package edu.oslab.threadpool.web.event;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * Writes events to a Spring MVC {@link SseEmitter}.
 *
 * <p>{@link SseEmitter} is not safe for concurrent sends, which is why every write for a subscriber
 * goes through that subscriber's single delivery thread.
 */
public final class SseEmitterWriter implements SseWriter {
    private final SseEmitter emitter;

    public SseEmitterWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void send(PoolEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.seq()))
                .name(event.type().name())
                .data(event));
    }

    @Override
    public void comment(String comment) throws IOException {
        emitter.send(SseEmitter.event().comment(comment));
    }

    @Override
    public void complete() {
        emitter.complete();
    }

    @Override
    public void completeWithError(Throwable failure) {
        emitter.completeWithError(failure);
    }
}
