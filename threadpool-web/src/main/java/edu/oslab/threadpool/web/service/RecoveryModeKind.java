package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.RecoveryMode;
import edu.oslab.threadpool.web.error.RunValidationException;

import java.util.Locale;

public enum RecoveryModeKind {
    STRICT,
    RECOVER;

    public RecoveryMode coreMode() {
        return RecoveryMode.valueOf(name());
    }

    public static RecoveryModeKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RECOVER;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new RunValidationException("Unknown recoveryMode '" + raw + "'; expected STRICT or RECOVER");
        }
    }
}
