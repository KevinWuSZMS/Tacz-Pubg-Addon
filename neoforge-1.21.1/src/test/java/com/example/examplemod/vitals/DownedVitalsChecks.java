package com.example.examplemod.vitals;

import com.example.examplemod.vitals.client.DownedHealthTrail;
import com.example.examplemod.vitals.client.PubgVitalsLayout;
import java.util.List;

/** Checks downed bar geometry and damage timing without starting a client. */
public final class DownedVitalsChecks {
    private static int checks;

    public static void main(String[] args) {
        var style = PubgVitalsLayout.Style.defaults();
        var f = PubgVitalsLayout.frame(854, 480, style);
        var full = PubgVitalsLayout.downedBars(854, 480, 100, 100, 100, style);
        check(has(full, PubgVitalsLayout.DOWNED_HEALTH, f.left(), f.left() + f.width()),
                "A fresh down starts with a full bright red bar");
        check(full.stream().anyMatch(r -> r.color() == PubgVitalsLayout.DOWNED_FILL_HATCH),
                "A full downed bar already has diagonal stripes");
        check(full.stream().noneMatch(r -> r.color() == PubgVitalsLayout.DOWNED_DAMAGE_TRAIL
                        || r.color() == PubgVitalsLayout.DOWNED_EMPTY_HATCH),
                "A full bar has no fabricated damage or depleted portion");
        var damaged = PubgVitalsLayout.downedBars(854, 480, 55, 100, 70, style);
        int actual = f.left() + (int) Math.round(f.width() * .55);
        int trail = f.left() + (int) Math.round(f.width() * .70);
        check(has(damaged, PubgVitalsLayout.DOWNED_HEALTH, f.left(), actual), "Real blood uses current HP");
        check(has(damaged, PubgVitalsLayout.DOWNED_DAMAGE_TRAIL, actual, trail), "Recent loss has a distinct red segment");
        check(damaged.stream().anyMatch(r -> r.color() == PubgVitalsLayout.DOWNED_EMPTY_HATCH
                        && r.left() > f.left() + f.width() * .95),
                "The depleted diagonal pattern continues beyond the ordinary 75 percent reference");
        check(damaged.stream().noneMatch(r -> r.color() == style.reference() || r.color() == style.treatment()
                        || r.color() == style.nextStep() || r.color() == style.white() || r.color() == style.full()),
                "Downed blood does not inherit ordinary health thresholds or medicine forecasts");
        for (int width : new int[]{1, 80, 853, 854}) {
            var bounds = PubgVitalsLayout.frame(width, 480, style);
            for (double hp : new double[]{0, .01, 25, 99.99, 100}) {
                var bars = PubgVitalsLayout.downedBars(width, 480, hp, 100, 70, style);
                check(bars.stream().allMatch(r -> r.left() >= bounds.left() && r.right() <= bounds.left() + bounds.width()
                                && r.right() > r.left() && r.bottom() > r.top()),
                        "All blood and hatch rectangles stay inside the selected HUD width");
            }
        }
        var history = new DownedHealthTrail();
        history.tick(100, 100, 1);
        history.tick(60, 100, 1);
        check(history.value(60, 100, 1) == 100, "An impact retains its old edge briefly");
        for (int tick = 0; tick < 100; tick++) history.tick(60, 100, 1);
        check(history.value(60, 100, 1) == 60, "An isolated impact fully settles");
        history.clear();
        history.tick(100, 100, 2);
        for (int tick = 1; tick <= 600; tick++) history.tick(100 - tick * .1, 100, 2);
        double bleedingTrail = history.value(40, 100, 2);
        check(bleedingTrail > 40 && bleedingTrail < 42,
                "Continuous bleeding leaves a bounded recent-loss segment instead of holding at full forever");
        for (int tick = 0; tick < 100; tick++) history.tick(40, 100, 2);
        check(history.value(40, 100, 2) == 40, "A rescue pause allows the old damage segment to settle");
        check(history.value(100, 100, 3) == 100, "A new down snapshot cannot borrow an older down's edge");
        history.tick(100, 100, 3);
        check(history.value(100, 100, 3) == 100, "A repeated down starts full");
        history.tick(50, 100, 3);
        history.clear();
        check(history.value(10, 100, 3) == 10, "Clearing a match or revival discards its damage history");
        history.tick(10, 100, 3);
        history.tick(20, 100, 3);
        check(history.value(20, 100, 3) == 20, "Restoring health cannot retain stale loss");
        history.tick(20, 40, 3);
        check(history.value(20, 40, 3) == 20, "Changing maximum health starts a fresh scale");
        System.out.println("DownedVitalsChecks: " + checks + " geometry and damage-timing checks passed; no game launched.");
    }

    private static boolean has(List<PubgVitalsLayout.Rectangle> rectangles, int color, int left, int right) {
        return rectangles.stream().anyMatch(r -> r.color() == color && r.left() == left && r.right() == right);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
