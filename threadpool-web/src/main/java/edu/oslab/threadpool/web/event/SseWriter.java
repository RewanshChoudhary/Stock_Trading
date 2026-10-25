package edu.oslab.threadpool.web.event;

import java.io.IOException;

/**
 * The wire side of an SSE subscription. Kept behind an interface so the delivery thread, replay,
 * heartbeat, and backpressure behaviour can be tested without a servlet container.
 */
public interface SseWriter {

    void send(PoolEvent event) throws IOException;

    void comment(String comment) throws IOException;

    void complete();

    void completeWithError(Throwable failure);
}
