package edu.oslab.threadpool;

import edu.oslab.threadpool.benchmark.BenchmarkHarness;
import edu.oslab.threadpool.benchmark.MetricsReporter;
import edu.oslab.threadpool.benchmark.RandomDagFactory;
import edu.oslab.threadpool.benchmark.ScalingReport;
import edu.oslab.threadpool.benchmark.ScalingStudy;
import edu.oslab.threadpool.scheduler.FcfsScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkFeaturesTest {
    @Test
    void randomDagIsDeterministicAndAcceptedByDependencyGraph() {
        List<TaskSpec> first = RandomDagFactory.generate(30, 0.3, 9123);
        List<TaskSpec> second = RandomDagFactory.generate(30, 0.3, 9123);
        assertEquals(
                first.stream().map(BenchmarkFeaturesTest::signature).toList(),
                second.stream().map(BenchmarkFeaturesTest::signature).toList());
        DependencyGraph graph = new DependencyGraph();
        assertTrue(graph.registerBatch(first).contains("random-task-0"));
        assertEquals(30, graph.topologicalOrder().size());
    }

    @Test
    void scalingStudyReturnsAStatisticalRowForEveryPolicy() throws Exception {
        ScalingReport report = new ScalingStudy(0.2).run(new int[]{8}, 1, 17);
        assertEquals(5, report.rows().size());
        assertTrue(report.formatTable().contains("Mean makespan (ms)"));
        assertTrue(report.rows().stream().allMatch(row -> row.tasks() == 8));
    }

    @Test
    void benchmarkReportsPerLaneCountsAndMetrics() throws Exception {
        BenchmarkHarness harness = new BenchmarkHarness(2, Duration.ofSeconds(3));
        var results = harness.compare(
                () -> List.of(
                        new TaskSpec("platform", 0, 1, Set.of(), quantum -> SliceResult.done()),
                        new TaskSpec("virtual", 0, 1, Set.of(), quantum -> {
                            Thread.sleep(5);
                            return SliceResult.done();
                        }, true)),
                List.of(FcfsScheduler::new));
        assertEquals(1, results.getFirst().platformTaskCount());
        assertEquals(1, results.getFirst().virtualTaskCount());
        String formatted = MetricsReporter.format(results);
        assertTrue(formatted.contains("Per-lane metrics"));
        assertTrue(formatted.contains("Virtual wait"));
    }

    private static String signature(TaskSpec spec) {
        return spec.id() + "|" + spec.priority() + "|" + spec.estimatedBurstMillis() + "|" + spec.dependencies();
    }
}
