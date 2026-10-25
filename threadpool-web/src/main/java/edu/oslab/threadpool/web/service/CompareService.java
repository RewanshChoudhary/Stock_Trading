package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.DependencyGraph;
import edu.oslab.threadpool.web.dto.CompareJobResponse;
import edu.oslab.threadpool.web.dto.CompareRequest;
import edu.oslab.threadpool.web.dto.CompareResultResponse;
import edu.oslab.threadpool.web.dto.TaskSpecDto;
import edu.oslab.threadpool.web.error.RunCapacityExceededException;
import edu.oslab.threadpool.web.error.RunNotFoundException;
import edu.oslab.threadpool.web.error.RunValidationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts and tracks headless policy comparison jobs.
 *
 * <p>Compare jobs are deliberately kept out of {@link RunService}: they produce no events and no
 * pool is ever exposed to a client, so they cannot be mistaken for an interactive run.
 */
@Service
public class CompareService {

    static final int DEFAULT_WORKER_COUNT = 4;
    static final long DEFAULT_QUANTUM_MS = 50;
    static final int DEFAULT_REPETITIONS = 3;
    static final int MAX_REPETITIONS = 10;

    private final Map<String, CompareJob> jobs = new ConcurrentHashMap<>();
    private final RunLimitsProperties limits;

    public CompareService(RunLimitsProperties limits) {
        this.limits = limits;
    }

    public CompareJobResponse start(CompareRequest request) {
        if (request == null) {
            throw new RunValidationException("A compare request is required");
        }
        List<TaskSpecDto> tasks = request.tasks() == null ? List.of() : request.tasks();
        if (tasks.isEmpty()) {
            throw new RunValidationException("A compare request needs at least one task");
        }
        if (tasks.size() > limits.maxTasksPerRun()) {
            throw new RunCapacityExceededException("A compare workload may contain at most "
                    + limits.maxTasksPerRun() + " tasks, but " + tasks.size() + " were submitted");
        }
        List<PolicyKind> policies = resolvePolicies(request.policies());
        int workerCount = resolveWorkerCount(request.workerCount());
        long quantum = resolveQuantum(request.quantum());
        int repetitions = resolveRepetitions(request.repetitions());

        // Reject cycles and unknown dependencies before starting a background job: the client can
        // still get a synchronous 4xx instead of polling a job that is doomed to fail.
        new DependencyGraph().registerBatch(TaskSpecFactory.fromDtos(tasks));

        CompareJob job = new CompareJob(
                UUID.randomUUID().toString(), tasks, policies, workerCount, quantum, repetitions);
        jobs.put(job.id(), job);
        job.start();
        return new CompareJobResponse(job.id(), job.state(), job.totalPolicies(), job.repetitions());
    }

    public CompareResultResponse result(String jobId) {
        CompareJob job = jobs.get(jobId == null ? "" : jobId);
        if (job == null) {
            throw new RunNotFoundException("compare job " + jobId);
        }
        return new CompareResultResponse(
                job.id(),
                job.state(),
                job.results().size(),
                job.totalPolicies(),
                job.error(),
                job.results());
    }

    private List<PolicyKind> resolvePolicies(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(PolicyKind.values());
        }
        if (raw.size() > PolicyKind.values().length) {
            throw new RunValidationException("At most " + PolicyKind.values().length + " policies can be compared");
        }
        List<PolicyKind> parsed = raw.stream().map(PolicyKind::parse).distinct().toList();
        if (parsed.isEmpty()) {
            throw new RunValidationException("At least one policy is required");
        }
        return parsed;
    }

    private int resolveWorkerCount(Integer requested) {
        int workerCount = requested == null ? DEFAULT_WORKER_COUNT : requested;
        if (workerCount < 1 || workerCount > limits.maxWorkersPerRun()) {
            throw new RunValidationException(
                    "workerCount must be between 1 and " + limits.maxWorkersPerRun() + ", but was " + workerCount);
        }
        return workerCount;
    }

    private long resolveQuantum(Long requested) {
        long quantum = requested == null ? DEFAULT_QUANTUM_MS : requested;
        if (quantum < RunService.MIN_QUANTUM_MS || quantum > RunService.MAX_QUANTUM_MS) {
            throw new RunValidationException("quantum must be between " + RunService.MIN_QUANTUM_MS + " and "
                    + RunService.MAX_QUANTUM_MS + " ms, but was " + quantum);
        }
        return quantum;
    }

    private int resolveRepetitions(Integer requested) {
        int repetitions = requested == null ? DEFAULT_REPETITIONS : requested;
        if (repetitions < 1 || repetitions > MAX_REPETITIONS) {
            throw new RunValidationException(
                    "repetitions must be between 1 and " + MAX_REPETITIONS + ", but was " + repetitions);
        }
        return repetitions;
    }
}
