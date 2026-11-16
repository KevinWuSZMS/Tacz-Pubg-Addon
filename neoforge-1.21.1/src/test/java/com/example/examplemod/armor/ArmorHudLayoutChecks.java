package com.example.examplemod.armor;

import com.example.examplemod.armor.client.ArmorHudLayout;
import com.example.examplemod.pubgsettings.PubgSettings;
import com.example.examplemod.vitals.client.CombatHudStyle;
import com.example.examplemod.vitals.client.CombatHudNumbers;
import com.example.examplemod.vitals.client.PubgVitalsLayout;

public final class ArmorHudLayoutChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        glyphCentering();
        oxygenReadout();
        var settings = PubgSettings.initial();
        check(settings.integer("hud.bar_width") == 182 && settings.enabled("hud.align_hotbar"),
                "New saves align the health/status frame to vanilla's 182px hotbar by default");
        var old = PubgSettings.parse("{\"hud.bar_width\":240}");
        check(CombatHudStyle.width(old) == 182 && old.integer("hud.bar_width") == 240,
                "Old explicit 240px settings become aligned without deleting their custom value");
        check(CombatHudStyle.width(PubgSettings.parse(old.with("hud.align_hotbar", 0).toJson())) == 240,
                "Turning off alignment restores the user's persisted custom width after serialization");
        for (int width : new int[]{182, 183, 192, 239, 240, 241, 264, 300, 319, 320, 321, 426, 427, 640, 853, 854}) {
            for (int height : new int[]{180, 240, 360, 480}) {
                var panel = ArmorHudLayout.panel(width, height, 182);
                var frame = PubgVitalsLayout.frame(width, height, PubgVitalsLayout.Style.defaults());
                check(panel.x() >= 0 && panel.x() + panel.width() <= width, "inside horizontal screen");
                check(panel.y() >= 0 && panel.y() + panel.height() <= height, "inside vertical screen");
                int centralLeft = width / 2 - 91;
                check(panel.x() == centralLeft && panel.width() == 182, "equipment row aligns with central boost bar");
                check(frame.left() == centralLeft && frame.width() == 182
                                && panel.x() == frame.left() && panel.x() + panel.width() == frame.left() + frame.width(),
                        "Health, energy and status share vanilla's exact integer-rounded left/right edges, including odd screens");
                check(panel.y() + panel.height() == height - 42,
                        "equipment row sits directly above boost without overlapping it at " + width + "x" + height);
                check(panel.height() == 24, "compact state row reserves readable colored equipment and percentages");
                for (int icon : new int[]{12,18,28}) {
                    var metrics=ArmorHudLayout.metrics(panel.width(),panel.height(),icon,2,24,10,10);
                    check(metrics.logicalWidth()*metrics.scale()<=panel.width()+1e-4
                            &&metrics.logicalHeight()*metrics.scale()<=panel.height()+1e-4,"All percentages, gun numbers and status icons fit the responsive row");
                    check(metrics.cellWidth()*3<metrics.logicalWidth(),"Equipment must leave a real region for firing mode/ammo/status instead of spreading across the entire bar");
                    check(metrics.scale() == 1, "The normal 182px state row retains full ammo and icon size");
                    check(Math.abs(metrics.groupWidth() - metrics.logicalWidth() / 3) < 1e-4,
                            "Normal counts use three equal equipment, weapon and status groups");
                    float origin = panel.x() + (panel.width() - metrics.logicalWidth() * metrics.scale()) / 2;
                    check(Math.abs(origin + metrics.loadedCenter() * metrics.scale() - width / 2) < 1e-4,
                            "Current ammunition stays on vanilla hotbar center, including odd screen widths");
                }
                int covered = 0, minimumCellWidth = Integer.MAX_VALUE, maximumCellWidth = 0;
                for (int i = 0; i < 3; i++) {
                    var cell = panel.cell(i);
                    check(cell.x() == panel.x() + covered && cell.y() == panel.y(), "three contiguous cells share one baseline");
                    check(cell.height() == panel.height(), "each equipment cell uses the complete row");
                    covered += cell.width();
                    minimumCellWidth = Math.min(minimumCellWidth, cell.width());
                    maximumCellWidth = Math.max(maximumCellWidth, cell.width());
                }
                check(covered == panel.width() && maximumCellWidth - minimumCellWidth <= 1,
                        "integer division distributes all width evenly without gaps or overflow");
                var moved = panel.move(37, -26);
                check(moved.x() == panel.x() + 37 && moved.y() == panel.y() - 26
                                && moved.width() == panel.width() && moved.height() == panel.height(),
                        "following a moved vitals anchor preserves equipment geometry");
                var topLeft = panel.move(-width, -height).inside(width, height);
                var bottomRight = panel.move(width, height).inside(width, height);
                check(topLeft.x() == 0 && topLeft.y() == 0, "following vitals to the top edge cannot hide equipment offscreen");
                check(bottomRight.x() + bottomRight.width() == width && bottomRight.y() + bottomRight.height() == height,
                        "following vitals to the bottom/right edges keeps all equipment cells visible");
            }
        }
        for (int width : new int[]{1, 80, 181, 182, 183, 240, 420}) {
            var frame = PubgVitalsLayout.frame(width, 180, PubgVitalsLayout.Style.defaults());
            check(frame.width() == Math.min(width, 182) && frame.left() >= 0 && frame.left() + frame.width() <= width,
                    "Only viewports narrower than the actual hotbar shrink its shared outer frame");
            for (int height : new int[]{18, 24, 40}) for (int icon : new int[]{12, 18, 28}) for (int gap : new int[]{0, 2, 10})
                for (int loaded : new int[]{6, 12, 18, 60}) for (int reserve : new int[]{6, 12, 24, 60}) {
                    var fit = ArmorHudLayout.metrics(width, height, icon, gap, 24, loaded, reserve);
                    check(fit.logicalWidth() * fit.scale() <= width + 1e-4
                                    && fit.logicalHeight() * fit.scale() <= height + 1e-4,
                            "Large counts and custom icon/gap/row settings fit inside the shared frame");
                    for (int slot = 0; slot < 3; slot++) {
                        float left = fit.cellLeft(slot), content = fit.cellContentWidth();
                        check(left >= 0 && left + content <= fit.groupWidth() + 1e-4
                                        && content + 1e-4 >= 24 * fit.percentageScale() && content + 1e-4 >= fit.iconSize(),
                                "Equipment icons and fitted percentages stay within their own cell: width=" + width
                                        + ", height=" + height + ", icon=" + icon + ", gap=" + gap
                                        + ", loaded=" + loaded + ", reserve=" + reserve + ", cell=" + content);
                        check(Math.abs(fit.cellCenter(slot) + fit.statusCenter(2 - slot) - fit.logicalWidth()) < 1e-4,
                                "The three status symbols exactly mirror the three equipment cells");
                        check(fit.statusCenter(slot) - ArmorHudLayout.STATUS_SIZE / 2f >= fit.logicalWidth() - fit.groupWidth() - 1e-4
                                        && fit.statusCenter(slot) + ArmorHudLayout.STATUS_SIZE / 2f <= fit.logicalWidth() + 1e-4,
                                "All three status symbols, including lungs, remain inside the right group");
                    }
                    check(fit.percentageTop() >= fit.iconSize()
                                    && fit.percentageTop() + ArmorHudLayout.TEXT_HEIGHT <= fit.logicalHeight(),
                            "Tier badges stay within the image and cannot cover the percentage baseline");
                    float loadedLeft = fit.loadedCenter() - ArmorHudLayout.loadedWidth(loaded) / 2f;
                    float loadedRight = fit.loadedCenter() + ArmorHudLayout.loadedWidth(loaded) / 2f;
                    check(fit.modeCenter() - ArmorHudLayout.MODE_SIZE / 2f >= fit.groupWidth() - 1e-4
                                    && fit.modeCenter() + ArmorHudLayout.MODE_SIZE / 2f <= loadedLeft
                                    && loadedRight <= fit.reserveCenter() - reserve / 2f
                                    && fit.reserveCenter() + reserve / 2f <= fit.logicalWidth() - fit.groupWidth() + 1e-4
                                    && fit.statusCenter(0) + ArmorHudLayout.STATUS_SIZE <= fit.statusCenter(1) + 1e-4
                                    && fit.statusCenter(1) + ArmorHudLayout.STATUS_SIZE <= fit.statusCenter(2) + 1e-4,
                            "All nine items stay disjoint even with long ammunition counts and custom settings");
                    float origin = (width - fit.logicalWidth() * fit.scale()) / 2;
                    check(Math.abs(origin + fit.loadedCenter() * fit.scale() - width / 2f) < 1e-4,
                            "Responsive fitting never displaces current ammunition from the frame center");
                }
        }
        System.out.println("Armor HUD layout checks: " + checks + " passed; no game launched.");
    }
    private static void oxygenReadout() {
        for (int[] sample : new int[][]{{300, 300, 100}, {150, 300, 50}, {-20, 300, 0}, {450, 300, 100}, {0, 0, 0}}) {
            long percent = Math.round(PubgVitalsLayout.fraction(sample[0], sample[1]) * 100);
            check(percent == sample[2], "Air supply reads as a clamped 0–100 percentage even while drowning or with zero capacity");
            for (int width : new int[]{80, 182, 420}) for (int height : new int[]{18, 24, 40}) {
                var fit = ArmorHudLayout.metrics(width, height, 18, 2, CombatHudNumbers.visibleWidth("100%"), 10, 10);
                float textHeight = CombatHudNumbers.DEFAULT_HEIGHT * fit.percentageScale();
                var ink = CombatHudNumbers.inkBounds(percent + "%", textHeight);
                check(ink.width() <= fit.cellContentWidth() + 1e-4
                                && fit.percentageTop() >= fit.iconSize()
                                && fit.percentageTop() + textHeight <= fit.logicalHeight(),
                        "Oxygen fits below the lungs on the equipment percentage baseline at every responsive size");
                check(Math.abs(fit.statusCenter(2) + fit.cellCenter(0) - fit.logicalWidth()) < 1e-4,
                        "Lungs and oxygen mirror the backpack cell");
            }
        }
    }
    private static void glyphCentering() throws Exception {
        String glyphs = "0123456789%\u221e.\u2014-:/";
        try (var stream = ArmorHudLayoutChecks.class.getResourceAsStream("/assets/tacz_pubg/textures/gui/hud/numbers/bebas_neue.png")) {
            check(stream != null, "Numeric atlas is packaged");
            var image = javax.imageio.ImageIO.read(stream);
            for (int i = 0; i < glyphs.length(); i++) {
                int left = 64, right = 0;
                for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++)
                    if ((image.getRGB(i % 8 * 64 + x, i / 8 * 64 + y) >>> 24) >= 32) {
                        left = Math.min(left, x); right = Math.max(right, x + 1);
                    }
                var bounds = CombatHudNumbers.inkBounds(glyphs.substring(i, i + 1), 48.5f);
                check(bounds.left() == left - 4 && bounds.right() == right - 4,
                        "Visible numeric metrics match actual atlas pixels for " + glyphs.charAt(i));
            }
        }
        for (String loaded : new String[]{"0", "1", "6", "19", "40", "100", "1000", "2147483647"})
            for (String reserve : new String[]{"0", "95", "9999", "\u221e"}) {
                var bounds = CombatHudNumbers.inkBounds(loaded, CombatHudNumbers.DEFAULT_HEIGHT * ArmorHudLayout.LOADED_SCALE);
                var fit = ArmorHudLayout.metrics(182, 24, 18, 2, CombatHudNumbers.visibleWidth("100%"),
                        CombatHudNumbers.visibleWidth(loaded), CombatHudNumbers.visibleWidth(reserve));
                float drawLeft = fit.loadedCenter() - bounds.center();
                float visibleCenter = (drawLeft + bounds.left() + drawLeft + bounds.right()) / 2;
                float screenCenter = (182 - fit.logicalWidth() * fit.scale()) / 2 + visibleCenter * fit.scale();
                check(Math.abs(screenCenter - 91) < 1e-4,
                        "The visible center is stable across single/multiple digits and finite/infinite reserve counts");
            }
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
