package edu.oslab.threadpool.web.event;

import edu.oslab.threadpool.Lane;
import edu.oslab.threadpool.PoolEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Records every event of one run and fans it out to live SSE subscribers.
 *
 * <p>Sequence assignment, trace append, and fan-out all happen under a single monitor. That keeps
 * the trace strictly ordered even though slice events arrive from several worker threads at once,
 * and it makes subscribing atomic with the replay snapshot, so a reconnecting client can never miss
 * or duplicate an event. Event volume is one event per task state change, so the monitor is not a
 * throughput concern.
 *
 * <p>Listener callbacks only ever enqueue into a subscriber's bounded queue; no I/O happens on the
 * pool's threads.
 */
public final class EventRecorder implements PoolEventListener {

    private final String runId;
    private final long startNanos = System.nanoTime();
    private final Duration heartbeatInterval;
    private final int queueCapacity;
    private final List<PoolEvent> events = new ArrayList<>();
    private final Set<SseSubscriber> subscribers = new LinkedHashSet<>();
    private long sequence;
    private int subscriberSequence;
    private boolean terminated;
    private boolean aborted;

    public EventRecorder(String runId, Duration heartbeatInterval, int queueCapacity) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.queueCapacity = queueCapacity;
    }

    public String runId() {
        return runId;
    }

    /** The full trace of this run so far, in sequence order. */
    public List<PoolEvent> events() {
        synchronized (this) {
            return List.copyOf(events);
        }
    }

    /** Events with a sequence number greater than {@code lastSeq}, for {@code Last-Event-ID} replay. */
    public List<PoolEvent> eventsSince(long lastSeq) {
        synchronized (this) {
            return events.stream().filter(event -> event.seq() > lastSeq).toList();
        }
    }

    public long lastSeq() {
        synchronized (this) {
            return sequence;
        }
    }

    public boolean terminated() {
        synchronized (this) {
            return terminated;
        }
    }

    public int subscriberCount() {
        synchronized (this) {
            return subscribers.size();
        }
    }

    /** Subscribes an SSE response, replaying everything after {@code lastEventId}. */
    public SseEmitter subscribe(SseEmitter emitter, long lastEventId) {
        SseSubscriber subscriber = subscribe(new SseEmitterWriter(emitter), lastEventId);
        emitter.onCompletion(subscriber::close);
        emitter.onTimeout(subscriber::close);
        emitter.onError(failure -> subscriber.close());
        return emitter;
    }

    /** Subscribes any writer; used directly by tests and by the emitter overload above. */
    public SseSubscriber subscribe(SseWriter writer, long lastEventId) {
        SseSubscriber subscriber;
        synchronized (this) {
            subscriber = new SseSubscriber(
                    this,
                    runId + "-" + (++subscriberSequence),
                    writer,
                    queueCapacity,
                    heartbeatInterval);
            subscriber.replay(eventsSince(lastEventId));
            subscribers.add(subscriber);
            if (terminated) {
                subscriber.finish();
            }
        }
        subscriber.start();
        return subscriber;
    }

    public void removeSubscriber(SseSubscriber subscriber) {
        synchronized (this) {
            subscribers.remove(subscriber);
        }
    }

    /**
     * Emits {@link PoolEventType#RUN_FINISHED} unless the pool already reported the end of the run,
     * and closes every live subscription once they have drained.
     */
    public void ensureRunFinished() {
        synchronized (this) {
            if (terminated || aborted) {
                return;
            }
            dispatch(PoolEvent.builder(PoolEventType.RUN_FINISHED));
            finishSubscribersLocked();
        }
    }

    /** Drops every subscription, for example when a run is deleted. */
    public void closeAll() {
        synchronized (this) {
            for (SseSubscriber subscriber : List.copyOf(subscribers)) {
                subscriber.close();
            }
            subscribers.clear();
        }
    }

    @Override
    public void onRunStarted(int workerCount, String policyName) {
        publish(PoolEvent.builder(PoolEventType.RUN_STARTED)
                .run(policyName, workerCount)
                .poolSize(workerCount));
    }

    @Override
    public void onRunFinished() {
        terminate(PoolEvent.builder(PoolEventType.RUN_FINISHED));
    }

    @Override
    public void onRunAborted() {
        synchronized (this) {
            if (terminated || aborted) {
                return;
            }
            aborted = true;
            dispatch(PoolEvent.builder(PoolEventType.RUN_ABORTED).message("Run cancelled"));
        }
    }

    @Override
    public void onTaskSubmitted(String taskId, int priority, long burstMs, Set<String> dependencies) {
        publish(PoolEvent.builder(PoolEventType.TASK_SUBMITTED)
                .task(taskId)
                .submission(priority, burstMs, List.copyOf(dependencies)));
    }

    @Override
    public void onTaskReady(String taskId, int readyQueueDepth) {
        publish(PoolEvent.builder(PoolEventType.TASK_READY)
                .task(taskId)
                .readyQueueDepth(readyQueueDepth));
    }

    @Override
    public void onSliceStarted(String taskId, String workerName, Lane lane) {
        publish(PoolEvent.builder(PoolEventType.SLICE_STARTED).task(taskId).worker(workerName, lane));
    }

    @Override
    public void onSliceEnded(
            String taskId,
            String workerName,
            Lane lane,
            long sliceMs,
            edu.oslab.threadpool.PoolEventListener.SliceOutcome outcome) {
        publish(PoolEvent.builder(PoolEventType.SLICE_ENDED)
                .task(taskId)
                .worker(workerName, lane)
                .slice(sliceMs, edu.oslab.threadpool.web.event.SliceOutcome.fromCore(outcome)));
    }

    @Override
    public void onTaskCompleted(String taskId) {
        publish(PoolEvent.builder(PoolEventType.TASK_COMPLETED).task(taskId));
    }

    @Override
    public void onTaskFailed(String taskId, String reason) {
        publish(PoolEvent.builder(PoolEventType.TASK_FAILED).task(taskId).message(reason));
    }

    @Override
    public void onTaskRecovering(String taskId, String reason) {
        publish(PoolEvent.builder(PoolEventType.TASK_RECOVERING).task(taskId).message(reason));
    }

    @Override
    public void onAiDecision(String taskId, String source, String optionId, String reason, boolean fallback) {
        publish(PoolEvent.builder(PoolEventType.AI_DECISION)
                .task(taskId)
                .message(reason)
                .decision(source, optionId, fallback));
    }

    @Override
    public void onTaskRecovered(String taskId, String optionId) {
        publish(PoolEvent.builder(PoolEventType.TASK_RECOVERED)
                .task(taskId)
                .decision(null, optionId, false));
    }

    @Override
    public void onTaskCascadeFailed(String taskId, String causeTaskId) {
        publish(PoolEvent.builder(PoolEventType.TASK_CASCADE_FAILED)
                .task(taskId)
                .cause(causeTaskId));
    }

    @Override
    public void onMlfqDemoted(String taskId, int fromLevel, int toLevel) {
        publish(PoolEvent.builder(PoolEventType.MLFQ_DEMOTED).task(taskId).level(fromLevel, toLevel));
    }

    @Override
    public void onMlfqBoosted() {
        publish(PoolEvent.builder(PoolEventType.MLFQ_BOOSTED));
    }

    @Override
    public void onPoolResized(int newSize) {
        publish(PoolEvent.builder(PoolEventType.POOL_RESIZED).poolSize(newSize));
    }

    private void publish(PoolEvent.Builder builder) {
        synchronized (this) {
            if (terminated || aborted) {
                // A terminal event closes the trace and the stream: work that was already in flight
                // when the run finished or was cancelled must not append to a closed record.
                return;
            }
            dispatch(builder);
        }
    }

    private void terminate(PoolEvent.Builder builder) {
        synchronized (this) {
            if (terminated) {
                return;
            }
            dispatch(builder);
            finishSubscribersLocked();
        }
    }

    private void finishSubscribersLocked() {
        terminated = true;
        for (SseSubscriber subscriber : subscribers) {
            subscriber.finish();
        }
    }

    private void dispatch(PoolEvent.Builder builder) {
        PoolEvent event = builder.build(++sequence, elapsedMillis());
        events.add(event);
        for (SseSubscriber subscriber : subscribers) {
            subscriber.publish(event);
        }
    }

    private long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
