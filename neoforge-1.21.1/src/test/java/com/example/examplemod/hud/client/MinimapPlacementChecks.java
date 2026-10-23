package com.example.examplemod.hud.client;

public final class MinimapPlacementChecks {
    public static void main(String[] args) {
        int checks = 0;
        for (int[] size : new int[][] {{320, 240}, {640, 360}, {1920, 1080}}) {
            var map = MinimapPlacementPolicy.bottomRight(size[0], size[1], 132, 132, 8);
            check(map.x() + map.width() == size[0] - 8 && map.y() + map.height() == size[1] - 8,
                    "New and restore-default bottom-right placement disagree"); checks++;
            var header = MinimapPlacementPolicy.header(map, 18, 0);
            check(header.x() == map.x() && header.width() == map.width() && header.y() + header.height() == map.y(),
                    "Header no longer exactly touches map top with the same width"); checks++;
            var weapons = MinimapPlacementPolicy.weaponsLeft(map, 96, 130, 4);
            check(weapons.x() + weapons.width() + 4 == map.x() && weapons.y() + weapons.height() == map.y() + map.height(),
                    "Weapon stack detached or flipped to the map right"); checks++;
            var clamped = MinimapPlacementPolicy.clampGroup(new HudLayoutModel.Rect(-100, -100, 132, 132),
                    size[0], size[1], 18, 3, 64, 4);
            check(clamped.x() >= 68 && clamped.y() >= 21, "Editor map drag hid its attached children"); checks++;
            var moved = new HudLayoutModel.Rect(map.x() - 37, map.y() - 14, map.width(), map.height());
            var movedHeader = MinimapPlacementPolicy.header(moved, 18, 0);
            var movedWeapons = MinimapPlacementPolicy.weaponsLeft(moved, 96, 130, 4);
            check(movedHeader.x() - header.x() == -37 && movedHeader.y() - header.y() == -14
                    && movedWeapons.x() - weapons.x() == -37 && movedWeapons.y() - weapons.y() == -14,
                    "Children did not follow the map's exact drag delta"); checks++;
        }
        var top = new HudLayoutModel.Rect(0, 0, 132, 132);
        check(MinimapPlacementPolicy.header(top, 18, 0).y() == -18, "Header flipped below a top-edge map"); checks++;
        var narrow = MinimapPlacementPolicy.weaponsLeft(new HudLayoutModel.Rect(40, 30, 132, 132), 96, 130, 4);
        check(narrow.x() == 0 && narrow.width() == 36, "Narrow left space did not compress the weapon stack"); checks++;
        check(MinimapPlacementPolicy.mapChild("match_phase") && MinimapPlacementPolicy.mapChild("weapon_stack")
                && !MinimapPlacementPolicy.mapChild("vitals"), "Map group membership incorrect"); checks++;
        for (String id : new String[] {"xaero_minimap", "match_phase", "weapon_stack"}) {
            check(!MinimapPlacementPolicy.movable(id, false) && MinimapPlacementPolicy.movable(id, true),
                    "An unavailable native minimap group can still be moved and saved: " + id); checks++;
        }
        check(MinimapPlacementPolicy.movable("vitals", false) && !MinimapPlacementPolicy.movable(null, true),
                "Native availability incorrectly blocks independent HUD editing"); checks++;
        for (double scale : new double[] {.5, .75, 1, 1.25, 1.5, 2, 3}) {
            for (int frame = 0; frame < 4; frame++) {
                int border = XaeroMapFrameGeometry.frameBorder(frame);
                var visible = XaeroMapFrameGeometry.visible(37, 53, 128, scale, border);
                check(Math.abs(visible.width() - (128 + border * 2) * scale) <= 1, "Module padding leaked into visible map width"); checks++;
                int moduleX = XaeroMapFrameGeometry.moduleCoordinate(visible.x(), 128, scale, border);
                int moduleY = XaeroMapFrameGeometry.moduleCoordinate(visible.y(), 128, scale, border);
                check(XaeroMapFrameGeometry.visible(moduleX, moduleY, 128, scale, border).equals(visible),
                        "Visible/module transform failed a representable pixel roundtrip"); checks++;
            }
        }
        System.out.println("MinimapPlacementChecks: " + checks + " passed; no game launched.");
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
