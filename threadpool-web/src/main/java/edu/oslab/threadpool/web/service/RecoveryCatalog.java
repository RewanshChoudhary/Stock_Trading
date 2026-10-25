package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.RecoveryOption;
import edu.oslab.threadpool.SimulatedWork;
import edu.oslab.threadpool.Task;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.web.dto.RecoveryCatalogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static recovery choices shared by the rule-based and live advisors.
 *
 * <p>Demo workloads register failing task ids against a {@link Profile} so each recovery outcome
 * (cache, retry, substitute, skip, retry-then-cache, exhaustion cascade) is reachable without
 * relying on the LLM advisor. Unknown tasks keep the default four-option catalog.
 */
public final class RecoveryCatalog {
    private static final RecoveryCatalogEntry RETRY =
            new RecoveryCatalogEntry("retry", "RETRY", "Run the failed operation again", 20, 82, 2);
    private static final RecoveryCatalogEntry CACHE =
            new RecoveryCatalogEntry("cached-result", "CACHE", "Use the last verified cached result", 2, 92, 1);
    private static final RecoveryCatalogEntry SUBSTITUTE =
            new RecoveryCatalogEntry("substitute", "SUBSTITUTE", "Run a compatible substitute operation", 8, 74, 1);
    private static final RecoveryCatalogEntry SKIP =
            new RecoveryCatalogEntry("skip-optional", "SKIP", "Skip an optional operation safely", 1, 48, 1);

    private static final List<RecoveryCatalogEntry> ENTRIES = List.of(RETRY, CACHE, SUBSTITUTE, SKIP);

    /**
     * Failing task ids from the three Recovery presets. Leaf failures are intentionally absent —
     * they have no descendants, so the pool never opens a recovery session.
     */
    private static final Map<String, Profile> PROFILES = Map.ofEntries(
            // Recovery · Build Mesh
            Map.entry("bm-parse", Profile.CACHE_HIT),
            Map.entry("bm-unit-tests", Profile.FLAKY_RETRY),
            Map.entry("bm-optimize", Profile.SUBSTITUTE_PATH),
            Map.entry("bm-docs-lint", Profile.OPTIONAL_SKIP),
            Map.entry("bm-integration", Profile.RETRY_THEN_CACHE),
            Map.entry("bm-sign-artifact", Profile.EXHAUST),
            // Recovery · Service Graph
            Map.entry("sg-auth-compile", Profile.CACHE_HIT),
            Map.entry("sg-billing-tests", Profile.FLAKY_RETRY),
            Map.entry("sg-search-index", Profile.SUBSTITUTE_PATH),
            Map.entry("sg-docs-publish", Profile.OPTIONAL_SKIP),
            Map.entry("sg-contract-suite", Profile.RETRY_THEN_CACHE),
            Map.entry("sg-canary-gate", Profile.EXHAUST),
            // Recovery · Release Gate
            Map.entry("rg-image-scan", Profile.CACHE_HIT),
            Map.entry("rg-smoke-tests", Profile.FLAKY_RETRY),
            Map.entry("rg-load-probe", Profile.SUBSTITUTE_PATH),
            Map.entry("rg-changelog", Profile.OPTIONAL_SKIP),
            Map.entry("rg-staging-verify", Profile.RETRY_THEN_CACHE),
            Map.entry("rg-prod-health", Profile.EXHAUST));

    enum Profile {
        CACHE_HIT,
        FLAKY_RETRY,
        SUBSTITUTE_PATH,
        OPTIONAL_SKIP,
        RETRY_THEN_CACHE,
        EXHAUST,
        DEFAULT
    }

    private RecoveryCatalog() {
    }

    public static List<RecoveryCatalogEntry> entries() {
        return ENTRIES;
    }

    public static List<RecoveryOption> optionsFor(Task task, String ignoredFailureReason) {
        Profile profile = PROFILES.getOrDefault(task.id(), Profile.DEFAULT);
        long burst = Math.max(1, task.spec().estimatedBurstMillis());
        return switch (profile) {
            case CACHE_HIT -> List.of(
                    option(task, CACHE, Math.max(1, Math.min(5, burst / 4)), -1),
                    option(task, withQuality(SKIP, 20), 1, -1));
            case FLAKY_RETRY -> List.of(option(task, withQuality(RETRY, 95), burst, -1));
            case SUBSTITUTE_PATH -> List.of(
                    option(task, withQuality(SUBSTITUTE, 90), Math.max(1, Math.min(10, burst / 2)), -1),
                    option(task, withQuality(SKIP, 20), 1, -1));
            case OPTIONAL_SKIP -> List.of(
                    option(task, withQuality(SKIP, 96), 1, -1),
                    option(task, withQuality(RETRY, 40), burst, -1));
            case RETRY_THEN_CACHE -> List.of(
                    option(task, withAttempts(withQuality(RETRY, 98), 1), burst, 1),
                    option(task, withQuality(CACHE, 70), Math.max(1, Math.min(5, burst / 4)), -1));
            case EXHAUST -> List.of(
                    option(task, withAttempts(withQuality(RETRY, 90), 1), burst, 1),
                    option(task, withAttempts(withQuality(SUBSTITUTE, 80), 1), Math.max(1, Math.min(10, burst / 2)), 1));
            case DEFAULT -> defaultOptions(task, burst);
        };
    }

    private static List<RecoveryOption> defaultOptions(Task task, long burst) {
        List<RecoveryOption> options = new ArrayList<>(4);
        options.add(option(task, RETRY, burst, -1));
        options.add(option(task, CACHE, Math.max(1, Math.min(5, burst / 4)), -1));
        options.add(option(task, SUBSTITUTE, Math.max(1, Math.min(10, burst / 2)), -1));
        options.add(option(task, SKIP, 1, -1));
        return List.copyOf(options);
    }

    private static RecoveryCatalogEntry withQuality(RecoveryCatalogEntry entry, int quality) {
        return new RecoveryCatalogEntry(
                entry.id(), entry.type(), entry.description(), entry.costMillis(), quality, entry.maxAttempts());
    }

    private static RecoveryCatalogEntry withAttempts(RecoveryCatalogEntry entry, int maxAttempts) {
        return new RecoveryCatalogEntry(
                entry.id(), entry.type(), entry.description(), entry.costMillis(), entry.quality(), maxAttempts);
    }

    private static RecoveryOption option(Task task, RecoveryCatalogEntry entry, long burst, long failAtMs) {
        SimulatedWork work = failAtMs < 0 ? new SimulatedWork(burst) : new SimulatedWork(burst, failAtMs);
        TaskSpec execution = new TaskSpec(
                task.id() + "-" + entry.id(),
                task.spec().priority(),
                burst,
                Set.of(),
                work,
                false);
        return new RecoveryOption(
                entry.id(), entry.type(), entry.costMillis(), entry.quality(), entry.maxAttempts(), execution);
    }
}
