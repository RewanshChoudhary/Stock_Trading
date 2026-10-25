package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.web.error.RunValidationException;

import java.util.Locale;

public enum AdvisorKind {
    RULE_BASED,
    LLM;

    public static AdvisorKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RULE_BASED;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new RunValidationException("Unknown advisor '" + raw + "'; expected RULE_BASED or LLM");
        }
    }
}
