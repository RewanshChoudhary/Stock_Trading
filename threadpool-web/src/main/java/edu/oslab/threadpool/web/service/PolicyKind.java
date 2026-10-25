package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.scheduler.CriticalPathScheduler;
import edu.oslab.threadpool.scheduler.FcfsScheduler;
import edu.oslab.threadpool.scheduler.MlfqScheduler;
import edu.oslab.threadpool.scheduler.PriorityScheduler;
import edu.oslab.threadpool.scheduler.RoundRobinScheduler;
import edu.oslab.threadpool.scheduler.SchedulerPolicy;
import edu.oslab.threadpool.web.error.RunValidationException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.StringJoiner;

/** The scheduling policies the API accepts, and how each maps to a core {@link SchedulerPolicy}. */
public enum PolicyKind {
    FCFS("FCFS", "First come, first served"),
    PRIORITY("Priority (aging)", "Priority with linear aging so waiting tasks are promoted"),
    ROUND_ROBIN("Round Robin", "Fixed quantum, yielded tasks go to the back of the queue"),
    MLFQ("MLFQ", "Three-level feedback queue with periodic boosts"),
    CRITICAL_PATH("Critical Path", "Longest remaining dependency chain first");

    private static final Duration AGING_INTERVAL = Duration.ofMillis(250);

    private final String displayName;
    private final String description;

    PolicyKind(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Whether the policy honours the request's cooperative quantum. */
    public boolean usesQuantum() {
        return this != MLFQ && this != CRITICAL_PATH;
    }

    public SchedulerPolicy newScheduler(long quantumMillis) {
        return switch (this) {
            case FCFS -> new FcfsScheduler(quantumMillis);
            case PRIORITY -> new PriorityScheduler(AGING_INTERVAL, quantumMillis);
            case ROUND_ROBIN -> new RoundRobinScheduler(quantumMillis);
            case MLFQ -> new MlfqScheduler();
            case CRITICAL_PATH -> new CriticalPathScheduler();
        };
    }

    /** Accepts {@code "FCFS"}, {@code "round robin"}, {@code "critical-path"} and friends. */
    public static PolicyKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RunValidationException("policy is required; expected one of " + acceptedNames());
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (PolicyKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return kind;
            }
        }
        throw new RunValidationException("Unknown policy '" + raw + "'; expected one of " + acceptedNames());
    }

    public static String acceptedNames() {
        StringJoiner names = new StringJoiner(", ");
        Arrays.stream(values()).forEach(kind -> names.add(kind.name()));
        return names.toString();
    }
}
