package com.example.examplemod.spawn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Focused geometry/connectivity checks. No client, server or world is started. */
public final class SpawnAuthoringChecks {
    private static int checks;

    public static void main(String[] args) {
        var cube = SpawnFloorGeometry.topSurfaces(List.of(new AABB(0, 0, 0, 1, 1, 1)));
        check(cube.size() == 1 && cube.getFirst().y() == 1, "cube has a single top at y=1");
        var slab = SpawnFloorGeometry.topSurfaces(List.of(new AABB(0, 0, 0, 1, .5, 1)));
        check(slab.size() == 1 && slab.getFirst().y() == .5, "lower slab follows actual height");
        var stairs = SpawnFloorGeometry.topSurfaces(List.of(
                new AABB(0, 0, 0, 1, .5, 1), new AABB(0, .5, .5, 1, 1, 1)));
        check(stairs.size() == 2, "stairs split upper and lower treads");
        check(stairs.stream().filter(s -> s.minZ() == .5).allMatch(s -> s.y() == 1),
                "hidden lower stair surface is not drawn");
        check(Math.abs(area(stairs) - 1) < 1e-6, "stairs cover the floor exactly once");
        var partial = SpawnFloorGeometry.topSurfaces(List.of(new AABB(.25, 0, .25, .75, .5, .75)));
        check(Math.abs(area(partial) - .25) < 1e-6, "narrow support does not become a full tile");
        check(SpawnFloorGeometry.topSurfaces(List.of(new AABB(0, 0, 0, 1, 1.5, 1))).isEmpty(),
                "protruding fence geometry is rejected");
        check(SpawnFloorGeometry.topSurfaces(List.<AABB>of()).isEmpty(), "air has no floor");

        var twoTiles = SpawnFloorBoundary.outline(List.of(surface(0, 0, 1, 1, 65), surface(1, 0, 2, 1, 65)));
        check(twoTiles.size() == 4, "two adjacent floor cells have one four-edge perimeter");
        check(Math.abs(perimeter(twoTiles) - 6) < 1e-6, "internal shared edge is absent");
        var square = SpawnFloorBoundary.outline(List.of(surface(0, 0, 1, 1, 65), surface(1, 0, 2, 1, 65),
                surface(0, 1, 1, 2, 65), surface(1, 1, 2, 2, 65)));
        check(square.size() == 4 && perimeter(square) == 8, "confirmed plus preview union has no grid lines");
        var lShape = SpawnFloorBoundary.outline(List.of(surface(0, 0, 1, 1, 65), surface(1, 0, 2, 1, 65),
                surface(0, 1, 1, 2, 65)));
        check(lShape.size() == 6 && perimeter(lShape) == 8, "concave shape retains its actual exterior");
        var split = SpawnFloorBoundary.outline(List.of(surface(0, 0, .5, 1, 65), surface(.5, 0, 1, .5, 65),
                surface(.5, .5, 1, 1, 65)));
        check(split.size() == 4 && perimeter(split) == 4, "partial shape edges cancel without white T junctions");
        var storeys = SpawnFloorBoundary.outline(List.of(surface(0, 0, 1, 1, 65), surface(0, 0, 1, 1, 69)));
        check(storeys.size() == 8 && perimeter(storeys) == 8, "separate storeys never cancel each other's perimeter");
        var ring = new ArrayList<SpawnFloorBoundary.Surface>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++)
            if (x != 1 || z != 1) ring.add(surface(x, z, x + 1, z + 1, 65));
        check(perimeter(SpawnFloorBoundary.outline(ring)) == 16, "unmarked courtyard remains a hole in the boundary");

        check(SpawnAreaGroups.compute(List.of()).groupCount() == 0, "empty map has no groups");
        var floors = new ArrayList<>(List.of(new BlockPos(-10, 64, -20), new BlockPos(-9, 64, -20),
                new BlockPos(-10, 74, -20), new BlockPos(-10, 84, -20), new BlockPos(50, 64, 50)));
        var groups = SpawnAreaGroups.compute(floors);
        check(groups.groupCount() == 2, "horizontal adjacency and inclusive 10-block links merge");
        check(groups.groupByFloor().get(floors.get(0).asLong()).equals(groups.groupByFloor().get(floors.get(3).asLong())),
                "vertical grouping is transitive across multiple floors");
        check(groups.groupByFloor().size() == floors.size(), "grouping never invents unapproved floors");
        Collections.reverse(floors);
        check(SpawnAreaGroups.compute(floors).groupByFloor().equals(groups.groupByFloor()),
                "group ids do not depend on selection iteration order");
        check(SpawnAreaGroups.compute(List.of(new BlockPos(0, 64, 0), new BlockPos(0, 75, 0))).groupCount() == 2,
                "11-block gap remains separate");
        check(SpawnAreaGroups.compute(List.of(new BlockPos(0, 64, 0), new BlockPos(1, 65, 0))).groupCount() == 2,
                "diagonally offset floors do not silently merge");
        check(SpawnAreaGroups.compute(List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1))).groupCount() == 2,
                "horizontal corner contact is not connectivity");
        System.out.println("Spawn authoring checks passed: " + checks);
    }

    private static double area(List<SpawnFloorGeometry.Surface> surfaces) {
        return surfaces.stream().mapToDouble(s -> (s.maxX() - s.minX()) * (s.maxZ() - s.minZ())).sum();
    }

    private static SpawnFloorBoundary.Surface surface(double x1, double z1, double x2, double z2, double y) {
        return new SpawnFloorBoundary.Surface(x1, z1, x2, z2, y);
    }

    private static double perimeter(List<SpawnFloorBoundary.Segment> lines) {
        return lines.stream().mapToDouble(line -> line.end() - line.start()).sum();
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
}
