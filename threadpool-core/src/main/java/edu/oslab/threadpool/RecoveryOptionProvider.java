package edu.oslab.threadpool;

import java.util.List;

@FunctionalInterface
public interface RecoveryOptionProvider {
    /** Whether this provider has a catalog to consult for recovery. */
    default boolean isConfigured() {
        return true;
    }

    List<RecoveryOption> optionsFor(Task task, String failureReason);

    /** Provider used by strict/default compatibility paths with no recovery catalog. */
    static RecoveryOptionProvider none() {
        return new RecoveryOptionProvider() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public List<RecoveryOption> optionsFor(Task task, String failureReason) {
                return List.of();
            }
        };
    }
}
