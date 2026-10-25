package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.TaskMetrics;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.RecoveryAdvisor;
import edu.oslab.threadpool.web.dto.CreateRunRequest;
import edu.oslab.threadpool.web.dto.CreateRunResponse;
import edu.oslab.threadpool.web.dto.RunMetricsResponse;
import edu.oslab.threadpool.web.dto.RunStateResponse;
import edu.oslab.threadpool.web.dto.TaskMetricsDto;
import edu.oslab.threadpool.web.dto.TaskSpecDto;
import edu.oslab.threadpool.web.error.ConcurrentRunLimitExceededException;
import edu.oslab.threadpool.web.error.RunCapacityExceededException;
import edu.oslab.threadpool.web.error.RunNotFoundException;
import edu.oslab.threadpool.web.error.RunValidationException;
import edu.oslab.threadpool.web.event.EventRecorder;
import edu.oslab.threadpool.web.event.PoolEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and lifecycle owner for simulator runs.
 *
 * <p>Every run gets its own pool and event recorder. Runs are cleaned up by a dedicated daemon
 * platform thread rather than a Spring {@code @Scheduled} task, so the web layer never touches a
 * framework-managed thread pool.
 */
@Service
public class RunService {

    /** Defaults and bounds for the run definition, matching the documented limits. */
    static final int DEFAULT_WORKER_COUNT = 4;
    static final long DEFAULT_QUANTUM_MS = 50;
    static final long MIN_QUANTUM_MS = 1;
    static final long MAX_QUANTUM_MS = 1_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(RunService.class);
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final RunLimitsProperties limits;
    private final SseProperties sseProperties;
    private final LlmAdvisorFactory llmAdvisorFactory;
    private final Thread janitor;

    public RunService(
            RunLimitsProperties limits, SseProperties sseProperties, LlmAdvisorFactory llmAdvisorFactory) {
        this.limits = limits;
        this.sseProperties = sseProperties;
        this.llmAdvisorFactory = llmAdvisorFactory;
        this.janitor = Thread.ofPlatform()
                .daemon(true)
                .name("run-janitor")
                .unstarted(this::sweepLoop);
    }

    @PostConstruct
    void startJanitor() {
        janitor.start();
    }

    @PreDestroy
    void stopJanitor() {
        janitor.interrupt();
        List<Run> activeRuns = runs.values().stream().filter(run -> !run.state().isTerminal()).toList();
        activeRuns.forEach(Run::cancel);

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        for (Run run : runs.values()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                LOGGER.warn("Application shutdown deadline reached with runs still registered");
                break;
            }
            try {
                run.shutdownAndAwait(Duration.ofNanos(remaining));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while stopping run {} during application shutdown", run.id());
                break;
            }
        }
    }

    public synchronized CreateRunResponse createRun(CreateRunRequest request) {
        if (request == null) {
            throw new RunValidationException("A run definition is required");
        }
        PolicyKind policy = PolicyKind.parse(request.policy());
        RecoveryModeKind recoveryMode = RecoveryModeKind.parse(request.recoveryMode());
        AdvisorKind advisorKind = AdvisorKind.parse(request.advisor());
        List<TaskSpecDto> tasks = request.tasks() == null ? List.of() : request.tasks();
        if (tasks.isEmpty()) {
            throw new RunValidationException("A run needs at least one task");
        }
        if (tasks.size() > limits.maxTasksPerRun()) {
            throw new RunCapacityExceededException(
                    "A run may contain at most " + limits.maxTasksPerRun() + " tasks, but " + tasks.size()
                            + " were submitted");
        }
        long activeRuns = runs.values().stream().filter(run -> !run.state().isTerminal()).count();
        if (activeRuns >= limits.maxConcurrentRuns()) {
            throw new ConcurrentRunLimitExceededException("At most " + limits.maxConcurrentRuns()
                    + " runs may be active at once, and " + activeRuns + " are still in flight");
        }
        long inFlightTasks = runs.values().stream()
                .filter(run -> !run.state().isTerminal())
                .mapToLong(Run::taskCount)
                .sum();
        if (inFlightTasks + tasks.size() > limits.maxTotalTasks()) {
            throw new RunCapacityExceededException("At most " + limits.maxTotalTasks()
                    + " tasks may be in flight across all runs, and this run needs "
                    + (inFlightTasks + tasks.size()));
        }

        int workerCount = resolveWorkerCount(request);
        long quantum = resolveQuantum(request, policy);
        List<TaskSpec> taskSpecs = TaskSpecFactory.fromDtos(tasks);
        String runId = UUID.randomUUID().toString();
        EventRecorder recorder = new EventRecorder(
                runId, Duration.ofSeconds(sseProperties.heartbeatSeconds()), sseProperties.queueCapacity());
        RecoveryAdvisor advisor = advisorKind == AdvisorKind.LLM
                ? llmAdvisorFactory.newAdvisor()
                : new edu.oslab.threadpool.RuleBasedRecoveryAdvisor();
        boolean hasExplicitRecoverySelection = request.recoveryMode() != null;
        edu.oslab.threadpool.RecoveryOptionProvider optionProvider = hasExplicitRecoverySelection
                && recoveryMode == RecoveryModeKind.RECOVER
                        ? RecoveryCatalog::optionsFor
                        : (task, reason) -> List.of();
        Run run = new Run(
                runId,
                policy,
                workerCount,
                quantum,
                taskSpecs,
                recorder,
                recoveryMode.coreMode(),
                advisorKind,
                advisor,
                optionProvider);
        runs.put(runId, run);
        LOGGER.info(
                "Created run {} ({} tasks, {} workers, policy {})",
                runId,
                taskSpecs.size(),
                workerCount,
                policy.name());
        return new CreateRunResponse(runId, run.state(), policy.name(), run.taskCount());
    }

    public RunStateResponse startRun(String runId) {
        Run run = require(runId);
        run.start();
        return stateOf(run);
    }

    public RunStateResponse runState(String runId) {
        return stateOf(require(runId));
    }

    public RunStateResponse cancelRun(String runId) {
        Run run = require(runId);
        run.cancel();
        return stateOf(run);
    }

    public void deleteRun(String runId) {
        Run run = runs.remove(requireRunId(runId));
        if (run == null) {
            throw new RunNotFoundException(String.valueOf(runId));
        }
        run.discard();
    }

    public void failTask(String runId, String taskId) {
        require(runId).failTask(taskId);
    }

    public RunMetricsResponse metrics(String runId) {
        Run run = require(runId);
        List<TaskMetrics> metrics = run.taskMetrics();
        double elapsedSeconds = Math.max(1, run.elapsedMillis()) / 1_000.0;
        int completed = (int) metrics.stream()
                .filter(task -> task.state() == edu.oslab.threadpool.TaskState.DONE)
                .count();
        int failed = (int) metrics.stream()
                .filter(task -> task.state() == edu.oslab.threadpool.TaskState.FAILED)
                .count();
        double averageWait = metrics.stream().mapToLong(TaskMetrics::waitingTimeNanos).average().orElse(0)
                / 1_000_000.0;
        double averageTurnaround = metrics.stream().mapToLong(TaskMetrics::turnaroundTimeNanos).average().orElse(0)
                / 1_000_000.0;
        return new RunMetricsResponse(
                run.id(),
                run.state(),
                run.policy().name(),
                run.elapsedMillis(),
                completed / elapsedSeconds,
                averageWait,
                averageTurnaround,
                metrics.size(),
                completed,
                failed,
                (int) run.recorder().events().stream()
                        .filter(event -> event.type() == edu.oslab.threadpool.web.event.PoolEventType.TASK_RECOVERED)
                        .count(),
                (int) run.recorder().events().stream()
                        .filter(event -> event.type() == edu.oslab.threadpool.web.event.PoolEventType.AI_DECISION)
                        .count(),
                run.taskStateCounts(),
                metrics.stream().map(RunService::toDto).toList(),
                run.recoveryMode().name(),
                run.advisorKind().name());
    }

    /** The recorded trace of a run, in sequence order. */
    public List<PoolEvent> trace(String runId) {
        return require(runId).recorder().events();
    }

    /**
     * Subscribes to a run's SSE stream. Events after {@code lastEventId} are replayed first, then
     * live events follow; if the run already ended the stream is completed after the replay.
     */
    public SseEmitter subscribe(String runId, Long lastEventId) {
        Run run = require(runId);
        SseEmitter emitter = new SseEmitter((long) sseProperties.streamTimeoutMillis());
        return run.recorder().subscribe(emitter, lastEventId == null ? 0L : lastEventId);
    }

    /** Drops runs that finished more than the configured retention window ago. */
    public int sweepExpiredRuns() {
        Duration retention = Duration.ofSeconds(limits.runRetentionSeconds());
        long now = System.nanoTime();
        int removed = 0;
        for (Map.Entry<String, Run> entry : runs.entrySet()) {
            Run run = entry.getValue();
            if (run.isExpired(now, retention) && runs.remove(entry.getKey(), run)) {
                run.discard();
                removed++;
            }
        }
        return removed;
    }

    public int runCount() {
        return runs.size();
    }

    private void sweepLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(SWEEP_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                int removed = sweepExpiredRuns();
                if (removed > 0) {
                    LOGGER.debug("Retention sweep dropped {} finished run(s)", removed);
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Run retention sweep failed", failure);
            }
        }
    }

    private RunStateResponse stateOf(Run run) {
        return new RunStateResponse(
                run.id(),
                run.state(),
                run.policy().name(),
                run.taskCount(),
                run.readyCount(),
                run.workerCount(),
                run.elapsedMillis(),
                run.taskStateCounts(),
                run.recoveryMode().name(),
                run.advisorKind().name());
    }

    private Run require(String runId) {
        Run run = runs.get(requireRunId(runId));
        if (run == null) {
            throw new RunNotFoundException(String.valueOf(runId));
        }
        return run;
    }

    private String requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new RunNotFoundException(String.valueOf(runId));
        }
        return runId;
    }

    private int resolveWorkerCount(CreateRunRequest request) {
        int workerCount = request.workerCount() == null ? DEFAULT_WORKER_COUNT : request.workerCount();
        if (workerCount < 1 || workerCount > limits.maxWorkersPerRun()) {
            throw new RunValidationException(
                    "workerCount must be between 1 and " + limits.maxWorkersPerRun() + ", but was " + workerCount);
        }
        return workerCount;
    }

    private long resolveQuantum(CreateRunRequest request, PolicyKind policy) {
        long quantum = request.quantum() == null ? DEFAULT_QUANTUM_MS : request.quantum();
        if (quantum < MIN_QUANTUM_MS || quantum > MAX_QUANTUM_MS) {
            throw new RunValidationException("quantum must be between " + MIN_QUANTUM_MS + " and " + MAX_QUANTUM_MS
                    + " ms, but was " + quantum);
        }
        return policy.usesQuantum() ? quantum : DEFAULT_QUANTUM_MS;
    }

    private static TaskMetricsDto toDto(TaskMetrics metrics) {
        return new TaskMetricsDto(
                metrics.id(),
                metrics.state(),
                metrics.lane(),
                metrics.slicesRun(),
                metrics.waitingTimeNanos() / 1_000_000.0,
                metrics.turnaroundTimeNanos() / 1_000_000.0,
                metrics.failureMessage());
    }
}
