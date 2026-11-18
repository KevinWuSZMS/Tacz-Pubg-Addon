package com.example.examplemod.combat.client;

import com.example.examplemod.combat.CombatPayloads.Event;
import com.tacz.guns.api.TimelessAPI;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import me.xjqsh.lrtactical.api.LrTacticalAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

final class CombatMessageRenderer {
    // The insertion tag identifies the gun argument after Minecraft reorders the translation's placeholders.
    private static final String GUN_BADGE = "tacz_pubg:gun_badge";
    private record Run(String text, int width, int color, boolean framed) {}

    private CombatMessageRenderer() {}

    static MutableComponent text(String key, Object... args) {
        return Component.translatable("screen.tacz_pubg.combat." + key, args);
    }

    static boolean showsWeapon(Event event) {
        if (event.feedAttacker().name().isEmpty() || event.bleedout() || event.cause().equals("environment")) return false;
        if (event.unarmed()) return true;
        var id = ResourceLocation.tryParse(event.weaponId());
        return id != null && !id.getPath().isEmpty();
    }

    static Component weaponName(Event event) {
        if (event.unarmed()) return text("fists");
        var id = ResourceLocation.tryParse(event.weaponId());
        if (id == null || id.getPath().isEmpty()) return Component.empty();
        var gun = TimelessAPI.getClientGunIndex(id);
        if (gun.isPresent()) return Component.translatable(gun.get().getName());
        var item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isPresent()) return item.get().getDescription();
        if (event.throwable()) {
            for (var index : LrTacticalAPI.getThrowableIndexes())
                if (index.getId().equals(id)) return Component.translatable(index.getDescriptionId());
        } else {
            for (var index : LrTacticalAPI.getMeleeIndexes())
                if (index.getId().equals(id)) return Component.translatable(index.getDescriptionId());
        }
        return Component.literal(id.getPath().replace('_', ' '));
    }

    static boolean teammateMessage(Event event, UUID viewer, int team) {
        return (event.type().equals("knock") || event.type().equals("elimination"))
                && team > 0 && event.attacker().team() == team
                && !event.attacker().id().equals(viewer) && !event.victim().id().equals(viewer)
                // The finisher receives their own private assist separately.
                && !event.feedAttacker().id().equals(viewer);
    }

    static Component message(Event event, UUID viewer, int red) {
        if (event.type().equals("revive")) return text("revive", event.victim().name());
        if (event.type().equals("assist")) return text("assist_message",
                text("eliminate_verb").withColor(red & 0xFFFFFF), event.victim().name());
        Component subject = event.attacker().id().equals(viewer) ? text("you_subject") : Component.literal(event.attacker().name());
        Component victim = event.victim().id().equals(viewer) ? text("you_object") : Component.literal(event.victim().name());
        if (!showsWeapon(event) || !event.attacker().id().equals(event.feedAttacker().id())) {
            if (event.type().equals("knock")) return text("knocked_without_weapon", subject, victim);
            return text("finally_eliminated", subject, text("eliminate_past").withColor(red & 0xFFFFFF), victim);
        }
        Component action = event.type().equals("knock") ? text("knock_verb") : text("eliminate_verb").withColor(red & 0xFFFFFF);
        var weapon = weaponName(event).copy();
        if (event.firearm()) weapon.withStyle(style -> style.withInsertion(GUN_BADGE));
        return text(event.headshot() ? "headshot_message" : "attack_message", subject, weapon, action, victim);
    }

    static void draw(GuiGraphics g, Event event, UUID viewer, int cx, int y, int maxWidth,
                     int base, int red, float scale) {
        if ((base >>> 24) < 4) return;
        var font = Minecraft.getInstance().font;
        var runs = new ArrayList<Run>();
        message(event, viewer, red).visit((style, value) -> {
            if (!value.isEmpty()) {
                boolean framed = GUN_BADGE.equals(style.getInsertion());
                int color = event.friendlyFire() || style.getColor() != null ? red : base;
                runs.add(new Run(value, font.width(value) + (framed ? 8 : 0), color, framed));
            }
            return Optional.empty();
        }, Style.EMPTY);
        int total = runs.stream().mapToInt(Run::width).sum();
        float fit = Math.min(scale, (float) Math.max(1, maxWidth) / Math.max(1, total));
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(fit, fit, 1);
        int x = -total / 2;
        for (Run run : runs) {
            if (run.framed) {
                int border = event.friendlyFire() ? red : (base & 0xFF000000) | 0xB8AA7F;
                int right = x + run.width - 1;
                g.fill(x+1, -1, right, font.lineHeight, ((base >>> 25) << 24) | 0x201F1B);
                g.hLine(x+2, right-2, -2, border);
                g.hLine(x+2, right-2, font.lineHeight, border);
                g.vLine(x, -1, font.lineHeight-1, border);
                g.vLine(right, -1, font.lineHeight-1, border);
                g.fill(x+1, -1, x+2, 0, border);
                g.fill(right-1, -1, right, 0, border);
                g.fill(x+1, font.lineHeight-1, x+2, font.lineHeight, border);
                g.fill(right-1, font.lineHeight-1, right, font.lineHeight, border);
            }
            g.drawString(font, run.text, x + (run.framed ? 4 : 0), 0, run.color);
            x += run.width;
        }
        g.pose().popPose();
    }
}
