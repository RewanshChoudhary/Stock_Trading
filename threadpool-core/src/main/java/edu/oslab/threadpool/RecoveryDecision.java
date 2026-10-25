package edu.oslab.threadpool;

import java.util.Objects;

/** Advisor output. The pool validates the selected option before scheduling it. */
public record RecoveryDecision(
        String optionId,
        String reason,
        String source,
        boolean fallback) {

    public RecoveryDecision {
        optionId = Objects.requireNonNull(optionId, "optionId").trim();
        reason = Objects.requireNonNull(reason, "reason").trim();
        source = Objects.requireNonNull(source, "source").trim();
        if (optionId.isEmpty() || reason.isEmpty() || source.isEmpty()) {
            throw new IllegalArgumentException("Recovery decisions must contain optionId, reason, and source");
        }
    }

    public static RecoveryDecision ruleBased(String optionId, String reason) {
        return new RecoveryDecision(optionId, reason, "Rule-based", false);
    }
}
