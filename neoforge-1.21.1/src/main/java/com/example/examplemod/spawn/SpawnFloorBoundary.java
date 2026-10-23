package com.example.examplemod.spawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Cancels shared coplanar edges, including partial overlaps, without flattening separate storeys. */
public final class SpawnFloorBoundary {
    private SpawnFloorBoundary() {}

    public record Surface(double minX, double minZ, double maxX, double maxZ, double y) {}
    public record Segment(boolean alongX, double fixed, double y, double start, double end) {}
    private record Line(boolean alongX, double fixed, double y) {}

    public static List<Segment> outline(List<Surface> surfaces) {
        Map<Line, TreeMap<Double, Integer>> events = new HashMap<>();
        for (Surface surface : surfaces) {
            edge(events, new Line(true, surface.minZ(), surface.y()), surface.minX(), surface.maxX(), 1);
            edge(events, new Line(true, surface.maxZ(), surface.y()), surface.minX(), surface.maxX(), -1);
            edge(events, new Line(false, surface.minX(), surface.y()), surface.minZ(), surface.maxZ(), -1);
            edge(events, new Line(false, surface.maxX(), surface.y()), surface.minZ(), surface.maxZ(), 1);
        }
        List<Segment> result = new ArrayList<>();
        events.forEach((line, changes) -> {
            int balance = 0;
            Double beginning = null;
            for (var change : changes.entrySet()) {
                int next = balance + change.getValue();
                if (balance == 0 && next != 0) beginning = change.getKey();
                else if (balance != 0 && next == 0 && beginning != null) {
                    if (change.getKey() > beginning)
                        result.add(new Segment(line.alongX(), line.fixed(), line.y(), beginning, change.getKey()));
                    beginning = null;
                }
                balance = next;
            }
        });
        return List.copyOf(result);
    }

    private static void edge(Map<Line, TreeMap<Double, Integer>> lines, Line line,
            double start, double end, int orientation) {
        if (end <= start) return;
        TreeMap<Double, Integer> changes = lines.computeIfAbsent(line, ignored -> new TreeMap<>());
        changes.merge(start, orientation, Integer::sum);
        changes.merge(end, -orientation, Integer::sum);
    }
}
