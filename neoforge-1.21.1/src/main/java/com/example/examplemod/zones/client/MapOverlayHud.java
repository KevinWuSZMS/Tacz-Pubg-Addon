package com.example.examplemod.zones.client;

import com.example.examplemod.combat.CombatPayloads;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import com.example.examplemod.vitals.client.PubgVitalsLayout;
import com.example.examplemod.zones.MapOverlayPayloads.Teammate;
import com.example.examplemod.zones.client.xaero.XaeroHudAnchor;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** PUBG's lower-left squad list; authoring information stays a small out-of-match preview. */
public final class MapOverlayHud {
    private MapOverlayHud() {}
    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "map_team_status"), (graphics, delta) -> render(graphics)));
    }
    private static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return;
        var map = MapOverlayClient.current();
        List<Teammate> team = MapOverlayClient.teammates();
        if (map != null && !MapOverlayClient.isMatchRunning()) {
            int width = Math.min(168, graphics.guiWidth() - 16);
            String name = Texts.tr("screen.tacz_pubg.map_overlay_hud.map", map.id(), map.name())
                    + Texts.tr("screen.tacz_pubg.map_overlay_hud.configuration_preview");
            String label = mc.font.plainSubstrByWidth(Texts.resolve(name), width);
            int x = graphics.guiWidth() - mc.font.width(label) - 8, y = 8;
            var anchor = XaeroHudAnchor.minimap();
            if (anchor != null && anchor.overlaps(x, y, graphics.guiWidth(), y + 12))
                y = Math.min(graphics.guiHeight() - 12, anchor.y() + anchor.height() + 5);
            try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "map_preview",
                    x, y, mc.font.width(label), 12)) {
                graphics.drawString(mc.font, label, x, y, 0xBFC7CDD4, true);
            }
        }
        if (MatchVitalsHud.active()) {
            renderMatchSquad(graphics, team);
            return;
        }
        // The ordinary world/scoreboard preview retains its previous layout outside a match.
        int shown = Math.min(team.size(), Math.min(8, Math.max(1, (graphics.guiHeight() - 70) / 21)));
        if (shown == 0) return;
        int width = Math.min(136, Math.max(64, graphics.guiWidth() / 3));
        int left = 9, top = Math.max(35, graphics.guiHeight() - 37 - shown * 21);
        var anchor = XaeroHudAnchor.minimap();
        if (anchor != null && anchor.overlaps(left, top, left + width, top + shown * 21)) {
            if (anchor.x() + anchor.width() + width + 20 < graphics.guiWidth() / 2)
                left = anchor.x() + anchor.width() + 9;
            else top = Math.max(35, anchor.y() - shown * 21 - 6);
        }
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "squad",
                left - 3, top - 2, width + 5, shown * 21 + (team.size() > shown ? 12 : 0))) {
        for (int i = 0; i < shown; i++) {
            Teammate teammate = team.get(i);
            int row = top + i * 21, color = 0xFF000000 | (teammate.color() & 0xFFFFFF);
            graphics.fill(left - 3, row - 2, left + width + 2, row + 17, 0x60101418);
            graphics.fill(left, row, left + 11, row + 11, teammate.alive() ? color : 0xFF626972);
            String number = Integer.toString(teammate.slot());
            graphics.drawString(mc.font, number, left + (11 - mc.font.width(number)) / 2, row + 1, 0xFFFFFFFF, true);
            String name = (teammate.alive() ? "" : "× ") + teammate.name();
            graphics.drawString(mc.font, mc.font.plainSubstrByWidth(name, width - 16), left + 15, row + 1,
                    teammate.alive() ? 0xFFF4F4F4 : 0xFF9CA2AA, true);
            int healthWidth = width - 15;
            double health = teammate.alive() ? Math.clamp(teammate.health() / Math.max(1.0, teammate.maxHealth()), 0, 1) : 0;
            graphics.fill(left + 15, row + 12, left + width, row + 15, 0xFF444950);
            if (health > 0) graphics.fill(left + 15, row + 12, left + 15 + (int) Math.round(healthWidth * health), row + 15,
                    health <= .25 ? 0xFFE98B81 : 0xFFE6E7E8);
        }
        if (team.size() > shown) graphics.drawString(mc.font,
                Texts.resolve(Texts.tr("screen.tacz_pubg.map_overlay_hud.more_teammates", team.size() - shown)),
                left, top + shown * 21, 0xFFC7CDD4, true);
        }
    }

    private static void renderMatchSquad(GuiGraphics graphics, List<Teammate> team) {
        Minecraft mc = Minecraft.getInstance();
        var combat = CombatPayloads.latest();
        boolean currentCombat = MatchVitalsHud.activeSession(combat.session());
        int shown = Math.min(team.size(), Math.min(8, Math.max(1, (graphics.guiHeight() - 85) / 21)));
        if (shown == 0) return;
        int width = Math.min(148, Math.max(72, graphics.guiWidth() / 3));
        int left = 10, top = Math.max(35, graphics.guiHeight() - 50 - shown * 21);
        var anchor = XaeroHudAnchor.minimap();
        if (anchor != null && anchor.overlaps(left - 7, top, left + width, top + shown * 21)) {
            if (anchor.x() + anchor.width() + width + 20 < graphics.guiWidth() / 2)
                left = anchor.x() + anchor.width() + 10;
            else top = Math.max(35, anchor.y() - shown * 21 - 6);
        }
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "squad",
                left - 6, top - 2, width + 6, shown * 21 + (team.size() > shown ? 12 : 0))) {
        for (int i = 0; i < shown; i++) {
            Teammate mate = team.get(i);
            int row = top + i * 21;
            boolean alive = mate.alive(), online = mate.online();
            boolean self = mc.player != null && mate.id().equals(mc.player.getUUID());
            int color = alive ? 0xFF000000 | (mate.color() & 0xFFFFFF) : 0xFF666A6C;
            int textColor = alive && online ? 0xFFF6F6F4 : 0xFFA0A6AB;
            // A narrow neutral backing under the text and bar, without a full rectangular panel.
            graphics.fill(left - 1, row - 1, left + width, row + 11, 0x35101518);
            graphics.fill(left, row, left + 11, row + 11, color);
            String slot = Integer.toString(mate.slot());
            graphics.drawString(mc.font, slot, left + (11 - mc.font.width(slot)) / 2, row + 1, 0xFFFFFFFF, true);
            String state = !alive ? Texts.resolve(Texts.tr("screen.tacz_pubg.pubg_hud.eliminated"))
                    : !online ? Texts.resolve(Texts.tr("screen.tacz_pubg.pubg_hud.offline")) : "";
            int stateWidth = state.isEmpty() ? 0 : mc.font.width(state) + 5;
            String name = mc.font.plainSubstrByWidth(mate.name(), Math.max(8, width - 16 - stateWidth));
            graphics.drawString(mc.font, name, left + 15, row + 1, textColor, true);
            if (!state.isEmpty()) graphics.drawString(mc.font, state, left + width - mc.font.width(state),
                    row + 1, textColor, true);
            if (self) {
                graphics.fill(left - 6, row + 3, left - 5, row + 8, 0xFFF4F4F4);
                graphics.fill(left - 5, row + 4, left - 3, row + 7, 0xFFF4F4F4);
                graphics.fill(left - 3, row + 5, left - 2, row + 6, 0xFFF4F4F4);
            }
            graphics.fill(left, row + 13, left + width, row + 16, 0xB22A3035);
            if (!alive) {
                graphics.fill(left, row + 13, left + width, row + 14, 0xFF656B70);
            } else if (!online) {
                // No health is sent for a disconnected player; dashes communicate unknown health.
                for (int at = 0; at < width; at += 7)
                    graphics.fill(left + at, row + 14, left + Math.min(width, at + 3), row + 15, 0xFF78828C);
            } else {
                var downed = currentCombat ? combat.players().stream()
                        .filter(player -> player.id().equals(mate.id()) && player.downed() && player.maxHealth() > 0)
                        .findFirst().orElse(null) : null;
                double health = downed == null ? PubgVitalsLayout.fraction(mate.health(), mate.maxHealth())
                        : PubgVitalsLayout.fraction(downed.health(), downed.maxHealth());
                // Squad bars share the downed pool and red fill, without the personal bar's hatch.
                graphics.fill(left, row + 13, left + (int) Math.round(width * health), row + 16,
                        downed != null ? PubgVitalsLayout.DOWNED_HEALTH : health <= .25 ? 0xFFE88479 : 0xFFE6E7E5);
            }
        }
        if (team.size() > shown) {
            String more = Texts.resolve(Texts.tr("screen.tacz_pubg.map_overlay_hud.more_teammates", team.size() - shown));
            graphics.drawString(mc.font, more, left, top + shown * 21, 0xFFC7CDD4, true);
        }
        }
    }
}
