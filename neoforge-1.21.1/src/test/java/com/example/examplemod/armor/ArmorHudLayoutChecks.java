package com.example.examplemod.armor;

import com.example.examplemod.armor.client.ArmorHudLayout;
import com.example.examplemod.armor.client.PlayerStanceHud;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.pubgsettings.PubgSettings;
import com.example.examplemod.vitals.client.CombatHudStyle;
import com.example.examplemod.vitals.client.CombatHudNumbers;
import com.example.examplemod.vitals.client.CombatHudTextures;
import com.example.examplemod.vitals.client.PubgVitalsLayout;
import net.minecraft.world.entity.Pose;

public final class ArmorHudLayoutChecks {
    private static int checks;
    private static void stanceIndicator() throws Exception {
        check(PlayerStanceHud.stance(Pose.STANDING, false) == PlayerStanceHud.Stance.STANDING, "Standing body selects the upright silhouette");
        check(PlayerStanceHud.stance(Pose.CROUCHING, false) == PlayerStanceHud.Stance.CROUCHING, "Collision-constrained and rescue crouch select the crouching silhouette");
        check(PlayerStanceHud.stance(Pose.SWIMMING, false) == PlayerStanceHud.Stance.PRONE, "Ground crawl pose selects prone, including TaCZ and unarmed movement");
        check(PlayerStanceHud.stance(Pose.SWIMMING, true) == null, "Actual swimming is not labeled as ground prone");
        for (var pose : new Pose[]{Pose.SLEEPING, Pose.DYING, Pose.FALL_FLYING, Pose.SPIN_ATTACK})
            check(PlayerStanceHud.stance(pose, false) == null, "Unsupported body pose is not mislabeled as standing");
        var panel = new HudLayoutModel.Rect(250, 300, 182, 24);
        var indicator = PlayerStanceHud.bounds(640, 360, panel);
        var moved = PlayerStanceHud.bounds(640, 360, new HudLayoutModel.Rect(287, 274, 182, 24));
        check(indicator.x() + indicator.width() < panel.x(), "Posture remains entirely left of the status panel");
        check(moved.x() - indicator.x() == 37 && moved.y() - indicator.y() == -26,
                "Default posture anchor follows exact equipment-panel displacement");
        check(panel.width() == 182 && indicator.width() == indicator.height(), "Posture has its own square canvas without expanding the status row");
        for (var edge : new HudLayoutModel.Rect[]{new HudLayoutModel.Rect(0, 280, 182, 24), new HudLayoutModel.Rect(0, 0, 182, 24)}) {
            var placed = PlayerStanceHud.bounds(320, 360, edge);
            check(placed.x() >= 0 && placed.y() >= 0 && placed.x() + placed.width() <= 320 && placed.y() + placed.height() <= 360,
                    "Edge fallback keeps the entire indicator on screen");
            check(placed.y() + placed.height() <= edge.y() || placed.y() >= edge.y() + edge.height(),
                    "An edge-aligned panel does not cover its own status cells with the posture indicator");
        }
        for (var stance : PlayerStanceHud.Stance.values()) {
            try (var input = ArmorHudLayoutChecks.class.getResourceAsStream("/assets/tacz_pubg/" + stance.texture().getPath())) {
                check(input != null, "Every stance has a bundled image");
                var image = javax.imageio.ImageIO.read(input);
                check(image != null && image.getWidth() == image.getHeight() && image.getColorModel().hasAlpha(),
                        "Stance images use square transparent canvases");
                check((image.getRGB(0, 0) >>> 24) == 0 && (image.getRGB(image.getWidth()-1, image.getHeight()-1) >>> 24) == 0,
                        "Stance corners contain no game-background rectangle");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        glyphCentering();
        oxygenReadout();
        stanceIndicator();
        brokenEquipment();
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
    private static void brokenEquipment() throws Exception {
        var absent = ArmorHudLayout.durability(false, 0, 0);
        check(!absent.brokenVest() && absent.label().equals("—"), "An empty chest slot is distinct from a retained broken vest");
        check(CombatHudTextures.armor(0, true).equals(CombatHudTextures.armor(0)), "An empty slot never selects a missing broken-tier-zero texture");
        var imageHashes = new java.util.HashSet<Integer>();
        for (int tier = 1; tier <= 3; tier++) {
            var broken = ArmorHudLayout.durability(false, tier, 0);
            check(broken.brokenVest() && broken.label().equals("0%") && broken.color() == 0xFFDA0010,
                    "All exhausted vest tiers show the screenshot red zero percent");
            var surviving = ArmorHudLayout.durability(false, tier, 1.0 / (ArmorRules.maximum(false, tier) * ArmorRules.PRECISION));
            check(!surviving.brokenVest() && surviving.label().equals("1%"), "The final fractional durability point cannot falsely show broken zero percent");
            check(!ArmorHudLayout.durability(true, tier, 0).brokenVest(), "An exhausted helmet cannot use a broken vest picture");
            check(ArmorHudLayout.durability(false, tier, 1).label().equals("100%"), "Replacement armor restores its intact readout");
            var texture = CombatHudTextures.armor(tier, broken.brokenVest());
            check(texture.getPath().endsWith("armor_broken_" + tier + ".png")
                            && !texture.equals(CombatHudTextures.armor(tier, surviving.brokenVest())),
                    "User screenshot order maps to separate level one, two and three broken icons");
            try (var input = ArmorHudLayoutChecks.class.getResourceAsStream("/assets/tacz_pubg/" + texture.getPath())) {
                check(input != null, "Each broken vest image is packaged");
                var image = javax.imageio.ImageIO.read(input);
                check(image != null && image.getWidth() == image.getHeight() && image.getColorModel().hasAlpha(),
                        "Broken vest images use square transparent canvases");
                check((image.getRGB(0, 0) >>> 24) == 0 && (image.getRGB(image.getWidth()-1, image.getHeight()-1) >>> 24) == 0,
                        "Broken vest corners have no screenshot background");
                int opaque = 0, red = 0, hash = 1;
                boolean fitsCrop = true;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y);
                    hash = 31 * hash + pixel;
                    if ((pixel >>> 24) < 128) continue;
                    opaque++;
                    if ((pixel >> 16 & 255) > (pixel >> 8 & 255) * 2 && (pixel >> 16 & 255) > (pixel & 255) * 2) red++;
                    fitsCrop &= x >= image.getWidth() * 12 / 128 && x < image.getWidth() * 116 / 128
                            && y >= image.getHeight() * 12 / 128 && y < image.getHeight() * 116 / 128;
                }
                check(opaque > 0 && red >= opaque * .95, "Broken silhouettes visibly use red rather than the intact white mask");
                check(fitsCrop, "UV margin fitting preserves every solid part of the broken silhouette");
                check(imageHashes.add(hash), "Each vest tier packages a distinct reference silhouette");
            }
        }
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
