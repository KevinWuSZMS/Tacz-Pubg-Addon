package com.example.examplemod.combat.client;

import com.example.examplemod.combat.CombatPayloads.Actor;
import com.example.examplemod.combat.CombatPayloads.Event;
import com.example.examplemod.combat.TeamPalette;
import com.example.examplemod.pubgsettings.PubgSettings;
import com.example.examplemod.vitals.client.CombatHudTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

final class CombatFeedRow {
    private static final ResourceLocation HEADSHOT = icon("headshot");
    private static final ResourceLocation DOWNED = icon("downed");
    private static final ResourceLocation FIST = icon("fist");

    private CombatFeedRow() {}

    private static ResourceLocation icon(String key) {
        return ResourceLocation.fromNamespaceAndPath("tacz_pubg", "textures/gui/hud/kill_feed/" + key + ".png");
    }

    static void draw(GuiGraphics g, Event event, int viewerTeam, int width, int top, int row,
                     PubgSettings settings, double opacity) {
        Font font = Minecraft.getInstance().font;
        boolean friendly = event.friendlyFire();
        boolean ownTeam = viewerTeam > 0 && viewerTeam == event.attacker().team();
        String tint = friendly ? "combat.kill_color" : ownTeam ? "combat.own_color" : "combat.text_color";
        int color = CombatHudClient.alpha(settings.color(tint), opacity);
        if ((color >>> 24) < 4) return;
        int iconHeight = Math.max(6, row - 3);
        boolean downed = event.type().equals("knock") || event.bleedout();
        boolean headshot = event.firearm() && event.headshot();
        boolean source = !event.bleedout() && (event.firearm() || event.unarmed() || event.throwable()
                || !event.weaponId().isEmpty() || !downed);
        int sourceWidth = source ? sourceWidth(font, event, iconHeight) : 0;
        String flag = friendly ? CombatMessageRenderer.text("friendly_fire").getString() : "";
        int icons = padded(sourceWidth) + padded(headshot ? iconHeight : 0) + padded(downed ? iconHeight * 3 / 2 : 0);
        int fixed = 6 + padded(badgeWidth(font, event.attacker())) + badgeWidth(font, event.victim())
                + icons + padded(font.width(flag));
        String left = event.attacker().name();
        if (left.isEmpty()) left = CombatMessageRenderer.text("environment").getString();
        String right = event.victim().name();
        int nameSpace = Math.max(16, width - fixed);
        int leftMax = Math.min(font.width(left), Math.max(nameSpace / 2, nameSpace - font.width(right)));
        left = font.plainSubstrByWidth(left, leftMax);
        right = font.plainSubstrByWidth(right, nameSpace - font.width(left));
        int total = fixed + font.width(left) + font.width(right);
        float fit = Math.min(1, (float) width / total);
        g.pose().pushPose();
        g.pose().translate(width - total * fit, top + (1-fit) * row / 2, 0);
        g.pose().scale(fit, fit, 1);
        g.fill(0, 0, total, row - 1, CombatHudClient.alpha(settings.color("hud.panel_color"), opacity));
        int y = (row - font.lineHeight) / 2;
        int x = 2;
        x += padded(badge(g, event.attacker(), x, row, friendly, color, opacity));
        g.drawString(font, left, x, y, color);
        x += font.width(left) + 2;
        if (source) {
            var id = ResourceLocation.tryParse(event.weaponId());
            boolean drawn;
            if (event.unarmed()) {
                CombatHudTextures.draw(g, FIST, x, 1, sourceWidth, iconHeight, color);
                drawn = true;
            } else if (event.firearm() && id != null) {
                drawn = KillFeedWeaponIcons.drawGun(g, id, x, 1, sourceWidth, iconHeight, color);
            } else {
                drawn = event.throwable() && id != null
                        && KillFeedWeaponIcons.drawThrowable(g, id, x, 1, sourceWidth, iconHeight, color);
            }
            if (!drawn) {
                String label = font.plainSubstrByWidth(sourceLabel(event), sourceWidth);
                g.drawString(font, label, x + (sourceWidth - font.width(label)) / 2, y, color);
            }
            x += padded(sourceWidth);
        }
        if (headshot) {
            CombatHudTextures.draw(g, HEADSHOT, x, 1, iconHeight, iconHeight, color);
            x += padded(iconHeight);
        }
        if (downed) {
            CombatHudTextures.draw(g, DOWNED, x, 1, iconHeight * 3 / 2, iconHeight, color);
            x += padded(iconHeight * 3 / 2);
        }
        if (friendly) {
            g.drawString(font, flag, x, y, color);
            x += padded(font.width(flag));
        }
        g.drawString(font, right, x, y, color);
        badge(g, event.victim(), x + font.width(right) + 2, row, friendly, color, opacity);
        g.pose().popPose();
    }

    private static int sourceWidth(Font font, Event event, int iconHeight) {
        if (event.firearm()) return iconHeight * 3;
        if (event.unarmed()) return iconHeight * 3 / 2;
        if (event.throwable()) return iconHeight;
        return Math.min(45, font.width(sourceLabel(event)));
    }

    private static String sourceLabel(Event e) {
        if (e.type().equals("revive")) return "+";
        if (e.weaponId().isEmpty()) return CombatMessageRenderer.text("eliminate_verb").getString();
        return CombatMessageRenderer.weaponName(e).getString();
    }

    private static int padded(int width) { return width > 0 ? width + 2 : 0; }

    private static int badgeWidth(Font font, Actor actor) {
        return actor.team() > 0 ? Math.max(10, font.width(Integer.toString(actor.team())) + 4) : 0;
    }

    private static int badge(GuiGraphics g, Actor actor, int x, int row, boolean friendly, int color, double opacity) {
        Font font = Minecraft.getInstance().font;
        int width = badgeWidth(font, actor);
        if (width > 0) {
            int background = friendly ? 0xFF292021 : actor.color();
            int label = friendly ? color : CombatHudClient.alpha(TeamPalette.contrastText(background), opacity);
            g.fill(x, 1, x + width, row - 2, CombatHudClient.alpha(background, opacity));
            String number = Integer.toString(actor.team());
            g.drawString(font, number, x + (width - font.width(number)) / 2, (row - font.lineHeight) / 2, label, false);
        }
        return width;
    }
}
