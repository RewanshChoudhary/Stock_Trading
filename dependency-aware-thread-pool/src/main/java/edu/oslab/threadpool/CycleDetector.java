package edu.oslab.threadpool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CycleDetector {
    private enum Color {
        WHITE,
        GRAY,
        BLACK
    }

    private CycleDetector() {
    }

    public static List<String> findCycle(Map<String, ? extends Set<String>> graph) {
        Map<String, Color> colors = new HashMap<>();
        for (String node : graph.keySet()) {
            colors.put(node, Color.WHITE);
        }

        List<String> path = new ArrayList<>();
        Map<String, Integer> pathIndexes = new HashMap<>();
        for (String node : graph.keySet()) {
            if (colors.get(node) == Color.WHITE) {
                List<String> cycle = visit(node, graph, colors, path, pathIndexes);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        return List.of();
    }

    private static List<String> visit(
            String node,
            Map<String, ? extends Set<String>> graph,
            Map<String, Color> colors,
            List<String> path,
            Map<String, Integer> pathIndexes) {
        colors.put(node, Color.GRAY);
        pathIndexes.put(node, path.size());
        path.add(node);

        Set<String> dependencies = graph.containsKey(node) ? graph.get(node) : Set.of();
        for (String dependency : dependencies) {
            Color color = colors.getOrDefault(dependency, Color.WHITE);
            if (color == Color.GRAY) {
                List<String> cycle = new ArrayList<>(path.subList(pathIndexes.get(dependency), path.size()));
                cycle.add(dependency);
                return cycle;
            }
            if (color == Color.WHITE) {
                List<String> cycle = visit(dependency, graph, colors, path, pathIndexes);
                if (cycle != null) {
                    return cycle;
                }
            }
        }

        path.removeLast();
        pathIndexes.remove(node);
        colors.put(node, Color.BLACK);
        return null;
    }
}
