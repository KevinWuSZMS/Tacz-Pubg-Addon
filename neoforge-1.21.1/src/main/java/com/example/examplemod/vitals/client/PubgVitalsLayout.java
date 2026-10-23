package com.example.examplemod.vitals.client;

import java.util.ArrayList;
import java.util.List;

/** Shared geometry for the real HUD and its editor preview, in Minecraft GUI pixels. */
public final class PubgVitalsLayout {
    /** Vanilla Gui.renderItemHotbar uses a 182px outer frame, centred with integer division first. */
    public static final int WIDTH = 182, HEALTH_BOTTOM = 28, BOOST_BOTTOM = 40;
    public static final int BACKGROUND = 0x66212427, BORDER = 0x80494D50;
    public static final int HEALTH = 0xF2E8E9E7, LOW_HEALTH = 0xFFE65F57, APRICOT = 0xFFFFDC9F;
    public static final int DAMAGE_TRAIL = 0xC9AA403D, ABSORPTION = 0xFF9ED6E7, BOOST = 0xFFF09A27;
    private static final int[] BOOST_THRESHOLDS = {0, 20, 60, 90, 100};
    public record Rectangle(int left, int top, int right, int bottom, int color) {}
    public record Style(int width, int healthHeight, int boostHeight, int gap, int bottom, int rowHeight,
                        double redThreshold, double apricotThreshold, int red, int apricot, int white,
                        int full, int reference, int treatment, int nextStep, int boost, int background) {
        public static Style defaults() { return new Style(WIDTH, 10, 2, 2, 28, 24, .25, .40,
                LOW_HEALTH, APRICOT, HEALTH, 0xA5B0B2B0, 0x65A6A8AA, 0x6F9DA1A3, 0xACBCBEC0, BOOST, BACKGROUND); }
    }
    public record Frame(int left, int top, int width, int healthTop, int boostTop, int rowTop, int rowHeight, int height) {}
    private PubgVitalsLayout() {}
    public static double fraction(double value, double maximum) {
        return Double.isFinite(value) && Double.isFinite(maximum) && maximum > 0 ? Math.clamp(value / maximum, 0, 1) : 0;
    }
    public static int width(int screenWidth) { return width(screenWidth, WIDTH); }
    public static int width(int screenWidth, int requested) { return Math.max(1, Math.min(requested, screenWidth)); }
    public static int left(int screenWidth, int width) { return screenWidth / 2 - width / 2; }
    public static Frame frame(int screenWidth, int screenHeight, Style style) {
        int width = width(screenWidth, style.width()), healthTop = Math.max(0, screenHeight - style.bottom() - style.healthHeight());
        int boostTop = Math.max(0, healthTop - style.gap() - style.boostHeight());
        int rowTop = Math.max(0, boostTop - style.rowHeight());
        return new Frame(left(screenWidth, width), boostTop, width, healthTop, boostTop, rowTop, style.rowHeight(),
                healthTop + style.healthHeight() - boostTop);
    }
    public static int healthColor(double ratio, Style style) {
        return ratio <= style.redThreshold() ? style.red() : ratio < style.apricotThreshold() ? style.apricot()
                : ratio >= 1 - 1e-6 ? style.full() : style.white();
    }
    public static List<Rectangle> bars(int screenWidth, int screenHeight, double health, double maximum,
                                     double damageTrail, double boost, double absorption) {
        return bars(screenWidth, screenHeight, health, maximum, damageTrail, boost, absorption, health, health, Style.defaults());
    }
    public static List<Rectangle> bars(int screenWidth, int screenHeight, double health, double maximum,
                                     double damageTrail, double boost, double absorption, double target, double next, Style style) {
        var f = frame(screenWidth, screenHeight, style);
        int left = f.left(), width = f.width(), top = f.healthTop(), bottom = top + style.healthHeight();
        var rectangles = new ArrayList<Rectangle>();
        add(rectangles, left, top, left + width, bottom, style.background());
        double ratio = fraction(health, maximum);
        int actual = pixels(width, ratio), trail = pixels(width, Math.max(ratio, fraction(damageTrail, maximum)));
        add(rectangles, left + actual, top, left + trail, bottom, DAMAGE_TRAIL);
        // The 75% hatch is a permanent reference, not the treatment forecast itself.
        int aid = pixels(width, .75);
        if (actual < aid) for (int y = top; y < bottom; y++) {
            int start = actual - Math.floorMod(actual + y, 6);
            for (int x = start; x < aid; x += 6) add(rectangles, left + Math.max(actual, x), y,
                    left + Math.min(aid, x + 2), y + 1, style.reference());
        }
        int predicted = pixels(width, Math.max(ratio, fraction(target, maximum)));
        int nextStep = Math.min(predicted, pixels(width, Math.max(ratio, fraction(next, maximum))));
        add(rectangles, left + actual, top, left + predicted, bottom, style.treatment());
        // Two ordinary grey overlays retain the underlying hatch through alpha.
        add(rectangles, left + actual, top, left + nextStep, bottom, style.nextStep());
        add(rectangles, left, top, left + actual, bottom, healthColor(ratio, style));
        if (absorption > 0) add(rectangles, left, top, left + pixels(width, fraction(absorption, maximum)), top + 2, ABSORPTION);
        double safeBoost = Math.clamp(Double.isFinite(boost) ? boost : 0, 0, 100);
        for (int tier = 0; tier < 4; tier++) {
            int start = BOOST_THRESHOLDS[tier], end = BOOST_THRESHOLDS[tier + 1];
            int x1 = left + pixels(width, start / 100.0), x2 = left + pixels(width, end / 100.0) - (tier < 3 ? 1 : 0);
            add(rectangles, x1, f.boostTop(), x2, f.boostTop() + style.boostHeight(), style.background());
            add(rectangles, x1, f.boostTop(), x1 + pixels(x2 - x1, Math.clamp((safeBoost - start) / (end - start), 0, 1)),
                    f.boostTop() + style.boostHeight(), style.boost());
        }
        return List.copyOf(rectangles);
    }
    private static int pixels(int width, double fraction) { return (int) Math.round(width * fraction); }
    private static void add(List<Rectangle> result, int left, int top, int right, int bottom, int color) {
        if (right > left && bottom > top) result.add(new Rectangle(left, top, right, bottom, color));
    }
}
