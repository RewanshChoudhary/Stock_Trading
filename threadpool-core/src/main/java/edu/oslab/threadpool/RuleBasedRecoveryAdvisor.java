package edu.oslab.threadpool;

/** Deterministic offline advisor used as the default and as every fallback path. */
public final class RuleBasedRecoveryAdvisor implements RecoveryAdvisor {
    @Override
    public RecoveryDecision choose(RecoveryContext context) {
        return context.options().stream()
                .filter(option -> !context.attemptedOptionIds().contains(option.id()))
                .max((left, right) -> Double.compare(score(left, context), score(right, context)))
                .map(option -> RecoveryDecision.ruleBased(
                        option.id(),
                        "Selected " + option.type() + " using quality, cost, priority, and downstream work"))
                .orElseThrow(() -> new IllegalStateException("No unused recovery option is available"));
    }

    private double score(RecoveryOption option, RecoveryContext context) {
        return option.quality() * (1.0 + context.priority() + context.downstreamWorkMillis() / 100.0)
                - option.costMillis();
    }
}
