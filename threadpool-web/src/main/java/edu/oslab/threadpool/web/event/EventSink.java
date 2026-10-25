package edu.oslab.threadpool.web.event;

/**
 * Destination for recorded events.
 *
 * <p>{@link EventRecorder} calls {@link #publish} from whichever pool thread produced the event, so
 * implementations must never block: a sink owns its own queue and its own delivery thread.
 */
public interface EventSink {

    /** Queues an event for delivery. Must return quickly and must not throw. */
    void publish(PoolEvent event);

    /** Signals that the run is over and no further events will be published. */
    void finish();

    /** The subscriber's own identifier, used in thread names and logs. */
    String id();
}
