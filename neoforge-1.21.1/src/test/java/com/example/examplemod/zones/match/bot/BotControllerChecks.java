package com.example.examplemod.zones.match.bot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure caps, finite work, movement safety and aiming checks; starts no Minecraft process. */
public final class BotControllerChecks {
    private static int checks;
    public static void main(String[] args) {
        reservations(); budgets(); angles(); friendlySegment(); walking(); boundedSearch();
        System.out.println("BotControllerChecks: " + checks + " assertions passed");
    }
    private static void reservations() {
        for (int existing = 0; existing <= 100; existing++) for (int requested = 0; requested <= 21; requested++) {
            boolean allowed;
            try { BotRules.reserve(existing, requested); allowed = true; } catch (IllegalArgumentException expected) { allowed = false; }
            check(allowed == (existing + requested <= 100), "online external bodies plus pending creation retain the global cap");
        }
        expect(() -> BotRules.reserve(-1, 0)); expect(() -> BotRules.reserve(101, 0)); expect(() -> BotRules.reserve(0, -1));
        check(BotRules.MAX_PARTICIPANTS == 100 && BotRules.MAX_BOTS == 100, "both hard caps equal twenty");
        check(BotRules.skill("easy").reactionTicks() > BotRules.skill("hard").reactionTicks(), "difficulty changes reaction time");
        check(BotRules.skill("easy").aimError() > BotRules.skill("hard").aimError(), "difficulty changes angular error");
        check(BotRules.skill("easy").compensation() < BotRules.skill("hard").compensation(), "difficulty changes recoil compensation");
        expect(() -> BotRules.skill("unknown"));
    }
    private static void budgets() {
        BotRules.Budget budget = new BotRules.Budget(3, 2);
        check(budget.ray() && budget.ray() && budget.ray() && !budget.ray(), "raycasts stop at the shared ceiling");
        check(budget.node() && budget.node() && !budget.node(), "path expansion stops at the shared ceiling");
        check(budget.raysLeft() == 0 && budget.nodesLeft() == 0, "exhausted budgets remain zero");
        expect(() -> new BotRules.Budget(-1, 0));
    }
    private static void angles() {
        check(BotRules.approach(179, -179, 5) == 181, "turning crosses yaw seam over the short arc");
        check(BotRules.approach(0, 90, 7) == 7, "yaw acceleration is limited per tick");
        check(BotRules.approach(10, 12, 7) == 12, "bounded turns converge exactly");
        for (int value = -1080; value <= 1080; value += 7)
            check(BotRules.wrap(value) >= -180 && BotRules.wrap(value) < 180, "angles stay in signed turn interval");
    }
    private static void friendlySegment() {
        near(BotRules.segmentDistanceSquared(5, 0, 0, 0, 0, 0, 10, 0, 0), 0, "ally in muzzle corridor blocks trigger");
        near(BotRules.segmentDistanceSquared(5, 2, 0, 0, 0, 0, 10, 0, 0), 4, "corridor includes perpendicular distance");
        near(BotRules.segmentDistanceSquared(15, 0, 0, 0, 0, 0, 10, 0, 0), 25, "finite segment excludes allies behind target");
        near(BotRules.segmentDistanceSquared(1, 2, 2, 0, 0, 0, 0, 0, 0), 9, "zero-length segment handles stationary aim");
    }
    private static void walking() {
        BotPathfinder search = new BotPathfinder(new BotPathfinder.Cell(0, 4, 0), 8, 0);
        BotPathfinder.Terrain wall = (from, x, z) -> x == 3 && z != 3 ? null : new BotPathfinder.Cell(x, 4, z);
        for (int frame = 0; frame < 100 && !search.finished(); frame++) {
            BotRules.Budget budget = new BotRules.Budget(0, 9); int before = search.expanded();
            search.advance(wall, budget, 4);
            check(search.expanded() - before <= 4 && budget.nodesLeft() >= 5, "incremental planning yields within local and server budgets");
        }
        check(search.finished() && !search.path().isEmpty(), "A-star completes route around a wall");
        check(search.path().getLast().equals(new BotPathfinder.Cell(8, 4, 0)), "route reaches requested destination");
        check(search.path().stream().anyMatch(cell -> cell.x() == 3 && cell.z() == 3), "route uses the actual doorway");
        BotPathfinder.Cell previous = new BotPathfinder.Cell(0, 4, 0);
        for (BotPathfinder.Cell cell : search.path()) {
            check(Math.abs(cell.x() - previous.x()) + Math.abs(cell.z() - previous.z()) == 1, "walking path has adjacent cardinal cells");
            check(!(cell.x() == 3 && cell.z() != 3), "path never crosses blocked wall cells"); previous = cell;
        }
        BotPathfinder steep = new BotPathfinder(new BotPathfinder.Cell(0, 4, 0), 2, 0);
        steep.advance((from, x, z) -> new BotPathfinder.Cell(x, x == 1 ? 8 : 4, z), new BotRules.Budget(0, 600), 600);
        check(steep.path().stream().noneMatch(cell -> cell.x() == 1), "planner rejects a supplied step above player jump height");
        BotPathfinder fall = new BotPathfinder(new BotPathfinder.Cell(0, 4, 0), 2, 0);
        fall.advance((from, x, z) -> new BotPathfinder.Cell(x, x == 1 ? 0 : 4, z), new BotRules.Budget(0, 600), 600);
        check(fall.path().stream().noneMatch(cell -> cell.x() == 1), "planner rejects a supplied unsafe drop");
        BotPathfinder upperFloor = new BotPathfinder(new BotPathfinder.Cell(0, 0, 0), new BotPathfinder.Cell(0, 2, 0));
        BotPathfinder.Terrain stairs = (from, x, z) -> {
            if (z != 0 || x < 0 || x > 2) return null;
            if (from.y() == 0 && x == 1) return new BotPathfinder.Cell(x, 1, z);
            if (from.y() == 1 && x == 2) return new BotPathfinder.Cell(x, 2, z);
            if (from.y() == 2) return new BotPathfinder.Cell(x, 2, z);
            return null;
        };
        upperFloor.advance(stairs, new BotRules.Budget(0, 100), 100);
        check(upperFloor.finished() && upperFloor.path().getLast().equals(new BotPathfinder.Cell(0, 2, 0)),
                "explicit coordinate movement follows stairs to the requested floor rather than matching XZ below it");
    }
    private static void boundedSearch() {
        BotPathfinder search = new BotPathfinder(new BotPathfinder.Cell(0, 4, 0), 5000, 5000);
        search.advance((from, x, z) -> new BotPathfinder.Cell(x, 4, z), new BotRules.Budget(0, 10000), 10000);
        check(search.finished() && search.expanded() <= 512, "distant goals terminate at bounded expansion count");
        check(search.path().stream().allMatch(cell -> Math.abs(cell.x()) <= 28 && Math.abs(cell.z()) <= 28), "search remains within local loaded-world horizon");
        BotPathfinder blocked = new BotPathfinder(new BotPathfinder.Cell(0, 4, 0), 5, 5);
        blocked.advance((from, x, z) -> null, new BotRules.Budget(0, 5), 5);
        check(blocked.finished() && blocked.path().isEmpty(), "isolated player returns a stable empty path");
        BotPathfinder noBudget = new BotPathfinder(new BotPathfinder.Cell(0, 4, 0), 1, 1);
        noBudget.advance((from, x, z) -> { throw new AssertionError("world reads after budget"); }, new BotRules.Budget(0, 0), 100);
        check(!noBudget.finished() && noBudget.expanded() == 0, "zero budget performs zero terrain reads");
    }
    private static void expect(Runnable call) {
        try { call.run(); throw new AssertionError("expected validation error"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-9, message); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
