package edu.oslab.threadpool.web.dto;

import java.util.List;

/**
 * One task of a run as sent by the frontend.
 *
 * <p>{@code dependsOn} names tasks of the same run. {@code burstMs} is the simulated work length,
 * {@code failAtMs} (optional) makes the task fail once its simulated elapsed time reaches that
 * offset, and {@code blocking} routes it to the virtual lane.
 */
public record TaskSpecDto(
        String id,
        Long burstMs,
        Integer priority,
        List<String> dependsOn,
        Long failAtMs,
        Boolean blocking) {

    public List<String> dependencies() {
        return dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
