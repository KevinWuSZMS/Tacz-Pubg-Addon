package com.example.examplemod.zones.match.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Bounded, incremental four-neighbour walking A*. The terrain reader supplies loaded safe cells. */
public final class BotPathfinder {
    public record Cell(int x, int y, int z) {}
    @FunctionalInterface public interface Terrain { Cell step(Cell from, int x, int z); }
    private record Node(Cell cell, double cost, double score) {}
    private static final int MAX_EXPANSIONS = 512;
    private final Cell start;
    private final int targetX, targetZ;
    private final Integer targetY;
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score)
            .thenComparingInt(node -> node.cell().x()).thenComparingInt(node -> node.cell().z()));
    private final Map<Cell, Double> costs = new HashMap<>();
    private final Map<Cell, Cell> parents = new HashMap<>();
    private Cell closest;
    private int expanded;
    private boolean finished;
    private List<Cell> result = List.of();

    public BotPathfinder(Cell start, int targetX, int targetZ) {
        this(start, targetX, targetZ, null);
    }
    public BotPathfinder(Cell start, Cell target) { this(start, target.x(), target.z(), target.y()); }
    private BotPathfinder(Cell start, int targetX, int targetZ, Integer targetY) {
        this.start = start; this.targetX = targetX; this.targetZ = targetZ; this.targetY = targetY; closest = start;
        costs.put(start, 0D); open.add(new Node(start, 0, heuristic(start)));
    }
    public boolean finished() { return finished; }
    public int expanded() { return expanded; }
    public List<Cell> path() { return result; }
    public void advance(Terrain terrain, BotRules.Budget budget, int allowance) {
        int used = 0;
        while (!finished && !open.isEmpty() && expanded < MAX_EXPANSIONS && used < allowance && budget.node()) {
            used++;
            Node node = open.poll();
            if (node.cost() > costs.getOrDefault(node.cell(), Double.POSITIVE_INFINITY)) continue;
            expanded++;
            if (heuristic(node.cell()) < heuristic(closest)) closest = node.cell();
            if (node.cell().x() == targetX && node.cell().z() == targetZ && (targetY == null || node.cell().y() == targetY)) { finish(node.cell()); return; }
            for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = node.cell().x() + offset[0], z = node.cell().z() + offset[1];
                if (Math.abs(x - start.x()) > 28 || Math.abs(z - start.z()) > 28) continue;
                Cell next = terrain.step(node.cell(), x, z);
                if (next == null || next.x() != x || next.z() != z || next.y() - node.cell().y() > 1
                        || node.cell().y() - next.y() > 2) continue;
                double cost = node.cost() + 1 + Math.abs(next.y() - node.cell().y()) * .35;
                if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                costs.put(next, cost); parents.put(next, node.cell());
                open.add(new Node(next, cost, cost + heuristic(next)));
            }
        }
        if (!finished && (open.isEmpty() || expanded >= MAX_EXPANSIONS)) finish(closest);
    }
    private double heuristic(Cell cell) { return Math.abs(cell.x() - targetX) + Math.abs(cell.z() - targetZ)
            + (targetY == null ? 0 : Math.abs(cell.y() - targetY) * .35); }
    private void finish(Cell end) {
        finished = true; ArrayList<Cell> reversed = new ArrayList<>();
        for (Cell cell = end; cell != null && !cell.equals(start); cell = parents.get(cell)) reversed.add(cell);
        ArrayList<Cell> forward = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) forward.add(reversed.get(index));
        result = List.copyOf(forward); open.clear(); parents.clear(); costs.clear();
    }
}
