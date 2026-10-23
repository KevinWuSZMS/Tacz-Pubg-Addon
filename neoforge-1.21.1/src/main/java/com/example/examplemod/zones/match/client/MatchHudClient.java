package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.client.MapOverlayClient;
import com.example.examplemod.zones.client.xaero.XaeroHudAnchor;
import com.example.examplemod.zones.match.MatchConfigPayloads;
import com.example.examplemod.zones.match.MatchStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.UUID;

/** PC-style match presentation driven by real match, circle and roster snapshots. */
public final class MatchHudClient {
    private static MatchStatus current;
    private static UUID playedSession;
    private static int age, alertTicks, resultTicks;
    private static String alert = "";
    private MatchHudClient() {}
    public static MatchStatus currentStatus() {
        var frame = MapOverlayClient.frameFor(MapOverlayClient.current());
        return frame == null ? current : frame.status();
    }

    public static void register(IEventBus modBus) {
        MatchConfigPayloads.setHudHandler(MatchHudClient::accept);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (Minecraft.getInstance().isPaused()) return;
            if (++age > 80) current = null;
            if (alertTicks > 0) alertTicks--;
            if (resultTicks > 0) resultTicks--;
        });
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "match_status"), (graphics, delta) -> render(graphics)));
    }

    private static void clear() {
        current = null; playedSession = null;
        age = alertTicks = resultTicks = 0; alert = "";
    }

    private static void accept(MatchStatus next) {
        if (next == null) { current = null; age = alertTicks = resultTicks = 0; return; }
        MatchStatus previous = current;
        boolean same = previous != null && previous.sessionId().equals(next.sessionId()) && previous.mapId() == next.mapId();
        if (!same) { alertTicks = resultTicks = 0; alert = ""; }
        if (same && (!previous.stage().equals(next.stage()) || previous.phase() != next.phase())) {
            if (next.stage().equals("shrinking")) announce(Texts.tr("screen.tacz_pubg.pubg_hud.restricting"));
            else if (next.stage().equals("waiting")) announce(Texts.tr("screen.tacz_pubg.pubg_hud.new_safe_zone"));
        } else if (same && next.stage().equals("waiting")) {
            for (int warning : new int[] {60, 30, 10}) {
                if (previous.secondsRemaining() > warning && next.secondsRemaining() <= warning) {
                    announce(Texts.tr("screen.tacz_pubg.pubg_hud.restrict_in", warning)); break;
                }
            }
        }
        if (same && !previous.bombingStage().equals(next.bombingStage()) && next.bombingStage().equals("warning"))
            announce(Texts.tr("screen.tacz_pubg.pubg_hud.red_zone_warning"));
        if (same && !ended(previous) && ended(next) && next.sessionId().equals(playedSession)) resultTicks = 160;
        current = next; age = 0;
    }

    private static boolean ended(MatchStatus status) {
        return status.stage().equals("finished") || status.stage().equals("stopped");
    }
    private static void announce(String text) { alert = text; alertTicks = 70; }
    public static String clock(int seconds) {
        int safe = Math.max(0, seconds);
        return safe / 60 + ":" + (safe % 60 < 10 ? "0" : "") + safe % 60;
    }

    private static String stage(MatchStatus status) {
        return switch (status.stage()) {
            case "preparing" -> Texts.tr("screen.tacz_pubg.pubg_hud.preparing");
            case "no_circle" -> Texts.tr(status.flightProgress() >= 0
                    ? "screen.tacz_pubg.pubg_hud.flight" : "screen.tacz_pubg.pubg_hud.first_circle");
            case "waiting" -> Texts.tr("screen.tacz_pubg.pubg_hud.waiting");
            case "shrinking" -> Texts.tr("screen.tacz_pubg.pubg_hud.shrinking");
            case "final" -> Texts.tr(status.phase() == 0
                    ? "screen.tacz_pubg.match_config.blue_zone_off_combat" : "screen.tacz_pubg.pubg_hud.final");
            case "paused" -> Texts.tr("screen.tacz_pubg.match_config.paused");
            case "finished" -> Texts.tr("screen.tacz_pubg.match_config.settled");
            case "stopped" -> Texts.tr("screen.tacz_pubg.match_hud_client.aborted");
            default -> status.stage();
        };
    }

    private static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return;
        var map = MapOverlayClient.current();
        var frame = MapOverlayClient.frameFor(map);
        MatchStatus status = frame == null ? current : frame.status();
        if (status == null) return;
        if (map == null || map.id() != status.mapId() || !map.dimension().equals(mc.level.dimension().location())) return;
        boolean personal = MatchVitalsHud.activeSession(status.sessionId());
        if (personal) playedSession = status.sessionId();
        if (!ended(status)) {
            if (personal) {
                renderPhase(graphics, status, frame);
                if (!com.example.examplemod.combat.client.CombatHudClient.hasStats(status.sessionId())) renderAlive(graphics, status);
                renderCompass(graphics);
                if (alertTicks > 0) centerMessage(graphics, alert, false);
            }
        } else if (resultTicks > 0 && status.sessionId().equals(playedSession)) {
            centerMessage(graphics, status.result(), true);
        }
    }

    private static void renderPhase(GuiGraphics graphics, MatchStatus status, MapOverlayClient.CircleFrame frame) {
        Minecraft mc = Minecraft.getInstance();
        var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
        var header = com.example.examplemod.hud.client.HudLayoutClient.mapHeader(graphics.guiWidth(), graphics.guiHeight());
        int x = header.x(), y = header.y(), width = header.width(), height = header.height();
        boolean moving = status.effectiveStage().equals("shrinking");
        int color = status.paused() ? 0xFFADB4BA : 0xFFF4F4F4;
        // One unobtrusive line, as in the PC minimap header; no opaque status card.
        graphics.fill(x, y, x + width, y + height,
                (int) Math.round(settings.number("map.headerBackgroundOpacity") * 255) << 24 | 0x101418);
        warningGlyph(graphics, x, y, moving && !status.paused() ? settings.color("map.warningColor") : 0xFF8DA6BF);
        long remaining = frame == null ? status.remainingTicks() : frame.remainingTicks();
        String timer = remaining >= 1200 ? clock((int) ((remaining + 19) / 20)) : Long.toString((remaining + 19) / 20);
        graphics.drawString(mc.font, timer, x + 11, y, color, true);
        String phase = status.phase() > 0
                ? Texts.resolve(Texts.tr("screen.tacz_pubg.pubg_hud.phase", status.phase())) : Texts.resolve(stage(status));
        if (status.paused() && status.phase() > 0) phase += " · " + Texts.resolve(stage(status));
        int room = width - 16 - mc.font.width(timer);
        phase = mc.font.plainSubstrByWidth(phase, Math.max(0, room));
        graphics.drawString(mc.font, phase, x + width - mc.font.width(phase), y, 0xFFF4F4F4, true);
        renderZoneGauge(graphics, status, frame, x, y + height - settings.integer("map.gaugeHeight"), width,
                x + 16 + mc.font.width(timer), x + width - mc.font.width(phase) - 5);
        if (status.bombingStage().equals("warning") || status.bombingStage().equals("striking")) {
            String red = Texts.resolve(Texts.tr("screen.tacz_pubg.pubg_hud.red_zone", clock(status.bombingSeconds())));
            // Place a warning above the header, never over the minimap itself.
            graphics.drawString(mc.font, mc.font.plainSubstrByWidth(red, width), x,
                    y - 12, 0xFFFF7777, true);
        }
    }

    private static void renderZoneGauge(GuiGraphics graphics, MatchStatus status, MapOverlayClient.CircleFrame frame,
            int x, int y, int width, int textLeft, int textRight) {
        var player = Minecraft.getInstance().player;
        var target = status.targetCircle();
        var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
        int height = settings.integer("map.gaugeHeight");
        graphics.fill(x, y, x + width, y + height, 0xB32B343D);
        var start = status.phaseStartCircle();
        if (target == null || start == null || player == null || status.phase() == 0) return;
        Circle boundary = frame == null ? status.currentCircle() : frame.current();
        var position = player.getPosition(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        var gauge = com.example.examplemod.zones.match.ZoneGaugeGeometry.sample(ring(start), boundary == null ? null : ring(boundary),
                ring(target), position.x, position.z);
        int blueWidth = Math.clamp((int) Math.round(width * gauge.blue()), 0, width);
        graphics.fill(x, y, x + blueWidth, y + height, settings.color("map.blueColor"));
        if (boundary != null) {
            int blueAt = x + Math.clamp(blueWidth, 0, width - 2);
            graphics.fill(blueAt, y - 1, blueAt + 2, y + height + 1, settings.color("map.blueColor"));
        }
        if (status.effectiveStage().equals("shrinking")) {
            int spacing = settings.integer("map.arrowSpacing");
            double ticks = frame == null ? status.matchTicks() : frame.animationTicks();
            int offset = (int) com.example.examplemod.zones.match.ZoneGaugeGeometry.conveyorOffset(ticks,
                    settings.number("map.arrowSpeed"), spacing);
            int tintArrow = ((int) Math.round(settings.number("map.arrowOpacity") * 255) << 24)
                    | settings.color("map.arrowColor") & 0xFFFFFF;
            for (int step = offset - spacing; step < width; step += spacing) {
                for (int row = 0; row < height; row++) {
                    int atArrow = x + step + (height / 2 - Math.abs(row - height / 2));
                    if (atArrow >= x && atArrow < x + width - 2) graphics.fill(atArrow, y + row, atArrow + 1, y + row + 1, tintArrow);
                }
            }
        }
        graphics.fill(x + width - 2, y - 1, x + width, y + height + 1, settings.color("map.safeColor"));
        int at = x + Math.clamp((int) Math.round(gauge.player() * (width - 2)), 2, width - 3);
        int tint = gauge.outsideBlue() ? settings.color("map.dangerColor") : settings.color("map.playerColor");
        graphics.fill(at - 2, y - 4, at + 3, y - 3, tint);
        graphics.fill(at - 1, y - 3, at + 2, y - 2, tint);
        graphics.fill(at, y - 2, at + 1, y + 2, tint);
        if (settings.enabled("map.showSafeDistance") && !gauge.insideSafe() && !status.paused()) {
            String text = (int) Math.ceil(gauge.safeDistance()) + "m";
            var font = Minecraft.getInstance().font;
            if (font.width(text) <= textRight - textLeft)
                graphics.drawString(font, text, textLeft + (textRight - textLeft - font.width(text)) / 2,
                        y - settings.integer("map.headerHeight") + height, tint, true);
        }
    }
    private static com.example.examplemod.zones.match.ZoneGaugeGeometry.Ring ring(Circle circle) {
        return new com.example.examplemod.zones.match.ZoneGaugeGeometry.Ring(circle.centerX(), circle.centerZ(), circle.radius());
    }

    private static void renderAlive(GuiGraphics graphics, MatchStatus status) {
        var font = Minecraft.getInstance().font;
        String number = Integer.toString(status.playersAlive());
        String label = Texts.resolve(Texts.tr("screen.tacz_pubg.pubg_hud.alive_label"));
        int numberWidth = (int) Math.ceil(font.width(number) * 1.6), labelWidth = font.width(label);
        int width = numberWidth + labelWidth + 14, x = graphics.guiWidth() - width - 9, y = 9;
        var anchor = XaeroHudAnchor.minimap();
        if (anchor != null && anchor.overlaps(x - 3, y, x + width + 3, y + 34))
            x = Math.max(8, anchor.x() - width - 8);
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "alive_counter",
                x, y, width, 33)) {
        graphics.fill(x, y, x + numberWidth + 6, y + 19, 0x80161B20);
        graphics.fill(x + numberWidth + 8, y, x + width, y + 19, 0x80161B20);
        scaledString(graphics, number, x + 3, y + 2, 1.6F, 0xFFFFFFFF);
        graphics.drawString(font, label, x + numberWidth + 10, y + 6, 0xFFF0F1F2, true);
        if (status.teamsAlive() < status.playersAlive()) {
            String teams = Texts.resolve(Texts.tr("screen.tacz_pubg.pubg_hud.teams", status.teamsAlive()));
            graphics.drawString(font, teams, x + width - font.width(teams), y + 22, 0xFFCDD1D5, true);
        }
        }
    }

    private static void renderCompass(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getCameraEntity() == null) return;
        int width = Math.min(300, Math.max(120, graphics.guiWidth() - 190));
        int left = (graphics.guiWidth() - width) / 2, right = left + width, y = 19;
        var anchor = XaeroHudAnchor.minimap();
        if (anchor != null && anchor.overlaps(left, y - 10, right, y + 23)) y = anchor.y() + anchor.height() + 8;
        if (y > graphics.guiHeight() / 3 && !com.example.examplemod.hud.client.HudLayoutClient.hasPosition("compass")) return;
        var compassBounds = com.example.examplemod.hud.client.HudLayoutClient.resolveRect(
                graphics, "compass", left, y - 10, width, 34);
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "compass",
                left, y - 10, width, 34)) {
        double bearing = (Mth.wrapDegrees(mc.getCameraEntity().getYRot()) + 540) % 360;
        double pixelsPerDegree = width / 150.0;
        graphics.enableScissor(compassBounds.x(), compassBounds.y(),
                compassBounds.x() + compassBounds.width(), compassBounds.y() + compassBounds.height());
        for (int angle = 0; angle < 360; angle += 5) {
            double delta = Mth.wrapDegrees(angle - bearing);
            if (Math.abs(delta) > 78) continue;
            int x = (int) Math.round(graphics.guiWidth() / 2.0 + delta * pixelsPerDegree);
            boolean main = angle % 45 == 0, numbered = angle % 15 == 0;
            graphics.fill(x, y, x + 1, y + (main ? 6 : numbered ? 5 : 3), main ? 0xFFE4E7E9 : 0xFFB5BBC1);
            String label = main ? switch (angle) {
                case 0 -> "N"; case 45 -> "NE"; case 90 -> "E"; case 135 -> "SE";
                case 180 -> "S"; case 225 -> "SW"; case 270 -> "W"; default -> "NW";
            } : numbered ? Integer.toString(angle) : "";
            if (!label.isEmpty()) {
                float scale = main ? .9F : .7F;
                scaledString(graphics, label, x - (int) (mc.font.width(label) * scale / 2), y + 8,
                        scale, main ? 0xFFFFFFFF : 0xFFCCD1D5);
            }
        }
        for (var mate : MapOverlayClient.teammates()) {
            if (!mate.online() || !mate.alive() || mate.id().equals(mc.player.getUUID())
                    || !mate.dimension().equals(mc.level.dimension().location())) continue;
            double dx = mate.x() - mc.player.getX(), dz = mate.z() - mc.player.getZ();
            if (dx * dx + dz * dz < 1) continue;
            double heading = Math.toDegrees(Math.atan2(dx, -dz));
            double delta = Mth.wrapDegrees(heading - bearing);
            if (Math.abs(delta) > 73) continue;
            int x = (int) Math.round(graphics.guiWidth() / 2.0 + delta * pixelsPerDegree);
            int color = 0xFF000000 | mate.color() & 0xFFFFFF;
            graphics.fill(x - 4, y - 9, x + 5, y - 1, 0xD014181B);
            graphics.fill(x - 3, y - 8, x + 4, y - 2, color);
            String slot = Integer.toString(mate.slot());
            scaledString(graphics, slot, x - (int) (mc.font.width(slot) * .6F / 2), y - 8, .6F, 0xFFFFFFFF);
            graphics.fill(x - 1, y - 2, x + 2, y, color);
        }
        graphics.disableScissor();
        int middle = graphics.guiWidth() / 2;
        graphics.fill(middle - 1, y - 1, middle + 1, y + 6, 0xFFFFD44A);
        }
    }

    private static void warningGlyph(GuiGraphics graphics, int x, int y, int color) {
        for (int line = 0; line < 8; line++) {
            int half = line / 2;
            graphics.fill(x + 4 - half, y + line, x + 5 + half, y + line + 1, color);
        }
        graphics.fill(x + 4, y + 2, x + 5, y + 5, 0xFFFFFFFF);
        graphics.fill(x + 4, y + 6, x + 5, y + 7, 0xFFFFFFFF);
    }

    private static void centerMessage(GuiGraphics graphics, String text, boolean result) {
        var font = Minecraft.getInstance().font;
        String resolved = Texts.resolve(text);
        if (resolved.isBlank()) return;
        float scale = result ? 1.25F : 1F;
        int available = (int) ((graphics.guiWidth() - 30) / scale);
        String clipped = font.plainSubstrByWidth(resolved, Math.max(20, available));
        int y = Math.max(53, graphics.guiHeight() / 3);
        int boxWidth = result ? Math.min(graphics.guiWidth() - 20, Math.max(230, (int) (font.width(clipped) * scale)))
                : (int) (font.width(clipped) * scale);
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics,
                result ? "match_result" : "match_alert", (graphics.guiWidth() - boxWidth) / 2,
                result ? y - 23 : y, boxWidth, result ? 38 : 12)) {
        if (result) {
            String heading = Texts.resolve(Texts.tr("screen.tacz_pubg.pubg_hud.match_complete"));
            scaledString(graphics, heading, (graphics.guiWidth() - (int) (font.width(heading) * 1.6F)) / 2,
                    y - 23, 1.6F, 0xFFFFD04D);
            int ruleWidth = Math.min(210, graphics.guiWidth() - 35);
            graphics.fill((graphics.guiWidth() - ruleWidth) / 2, y - 5,
                    (graphics.guiWidth() + ruleWidth) / 2, y - 4, 0x99D9C27F);
        }
        int color = result ? 0xFFF0F0E7 : ((int) (255 * Math.min(1, alertTicks / 15.0)) << 24) | 0xF4CE53;
        scaledString(graphics, clipped, (graphics.guiWidth() - (int) (font.width(clipped) * scale)) / 2, y, scale, color);
        }
    }

    private static void scaledString(GuiGraphics graphics, String text, int x, int y, float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(Minecraft.getInstance().font, text, 0, 0, color, true);
        graphics.pose().popPose();
    }
}
