package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.RecoveryOption;
import edu.oslab.threadpool.SimulatedWork;
import edu.oslab.threadpool.Task;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.web.dto.RecoveryCatalogEntry;

import java.util.List;
import java.util.Set;

/** Static, safe recovery choices shared by the rule-based and live advisors. */
public final class RecoveryCatalog {
    private static final List<RecoveryCatalogEntry> ENTRIES = List.of(
            new RecoveryCatalogEntry("retry", "RETRY", "Run the failed operation again", 20, 82, 2),
            new RecoveryCatalogEntry("cached-result", "CACHE", "Use the last verified cached result", 2, 92, 1),
            new RecoveryCatalogEntry("substitute", "SUBSTITUTE", "Run a compatible substitute operation", 8, 74, 1),
            new RecoveryCatalogEntry("skip-optional", "SKIP", "Skip an optional operation safely", 1, 48, 1));

    private RecoveryCatalog() {
    }

    public static List<RecoveryCatalogEntry> entries() {
        return ENTRIES;
    }

    public static List<RecoveryOption> optionsFor(Task task, String ignoredFailureReason) {
        long burst = Math.max(1, task.spec().estimatedBurstMillis());
        return List.of(
                option(task, ENTRIES.get(0), burst),
                option(task, ENTRIES.get(1), Math.max(1, Math.min(5, burst / 4))),
                option(task, ENTRIES.get(2), Math.max(1, Math.min(10, burst / 2))),
                option(task, ENTRIES.get(3), 1));
    }

    private static RecoveryOption option(Task task, RecoveryCatalogEntry entry, long burst) {
        TaskSpec execution = new TaskSpec(
                task.id() + "-" + entry.id(),
                task.spec().priority(),
                burst,
                Set.of(),
                new SimulatedWork(burst),
                false);
        return new RecoveryOption(
                entry.id(), entry.type(), entry.costMillis(), entry.quality(), entry.maxAttempts(), execution);
    }
}
