package com.example.examplemod.vitals.client;

import java.util.ArrayList;
import java.util.List;

/** Shared geometry for the real HUD and its editor preview, in Minecraft GUI pixels. */
public final class PubgVitalsLayout {
    /** Vanilla Gui.renderItemHotbar uses a 182px outer frame, centred with integer division first. */
    public static final int WIDTH = 182, HEALTH_BOTTOM = 28, BOOST_BOTTOM = 40;
    public static final int BACKGROUND = 0x66212427, BORDER = 0x80494D50;
    public static final int HEALTH = 0xFFF2F2F2, LOW_HEALTH = 0xFFE85B4F, APRICOT = 0xFFFCE6AB;
    public static final int CRITICAL_HEALTH = 0xFFE82F28;
    public static final double CRITICAL_THRESHOLD = .10;
    public static final int DAMAGE_TRAIL = 0xC9AA403D, ABSORPTION = 0xFF9ED6E7, BOOST = 0xFFF09A27;
    public static final int DOWNED_HEALTH = 0xFFF22424, DOWNED_DAMAGE_TRAIL = 0xFF9D2025;
    public static final int DOWNED_FILL_HATCH = 0x24FFFFFF, DOWNED_TRAIL_HATCH = 0x76FF7777,
            DOWNED_EMPTY_HATCH = 0x65898B8E;
    private static final int[] BOOST_THRESHOLDS = {0, 20, 60, 90, 100};
    public record Rectangle(int left, int top, int right, int bottom, int color) {}
    public record Style(int width, int healthHeight, int boostHeight, int gap, int bottom, int rowHeight,
                        double redThreshold, double apricotThreshold, int red, int apricot, int white,
                        int full, int reference, int treatment, int nextStep, int boost, int background,
                        int criticalRed, int flashTicks) {
        public static Style defaults() { return new Style(WIDTH, 10, 2, 2, 28, 24, .25, .50,
                LOW_HEALTH, APRICOT, HEALTH, 0xA5B0B2B0, 0x65A6A8AA, 0x6F9DA1A3, 0xACBCBEC0, BOOST, BACKGROUND,
                CRITICAL_HEALTH, 5); }
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
        return healthColor(ratio, style, 0);
    }
    /** Only the living red fill flashes; exact 10%, 25%, 50% and 100% boundaries enter their next band. */
    public static int healthColor(double ratio, Style style, long animationTicks) {
        if (ratio >= 1) return style.full();
        if (ratio < style.redThreshold()) {
            boolean brightPhase = Math.floorMod(animationTicks, 2L * Math.max(1, style.flashTicks())) >= Math.max(1, style.flashTicks());
            return ratio > 0 && ratio < CRITICAL_THRESHOLD && brightPhase ? style.criticalRed() : style.red();
        }
        return ratio < style.apricotThreshold() ? style.apricot() : style.white();
    }
    public static List<Rectangle> bars(int screenWidth, int screenHeight, double health, double maximum,
                                     double damageTrail, double boost, double absorption) {
        return bars(screenWidth, screenHeight, health, maximum, damageTrail, boost, absorption, health, Style.defaults());
    }
    public static List<Rectangle> bars(int screenWidth, int screenHeight, double health, double maximum,
                                     double damageTrail, double boost, double absorption, double target, Style style) {
        return bars(screenWidth, screenHeight, health, maximum, damageTrail, boost, absorption, target, style, 0);
    }
    /** Legacy next-pulse arguments remain compatible but no longer create a separate preview layer. */
    public static List<Rectangle> bars(int screenWidth, int screenHeight, double health, double maximum,
                                     double damageTrail, double boost, double absorption, double target, double next, Style style) {
        return bars(screenWidth, screenHeight, health, maximum, damageTrail, boost, absorption, target, style, 0);
    }
    public static List<Rectangle> bars(int screenWidth, int screenHeight, double health, double maximum,
                                     double damageTrail, double boost, double absorption, double target, double next, Style style,
                                     long animationTicks) {
        return bars(screenWidth, screenHeight, health, maximum, damageTrail, boost, absorption, target, style, animationTicks);
    }
    public static List<Rectangle> bars(int screenWidth, int screenHeight, double health, double maximum,
                                     double damageTrail, double boost, double absorption, double target, Style style,
                                     long animationTicks) {
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
        // A single translucent total forecast retains the underlying 75% reference hatch.
        add(rectangles, left + actual, top, left + predicted, bottom, style.treatment());
        add(rectangles, left, top, left + actual, bottom, healthColor(ratio, style, animationTicks));
        if (absorption > 0) add(rectangles, left, top, left + pixels(width, fraction(absorption, maximum)), top + 2, ABSORPTION);
        addBoost(rectangles, f, boost, style);
        return List.copyOf(rectangles);
    }
    /** DBNO uses its own red blood pool, with continuous hatching through its depleted portion. */
    public static List<Rectangle> downedBars(int screenWidth, int screenHeight, double health, double maximum,
                                           double damageTrail, Style style) {
        var f = frame(screenWidth, screenHeight, style);
        int left = f.left(), width = f.width(), top = f.healthTop(), bottom = top + style.healthHeight();
        int actual = pixels(width, fraction(health, maximum));
        int trail = Math.max(actual, pixels(width, fraction(damageTrail, maximum)));
        var rectangles = new ArrayList<Rectangle>();
        add(rectangles, left, top, left + width, bottom, style.background());
        add(rectangles, left, top, left + actual, bottom, DOWNED_HEALTH);
        add(rectangles, left + actual, top, left + trail, bottom, DOWNED_DAMAGE_TRAIL);
        downedHatch(rectangles, left, top, bottom, 0, actual, DOWNED_FILL_HATCH);
        downedHatch(rectangles, left, top, bottom, actual, trail, DOWNED_TRAIL_HATCH);
        downedHatch(rectangles, left, top, bottom, trail, width, DOWNED_EMPTY_HATCH);
        addBoost(rectangles, f, 0, style);
        return List.copyOf(rectangles);
    }
    private static void downedHatch(List<Rectangle> rectangles, int left, int top, int bottom,
                                    int start, int end, int color) {
        for (int y = top; y < bottom; y++) {
            int first = start - Math.floorMod(start + y - top, 6);
            for (int x = first; x < end; x += 6)
                add(rectangles, left + Math.max(start, x), y, left + Math.min(end, x + 2), y + 1, color);
        }
    }
    private static void addBoost(List<Rectangle> rectangles, Frame f, double boost, Style style) {
        int left = f.left(), width = f.width();
        double safeBoost = Math.clamp(Double.isFinite(boost) ? boost : 0, 0, 100);
        for (int tier = 0; tier < 4; tier++) {
            int start = BOOST_THRESHOLDS[tier], end = BOOST_THRESHOLDS[tier + 1];
            int x1 = left + pixels(width, start / 100.0), x2 = left + pixels(width, end / 100.0) - (tier < 3 ? 1 : 0);
            add(rectangles, x1, f.boostTop(), x2, f.boostTop() + style.boostHeight(), style.background());
            add(rectangles, x1, f.boostTop(), x1 + pixels(x2 - x1, Math.clamp((safeBoost - start) / (end - start), 0, 1)),
                    f.boostTop() + style.boostHeight(), style.boost());
        }
    }
    private static int pixels(int width, double fraction) { return (int) Math.round(width * fraction); }
    private static void add(List<Rectangle> result, int left, int top, int right, int bottom, int color) {
        if (right > left && bottom > top) result.add(new Rectangle(left, top, right, bottom, color));
    }
}
