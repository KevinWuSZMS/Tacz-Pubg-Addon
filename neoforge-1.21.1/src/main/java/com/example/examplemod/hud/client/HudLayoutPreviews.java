package com.example.examplemod.hud.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.lootspawn.client.GroundLootClient;
import com.example.examplemod.vitals.client.PubgVitalsLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Representative inactive-state samples; renderers may replace any sample through registerPreview. */
final class HudLayoutPreviews {
    private HudLayoutPreviews() {}

    static HudLayoutModel.Rect defaults(String id, int w, int h) {
        int bar = PubgVitalsLayout.width(w);
        int center = Math.min(h - 106, h / 2 + 35);
        return switch (id) {
            case "vitals" -> rect((w - bar) / 2 - 1, h - 45, bar + 2, 19);
            case "equipment" -> rect((w - bar) / 2, h - 67, bar, 22);
            case "medicine_progress" -> centeredRect(w, center - 12,
                    Math.max(Math.min(150, w - 24), textWidth(medicineLabel())), 16);
            case "throwable_status" -> centeredRect(w, Math.min(h - 105, h / 2 + 35) - 22,
                    Math.max(textWidth(translated("screen.tacz_pubg.throwable.fuse", "3.5")), textWidth(throwableControls())), 45);
            case "throwable_wheel", "medicine_wheel" -> {
                double ring = Math.min(82, Math.min(w, h) * .3);
                int width = (int) Math.ceil(ring * 2 + 60), height = (int) Math.ceil(ring * 2 + 85);
                yield rect(w / 2 - width / 2, h / 2 - height / 2, width, height);
            }
            case "match_phase" -> HudLayoutClient.mapHeader(w, h);
            case "alive_counter" -> {
                int width = (int) Math.ceil(textWidth("64") * 1.6)
                        + textWidth(translated("screen.tacz_pubg.pubg_hud.alive_label")) + 14;
                yield rect(w - width - 9, 9, width, 33);
            }
            case "compass" -> centeredRect(w, 9, Math.min(300, Math.max(120, w - 190)), 34);
            case "match_alert" -> centeredRect(w, Math.max(53, h / 3),
                    Math.min(Math.max(20, w - 30), textWidth(translated("screen.tacz_pubg.pubg_hud.shrinking"))), 12);
            case "match_result" -> centeredRect(w, Math.max(53, h / 3) - 23, Math.min(w - 20, 230), 38);
            case "squad" -> rect(4, Math.max(35, h - 50 - squadRows(h) * 21) - 2,
                    Math.min(148, Math.max(72, w / 3)) + 6, squadRows(h) * 21);
            case "map_preview" -> {
                int width = Math.min(Math.min(168, w - 16), textWidth(mapLabel()));
                yield rect(w - width - 8, 8, width, 12);
            }
            case "spectator" -> rect(10, h - 83, w - 20, 22);
            case "flight" -> {
                int half = Math.max(30, (int) (h * .15)), gap = Math.max(64, (int) (w * .238));
                yield rect(w / 2 - gap - 65, h / 2 + (int) (h * .01) - half - 15, gap * 2 + 130, half * 2 + 52);
            }
            case "loot_pickup" -> centeredRect(w, h / 2 + 18, textWidth(pickupLabel()), Minecraft.getInstance().font.lineHeight);
            case "area_selection" -> {
                int width = 0;
                for (String line : selectionLines()) width = Math.max(width, textWidth(line));
                yield rect(6, 6, width + 8, 6 + 7 * 11);
            }
            case "xaero_minimap" -> XaeroHudBridge.bounds(w, h);
            default -> rect(8, 8, 160, 24);
        };
    }
    private static HudLayoutModel.Rect rect(int x, int y, int width, int height) {
        return new HudLayoutModel.Rect(x, y, width, height);
    }
    private static HudLayoutModel.Rect centeredRect(int screenWidth, int y, int width, int height) {
        return rect((screenWidth - width) / 2, y, width, height);
    }
    private static int squadRows(int height) { return Math.min(4, Math.max(1, (height - 85) / 21)); }
    static void draw(GuiGraphics g, String id, HudLayoutModel.Rect r, boolean selected) {
        int x = r.x(), y = r.y(), w = r.width(), h = r.height();
        int text = 0xFFF2F2E9;
        switch (id) {
            case "vitals" -> {
                g.fill(x, y, x + w, y + 4, 0xB0242529);
                g.fill(x + 1, y + 1, x + w * 3 / 4, y + 3, 0xFFF3BD49);
                g.fill(x, y + 7, x + w, y + h, 0xC031353B);
                g.fill(x + 1, y + 8, x + w * 4 / 5, y + h - 1, 0xFFEDECE4);
            }
            case "equipment" -> {
                String[] icons = {"helmet_2", "vest_2", "backpack_2"};
                for (int i = 0; i < 3; i++) {
                    icon(g, icons[i], x + i * w / 3, y);
                    string(g, "75%", x + i * w / 3 + 19, y + 5, w / 3 - 18, text);
                }
            }
            case "medicine_progress" -> {
                string(g, medicineLabel(), x, y, w, text);
                g.fill(x, y + h - 5, x + w, y + h - 1, 0xB02A3035);
                g.fill(x, y + h - 5, x + w / 2, y + h - 1, 0xFFF2CC72);
            }
            case "throwable_status" -> {
                icon(g, "frag_grenade", x + w / 2 - 8, y);
                centered(g, translated("screen.tacz_pubg.throwable.fuse", "3.5"), r, y + 22, 0xFFFFAB68);
                centered(g, throwableControls(), r, y + 35, 0xFFBFC6CB);
            }
            case "throwable_wheel", "medicine_wheel" -> wheel(g, r, id.equals("medicine_wheel"));
            case "match_phase" -> {
                string(g, "◷  01:30     " + translated("screen.tacz_pubg.pubg_hud.phase", 2), x + 2, y + 2, w - 4, text);
                g.fill(x, y + 15, x + w, y + 18, 0xCC4E769A);
                g.fill(x, y + 15, x + w / 2, y + 18, 0xFFE5E6E5);
            }
            case "alive_counter" -> {
                int numberWidth = (int) Math.ceil(textWidth("64") * 1.6);
                g.fill(x, y, x + numberWidth + 6, y + 19, 0x80161B20);
                g.fill(x + numberWidth + 8, y, x + w, y + 19, 0x80161B20);
                g.pose().pushPose();
                g.pose().translate(x + 3, y + 2, 0);
                g.pose().scale(1.6F, 1.6F, 1);
                string(g, "64", 0, 0, numberWidth, text);
                g.pose().popPose();
                string(g, translated("screen.tacz_pubg.pubg_hud.alive_label"),
                        x + numberWidth + 10, y + 6, w - numberWidth - 10, text);
            }
            case "compass" -> {
                centered(g, "NW     330     N     30     NE", r, y, text);
                for (int i = 0; i < w; i += 14) g.fill(x + i, y + 15, x + i + 1, y + (i % 28 == 0 ? 24 : 20), 0xFFDADEE2);
            }
            case "squad" -> {
                String player = Minecraft.getInstance().player == null ? HudLayoutClient.definition(id).label().getString()
                        : Minecraft.getInstance().player.getName().getString();
                for (int i = 0; i < Math.min(4, h / 21); i++) {
                    int row = y + i * 21, color = new int[]{0xFFF1CF5A, 0xFF5BBAC5, 0xFFB297DD, 0xFFB0CE65}[i];
                    g.fill(x, row, x + 3, row + 17, color);
                    string(g, (i + 1) + "  " + player, x + 7, row, w - 7, text);
                    g.fill(x + 7, row + 12, x + w, row + 15, 0xFF41494F);
                    g.fill(x + 7, row + 12, x + w * (8 - i) / 9, row + 15, color);
                }
            }
            case "flight" -> {
                int left = x + 65, right = x + w - 65, axisTop = y + 15, axisHeight = h - 52;
                g.fill(left, axisTop, left + 1, axisTop + axisHeight, text);
                g.fill(right, axisTop, right + 1, axisTop + axisHeight, text);
                for (int i = 0; i < 8; i++) {
                    int row = axisTop + i * axisHeight / 8;
                    g.fill(left, row, left + 7, row + 1, text);
                    g.fill(right - 7, row, right, row + 1, text);
                }
                string(g, "850 M", x, axisTop + axisHeight / 2, 58, text);
                string(g, "180 KM/H", right + 9, axisTop + axisHeight / 2, 56, text);
                centered(g, translated("message.tacz_pubg.flight.deploy", binding("flight_jump")), r, y + h - 12, text);
            }
            case "xaero_minimap" -> {
                g.fill(x, y, x + w, y + h, 0xEE304137);
                for (int at = 8; at < w; at += 16) g.fill(x + at, y, x + at + 1, y + h, 0x50587562);
                for (int at = 8; at < h; at += 16) g.fill(x, y + at, x + w, y + at + 1, 0x50587562);
                centered(g, "N", r, y + 4, text);
                centered(g, "▲", r, y + h / 2 - 4, 0xFFF4E899);
                centered(g, "Xaero", r, y + h - 14, text);
            }
            case "loot_pickup" -> string(g, pickupLabel(), x, y, w, text);
            case "area_selection" -> {
                g.fill(x, y, x + w, y + h, 0xA0000000);
                String[] lines = selectionLines();
                for (int i = 0; i < lines.length; i++)
                    string(g, lines[i], x + 4, y + 4 + i * 11, w - 8, i == 3 ? 0xFF64E9FF : text);
            }
            case "map_preview" -> string(g, mapLabel(), x, y, w, 0xBFC7CDD4);
            case "match_alert" -> string(g, translated("screen.tacz_pubg.pubg_hud.shrinking"), x, y, w, 0xFFF4CE53);
            default -> {
                g.fill(x, y, x + w, y + h, selected ? 0x90313C49 : 0x50232830);
                String label = HudLayoutClient.definition(id).label().getString();
                centered(g, label, r, y + Math.max(2, (h - 9) / 2), id.equals("match_result") ? 0xFFE9C059 : text);
            }
        }
    }
    private static void wheel(GuiGraphics g, HudLayoutModel.Rect r, boolean medical) {
        String[] items = medical ? new String[]{"med_kit", "first_aid_kit", "bandage", "energy_drink", "painkiller", "adrenaline_syringe"}
                : new String[]{"frag_grenade", "stun_grenade", "smoke_grenade", "molotov", "sticky_bomb", "c4", "blue_zone_grenade"};
        double radius = Math.max(0, (r.width() - 60) * .5);
        for (int i = 0; i < items.length; i++) {
            double angle = i * Math.PI * 2 / items.length - Math.PI / 2;
            int x = r.x() + r.width() / 2 + (int) (Math.cos(angle) * radius);
            int y = r.y() + r.height() / 2 + (int) (Math.sin(angle) * radius);
            g.fill(x - 25, y - 25, x + 25, y + 25, i == 0 ? 0xD0B68B38 : 0xB02A3038);
            icon(g, items[i], x - 8, y - 13);
            g.drawCenteredString(Minecraft.getInstance().font, "1", x, y + 8, 0xFFFFFFFF);
        }
        centered(g, binding(medical ? "medicine_wheel" : "throwable_wheel").getString(),
                r, r.y() + r.height() / 2 - 4, 0xFFE6E4D9);
        centered(g, translated(medical ? "screen.tacz_pubg.throwable.medicine_wheel" : "screen.tacz_pubg.throwable.throwable_wheel"),
                r, r.y() + 4, 0xFFFFE4A0);
    }
    private static int textWidth(String text) { return Minecraft.getInstance().font.width(text); }
    private static String translated(String key, Object... args) { return Texts.of(key, args).getString(); }
    private static Component binding(String id) {
        String name = "key.tacz_pubg." + id;
        for (var key : Minecraft.getInstance().options.keyMappings)
            if (key.getName().equals(name)) return key.getTranslatedKeyMessage();
        return Component.translatable(name);
    }
    private static ItemStack sampleItem(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("tacz_pubg", id)));
    }
    private static String medicineLabel() {
        return translated("screen.tacz_pubg.medicine.use_progress", sampleItem("first_aid_kit").getHoverName(), "3.2");
    }
    private static String throwableControls() {
        return translated("screen.tacz_pubg.throwable.controls", binding("cook"), binding("cancel_use"));
    }
    private static String pickupLabel() {
        return translated("gui.tacz_pubg.pickup_hint", GroundLootClient.pickupKey().getTranslatedKeyMessage(),
                sampleItem("first_aid_kit").getHoverName(), 1);
    }
    private static String mapLabel() {
        return translated("screen.tacz_pubg.map_overlay_hud.map", 1,
                HudLayoutClient.definition("map_preview").label())
                + translated("screen.tacz_pubg.map_overlay_hud.configuration_preview");
    }
    private static String[] selectionLines() {
        return new String[]{
                translated("screen.tacz_pubg.spawn_area_client.loot_floor_tool_left_click_pos_right_click"),
                "pos1: 0, 64, 0    pos2: 15, 64, 15",
                translated("screen.tacz_pubg.spawn_area_client.selection_blocks_same_y_maximum", 256),
                translated("screen.tacz_pubg.spawn_area_client.current_tier_light_preview_dark_confirmed_white_lines", "2"),
                translated("screen.tacz_pubg.spawn_area_client.air_clear_selection_save", binding("area_clear"), binding("area_save")),
                translated("screen.tacz_pubg.spawn_area_client.air_erase_confirmed_blocks_selection_area_add_remove", binding("area_erase")),
                translated("screen.tacz_pubg.spawn_area_client.nearby_confirmed_blocks", 256, "")
        };
    }
    private static void icon(GuiGraphics g, String id, int x, int y) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("tacz_pubg", id));
        if (item != null) g.renderItem(new ItemStack(item), x, y);
    }
    private static void string(GuiGraphics g, String text, int x, int y, int width, int color) {
        var font = Minecraft.getInstance().font;
        g.drawString(font, font.plainSubstrByWidth(text, Math.max(0, width)), x, y, color);
    }
    private static void centered(GuiGraphics g, String text, HudLayoutModel.Rect r, int y, int color) {
        var font = Minecraft.getInstance().font;
        text = font.plainSubstrByWidth(text, r.width());
        g.drawString(font, text, r.x() + (r.width() - font.width(text)) / 2, y, color);
    }
}
