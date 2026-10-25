package edu.oslab.threadpool.web.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One SSE subscription: a bounded queue plus a dedicated platform thread that drains it into the
 * response.
 *
 * <p>The delivery thread is created with {@code Thread.ofPlatform()} and is deliberately not a pool
 * worker — it is web-layer plumbing, so it does not touch the pool's threading invariants.
 *
 * <p>Backpressure is explicit: a subscriber that cannot keep up has its queue overflow, the
 * subscription is closed with an error, and the client reconnects with {@code Last-Event-ID} to
 * replay the events it missed from {@link EventRecorder}'s in-memory trace.
 */
public final class SseSubscriber implements EventSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(SseSubscriber.class);

    private record Delivery(PoolEvent event, boolean terminal) {
        static Delivery of(PoolEvent event) {
            return new Delivery(event, false);
        }

        static Delivery end() {
            return new Delivery(null, true);
        }
    }

    private final EventRecorder recorder;
    private final String id;
    private final SseWriter writer;
    private final BlockingQueue<Delivery> queue;
    private final Duration heartbeatInterval;
    private final List<PoolEvent> replay = new ArrayList<>();
    private final Thread deliveryThread;
    private volatile boolean finishing;
    private volatile boolean overflow;
    private volatile boolean closed;

    SseSubscriber(
            EventRecorder recorder,
            String id,
            SseWriter writer,
            int queueCapacity,
            Duration heartbeatInterval) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.id = Objects.requireNonNull(id, "id");
        this.writer = Objects.requireNonNull(writer, "writer");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        this.deliveryThread = Thread.ofPlatform().name("sse-" + id).unstarted(this::deliver);
    }

    @Override
    public String id() {
        return id;
    }

    /** Events the client asked for through {@code Last-Event-ID}; sent before anything queued live. */
    void replay(List<PoolEvent> missedEvents) {
        replay.addAll(missedEvents);
    }

    /** Starts the delivery thread. Called after replay is set, so ordering stays strict. */
    void start() {
        deliveryThread.start();
    }

    @Override
    public void publish(PoolEvent event) {
        if (closed) {
            return;
        }
        if (!queue.offer(Delivery.of(event))) {
            overflow = true;
        }
    }

    @Override
    public void finish() {
        if (closed) {
            return;
        }
        finishing = true;
        queue.offer(Delivery.end());
    }

    /** Stops delivery because the client went away or the run's trace was discarded. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        queue.clear();
        queue.offer(Delivery.end());
    }

    public boolean isClosed() {
        return closed;
    }

    private void deliver() {
        try {
            for (PoolEvent event : replay) {
                writer.send(event);
            }
            while (!closed) {
                if (overflow) {
                    LOGGER.debug("Closing SSE subscriber {}: it could not keep up with the run", id);
                    writer.completeWithError(new IllegalStateException("SSE subscriber " + id + " fell behind"));
                    return;
                }
                Delivery delivery = queue.poll(heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (delivery == null) {
                    if (finishing) {
                        break;
                    }
                    writer.comment("heartbeat");
                    continue;
                }
                if (delivery.terminal()) {
                    break;
                }
                writer.send(delivery.event());
            }
            writer.complete();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.completeWithError(exception);
        } catch (Throwable failure) {
            LOGGER.debug("SSE subscriber {} stopped delivering", id, failure);
            writer.completeWithError(failure);
        } finally {
            closed = true;
            recorder.removeSubscriber(this);
        }
    }
}
