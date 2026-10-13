package edu.oslab.threadpool;

import edu.oslab.threadpool.benchmark.BenchmarkHarness;
import edu.oslab.threadpool.benchmark.MetricsReporter;
import edu.oslab.threadpool.benchmark.WorkloadFactory;
import edu.oslab.threadpool.scheduler.FcfsScheduler;
import edu.oslab.threadpool.scheduler.MlfqScheduler;
import edu.oslab.threadpool.scheduler.PriorityScheduler;
import edu.oslab.threadpool.scheduler.RoundRobinScheduler;
import edu.oslab.threadpool.scheduler.SchedulerPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        WorkloadFactory pipeline = Main::buildPipelineWorkload;
        List<Supplier<? extends SchedulerPolicy>> policies = List.of(
                FcfsScheduler::new,
                PriorityScheduler::new,
                RoundRobinScheduler::new,
                MlfqScheduler::new);

        LOGGER.info("Running the build-pipeline workload under all four scheduling policies");
        List<edu.oslab.threadpool.benchmark.BenchmarkResult> results =
                new BenchmarkHarness(4).compare(pipeline, policies);
        System.out.println("\nScheduling policy comparison");
        System.out.println(MetricsReporter.format(results));

        LOGGER.info("Submitting a deliberately cyclic dependency batch");
        try (ThreadPoolManager pool = new ThreadPoolManager(2, new FcfsScheduler())) {
            pool.submitBatch(List.of(
                    task("cycle-a", 1, Set.of("cycle-b"), 1),
                    task("cycle-b", 1, Set.of("cycle-c"), 1),
                    task("cycle-c", 1, Set.of("cycle-a"), 1)));
            throw new IllegalStateException("The intentionally cyclic batch was unexpectedly accepted");
        } catch (DeadlockDetectedException exception) {
            System.out.println("Cycle rejection demo: " + exception.getMessage());
        }
    }

    private static List<TaskSpec> buildPipelineWorkload() {
        return List.of(
                task("repository-check", 4, Set.of(), 2),
                task("prepare-assets", 3, Set.of(), 2),
                task("compile-api", 8, Set.of("repository-check"), 3),
                task("compile-web", 7, Set.of("repository-check", "prepare-assets"), 3),
                task("test-api", 6, Set.of("compile-api"), 2),
                task("test-web", 5, Set.of("compile-web"), 2),
                task("assemble-release", 8, Set.of("test-api", "test-web"), 2),
                task("stage-release", 4, Set.of("assemble-release"), 2),
                task("publish-release", 2, Set.of("stage-release"), 2));
    }

    private static TaskSpec task(String id, int priority, Set<String> dependencies, int slices) {
        return new TaskSpec(
                id,
                priority,
                slices * 4L,
                dependencies,
                new DemoWork(id, slices));
    }

    private static final class DemoWork implements CooperativeWork {
        private static final Logger TASK_LOGGER = LoggerFactory.getLogger(DemoWork.class);
        private final String taskId;
        private final int totalSlices;
        private final AtomicInteger completedSlices = new AtomicInteger();

        private DemoWork(String taskId, int totalSlices) {
            this.taskId = taskId;
            this.totalSlices = totalSlices;
        }

        @Override
        public SliceResult runSlice(long quantumMillis) throws InterruptedException {
            int currentSlice = completedSlices.incrementAndGet();
            TASK_LOGGER.info(
                    "Task {} is performing cooperative slice {}/{} (budget {} ms)",
                    taskId, currentSlice, totalSlices, quantumMillis);
            Thread.sleep(4);
            return currentSlice >= totalSlices ? SliceResult.done() : SliceResult.yielded();
        }
    }
}
