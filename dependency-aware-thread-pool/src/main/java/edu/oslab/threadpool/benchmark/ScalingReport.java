package edu.oslab.threadpool.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ScalingReport(List<Row> rows) {
    public record Row(
            int tasks,
            String policy,
            double meanMakespanMillis,
            double makespanStdDevMillis,
            double meanThroughput,
            double averageWaitMillis,
            double averageTurnaroundMillis) {
    }

    public ScalingReport {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    public String formatTable() {
        StringBuilder table = new StringBuilder();
        table.append(String.format(
                "%8s %-22s %19s %12s %18s %14s %20s%n",
                "Tasks", "Policy", "Mean makespan (ms)", "Std dev", "Mean tasks/sec", "Avg wait (ms)",
                "Avg turnaround (ms)"));
        table.append("-".repeat(120)).append(System.lineSeparator());
        for (Row row : rows) {
            table.append(String.format(
                    "%8d %-22s %19.3f %12.3f %18.2f %14.3f %20.3f%n",
                    row.tasks(),
                    row.policy(),
                    row.meanMakespanMillis(),
                    row.makespanStdDevMillis(),
                    row.meanThroughput(),
                    row.averageWaitMillis(),
                    row.averageTurnaroundMillis()));
        }
        return table.toString();
    }

    public void writeCsv(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("tasks,policy,mean_makespan_ms,makespan_stddev_ms,mean_tasks_per_second,average_wait_ms,average_turnaround_ms");
            writer.newLine();
            for (Row row : rows) {
                writer.write(String.format(
                        "%d,\"%s\",%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        row.tasks(),
                        row.policy().replace("\"", "\"\""),
                        row.meanMakespanMillis(),
                        row.makespanStdDevMillis(),
                        row.meanThroughput(),
                        row.averageWaitMillis(),
                        row.averageTurnaroundMillis()));
            }
        }
    }
}
