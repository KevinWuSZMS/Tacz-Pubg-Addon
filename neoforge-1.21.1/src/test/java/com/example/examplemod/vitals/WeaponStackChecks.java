package com.example.examplemod.vitals;

import com.example.examplemod.hud.client.HudLayoutModel.Rect;
import com.example.examplemod.vitals.client.WeaponStackLayout;
import com.example.examplemod.vitals.client.WeaponStackRenderer;
import java.util.List;

/** Map-relative layout invariants for the five-row side stack, without a game client. */
public final class WeaponStackChecks {
    private static int checks;

    public static void main(String[] args) {
        var map = new Rect(500, 120, 192, 101);
        var bounds = WeaponStackLayout.bounds(map, 96);
        check(bounds.y() == map.y() && bounds.height() == map.height(),
                "The side stack spans exactly the map top and bottom, including odd map heights");
        check(right(bounds) == map.x() - 4 && bounds.width() == 96,
                "The side stack stays left of the map with the configured width and four-pixel gap");
        var moved = WeaponStackLayout.bounds(new Rect(537, 94, 192, 101), 96);
        check(moved.x() - bounds.x() == 37 && moved.y() - bounds.y() == -26
                        && moved.width() == bounds.width() && moved.height() == bounds.height(),
                "Moving the map translates the complete stack without changing its geometry");
        var resized = WeaponStackLayout.bounds(new Rect(500, 120, 384, 202), 96);
        check(resized.height() == 202 && resized.y() == 120 && resized.width() == 96
                        && bottom(resized) == 322,
                "A resized map changes row spacing while preserving the configured side-stack width");

        var occupied = WeaponStackRenderer.visibleSlots(List.of(true, false, true, false, true, true));
        check(occupied.equals(List.of(0, 2, 4)), "Empty slots pack away while real hotbar indices one, three and five survive");
        check(WeaponStackRenderer.visibleSlots(List.of(false, false, false, false, false)).isEmpty(),
                "An empty hotbar does not create live placeholder weapon rows");
        check(WeaponStackRenderer.visibleSlots(List.of(false, true)).equals(List.of(1)),
                "A short occupancy list retains its actual index");
        check(WeaponStackLayout.row(bounds, 0, 23, 3).area().y() == 201
                        && WeaponStackLayout.row(bounds, occupied.size() - 1, 23, 3).area().y() == 161,
                "Three occupied slots use the lowest three fifths rather than spreading to map height");
        occupancyTransitions(bounds);

        for (int mapHeight : new int[]{40, 79, 101, 128, 257})
            for (int availableWidth : new int[]{1, 12, 32, 64, 180})
                for (int iconHeight : new int[]{16, 23, 40})
                    for (int padding : new int[]{0, 3, 10}) {
                        var reference = new Rect(availableWidth + 4, 17, 128, mapHeight);
                        var stack = WeaponStackLayout.bounds(reference, 96);
                        check(stack.x() >= 0 && right(stack) == reference.x() - 4
                                        && stack.width() <= availableWidth && stack.width() <= 96,
                                "Narrow left margins shrink the stack without moving it across the map");
                        check(stack.y() == reference.y() && bottom(stack) == bottom(reference),
                                "Narrow widths and icon settings cannot change the map-height anchor");
                        int previousTop = bottom(stack), totalHeight = 0, smallest = Integer.MAX_VALUE, largest = 0;
                        for (int line = 0; line < 5; line++) {
                            var row = WeaponStackLayout.row(stack, line, iconHeight, padding);
                            check(bottom(row.area()) == previousTop,
                                    "Rows tile upward from the exact map bottom with no gap or overlap");
                            check(inside(stack, row.area()), "Every row remains in the map-height stack");
                            check(inside(row.area(), row.gun()) && inside(row.area(), row.smallItem()),
                                    "Gun silhouettes and small fallback icons stay inside their own row");
                            check(inside(row.area(), row.key()) && inside(row.area(), row.count()),
                                    "The rounded key label and count fit inside their row at narrow widths");
                            check(!overlaps(row.key(), row.count()),
                                    "A throwable count cannot cover its hotbar key label");
                            check(right(row.key()) == right(row.area()) && right(row.count()) == right(row.area()),
                                    "Key labels and quantities share the map-facing right edge");
                            check(row.smallItem().width() == row.smallItem().height(),
                                    "Fallback item art and throwable silhouettes preserve a square canvas");
                            previousTop = row.area().y();
                            totalHeight += row.area().height();
                            smallest = Math.min(smallest, row.area().height());
                            largest = Math.max(largest, row.area().height());
                        }
                        check(previousTop == stack.y() && totalHeight == stack.height() && largest - smallest <= 1,
                                "All five bands exactly cover odd map heights with at most one pixel of rounding difference");
                    }
        System.out.println("Weapon stack checks: " + checks + " passed; no game launched.");
    }

    private static void occupancyTransitions(Rect bounds) {
        for (int mask = 0; mask < 32; mask++) {
            var before = WeaponStackRenderer.visibleSlots(occupancy(mask));
            check(before.size() == Integer.bitCount(mask), "Every one of the 32 hotbar combinations draws only occupied slots");
            int represented = 0, previousSlot = -1, previousTop = bottom(bounds);
            for (int line = 0; line < before.size(); line++) {
                int slot = before.get(line);
                check(slot > previousSlot && slot < 5, "Compact rows retain increasing real slot indices without placeholders");
                represented |= 1 << slot;
                var row = WeaponStackLayout.row(bounds, line, 23, 3).area();
                check(bottom(row) == previousTop, "Missing any slot leaves no blank row below another occupied slot");
                previousSlot = slot;
                previousTop = row.y();
            }
            check(represented == mask, "Compaction neither omits an occupied hotbar slot nor invents an empty one");
            for (int changedSlot = 0; changedSlot < 5; changedSlot++) {
                var expected = new java.util.ArrayList<>(before);
                if ((mask & 1 << changedSlot) != 0) expected.remove(Integer.valueOf(changedSlot));
                else { expected.add(changedSlot); expected.sort(Integer::compareTo); }
                var after = WeaponStackRenderer.visibleSlots(occupancy(mask ^ 1 << changedSlot));
                check(after.equals(expected), "Inserting or removing any slot immediately recomputes the bottom-packed order");
            }
        }
    }

    private static List<Boolean> occupancy(int mask) {
        return java.util.stream.IntStream.range(0, 5).mapToObj(slot -> (mask & 1 << slot) != 0).toList();
    }

    private static int right(Rect rectangle) { return rectangle.x() + rectangle.width(); }
    private static int bottom(Rect rectangle) { return rectangle.y() + rectangle.height(); }
    private static boolean inside(Rect outer, Rect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && right(inner) <= right(outer) && bottom(inner) <= bottom(outer);
    }
    private static boolean overlaps(Rect first, Rect second) {
        return first.x() < right(second) && right(first) > second.x()
                && first.y() < bottom(second) && bottom(first) > second.y();
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
