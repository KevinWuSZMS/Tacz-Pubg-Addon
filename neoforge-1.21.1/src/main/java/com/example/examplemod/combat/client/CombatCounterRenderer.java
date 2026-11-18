package com.example.examplemod.combat.client;

import com.example.examplemod.combat.CombatCounterNotice;
import com.example.examplemod.vitals.client.CombatHudNumbers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Vector3f;

/** A fixed localized label, vertically rolling total, and a short post-roll highlight. */
final class CombatCounterRenderer {
    private CombatCounterRenderer() {}

    static void draw(GuiGraphics g, CombatCounterNotice.Frame frame, boolean assist,
                     int cx, int y, int maxWidth, int color, float scale) {
        if (frame.amount() <= 0 || frame.opacity() <= 0) return;
        int tint = CombatHudClient.alpha(color, frame.opacity());
        if ((tint >>> 24) < 4) return;
        var font = Minecraft.getInstance().font;
        String template = CombatMessageRenderer.text(assist ? "assists" : "kills", "\uFFFC").getString();
        int at = template.indexOf('\uFFFC');
        if (at < 0) return;
        String prefix = template.substring(0, at), suffix = template.substring(at + 1);
        String current = Integer.toString(frame.amount()), previous = Integer.toString(frame.previousAmount());
        float numberHeight = font.lineHeight + 2;
        int digits = Math.max(CombatHudNumbers.width(current, numberHeight),
                frame.previousAmount() > 0 ? CombatHudNumbers.width(previous, numberHeight) : 0);
        int prefixWidth = font.width(prefix), total = prefixWidth + digits + font.width(suffix);
        float fit = Math.min(scale, (float) Math.max(1, maxWidth) / Math.max(1, total));
        g.pose().pushPose();
        g.pose().translate(cx - total * fit / 2, y, 0);
        g.pose().scale(fit, fit, 1);
        if (frame.flash() > 0)
            g.fill(-3, -3, total + 3, font.lineHeight + 2,
                    CombatHudClient.alpha(0xFFD8D8D8, frame.opacity() * frame.flash()));
        g.drawString(font, prefix, 0, 0, tint);
        g.drawString(font, suffix, prefixWidth + digits, 0, tint);
        double progress = frame.progress();
        float travel = numberHeight + 2;
        boolean rolling = frame.previousAmount() > 0 && progress < 1;
        if (rolling) {
            // Scissors use screen coordinates, including a player's saved HUD translation and scale.
            var transform = g.pose().last().pose();
            var left = transform.transformPosition(new Vector3f(prefixWidth - 2, -6, 0));
            var right = transform.transformPosition(new Vector3f(prefixWidth + digits + 2, numberHeight + 4, 0));
            g.flush();
            g.enableScissor((int)Math.floor(left.x), (int)Math.floor(left.y),
                    (int)Math.ceil(right.x), (int)Math.ceil(right.y));
        }
        if (frame.previousAmount() > 0 && progress < 1) {
            CombatHudNumbers.draw(g, previous,
                    prefixWidth + digits - CombatHudNumbers.width(previous, numberHeight),
                    -2 - (float) progress * travel, numberHeight,
                    CombatHudClient.alpha(tint, 1 - progress));
        }
        CombatHudNumbers.draw(g, current,
                prefixWidth + digits - CombatHudNumbers.width(current, numberHeight),
                -2 + (float) (1 - progress) * travel, numberHeight,
                CombatHudClient.alpha(tint, progress));
        if (rolling) { g.flush(); g.disableScissor(); }
        g.pose().popPose();
    }
}
