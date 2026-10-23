package com.example.examplemod.gunbalance.client;

import com.example.examplemod.localization.Texts;

/** Pure geometry for one already sampled TaCZ cubic recoil spline, in degrees and milliseconds. */
public final class PersistentRecoilMath {
    private PersistentRecoilMath() {}

    public record Peak(double value, double timeMillis) {
        public static final Peak ZERO = new Peak(0, 0);

        public Peak {
            if (!Double.isFinite(value) || !Double.isFinite(timeMillis) || timeMillis < 0)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.persistent_recoil_math.recoil_peaks_must_be_finite_and_nonnegative_in_time"));
        }

        /** Each shot smoothly reaches its sampled peak, then holds it without returning to zero. */
        public double displacement(double elapsedMillis) {
            if (elapsedMillis <= 0) return 0;
            if (timeMillis <= 0 || elapsedMillis >= timeMillis) return value;
            double t = elapsedMillis / timeMillis;
            return value * t * t * (3 - 2 * t);
        }
    }

    /** Pitch keeps the highest positive uplift; yaw keeps the signed largest absolute excursion. */
    public static Peak peak(double[] knots, double[][] coefficients, boolean absolute) {
        if (knots == null || coefficients == null || knots.length != coefficients.length + 1
                || knots.length < 2 || knots[0] < 0) return Peak.ZERO;
        for (int i = 0; i < knots.length; i++) {
            if (!Double.isFinite(knots[i]) || (i > 0 && knots[i] <= knots[i - 1])) return Peak.ZERO;
        }
        Peak best = Peak.ZERO;
        for (int segment = 0; segment < coefficients.length; segment++) {
            double[] c = coefficients[segment];
            if (c == null || c.length == 0 || c.length > 4) return Peak.ZERO;
            for (double value : c) if (!Double.isFinite(value)) return Peak.ZERO;
            double duration = knots[segment + 1] - knots[segment];
            best = consider(best, c, 0, knots[segment], absolute);
            best = consider(best, c, duration, knots[segment], absolute);
            double a = 3 * coefficient(c, 3), b = 2 * coefficient(c, 2), d = coefficient(c, 1);
            if (a == 0) {
                if (b != 0) best = stationary(best, c, -d / b, duration, knots[segment], absolute);
            } else {
                double discriminant = b * b - 4 * a * d;
                if (Double.isFinite(discriminant) && discriminant >= 0) {
                    // This form retains the smaller root when b and sqrt(discriminant) nearly cancel.
                    double q = -.5 * (b + Math.copySign(Math.sqrt(discriminant), b));
                    if (q == 0) best = stationary(best, c, -b / (2 * a), duration, knots[segment], absolute);
                    else {
                        best = stationary(best, c, q / a, duration, knots[segment], absolute);
                        best = stationary(best, c, d / q, duration, knots[segment], absolute);
                    }
                }
            }
        }
        return best;
    }

    private static double coefficient(double[] c, int degree) { return degree < c.length ? c[degree] : 0; }

    private static Peak stationary(Peak best, double[] c, double time, double duration,
                                  double knot, boolean absolute) {
        return Double.isFinite(time) && time > 0 && time < duration
                ? consider(best, c, time, knot, absolute) : best;
    }

    private static Peak consider(Peak best, double[] c, double time, double knot, boolean absolute) {
        double value = 0;
        for (int i = c.length - 1; i >= 0; i--) value = value * time + c[i];
        if (!Double.isFinite(value)) return best;
        double score = absolute ? Math.abs(value) : value;
        double previous = absolute ? Math.abs(best.value) : best.value;
        return score > previous || (score == previous && knot + time < best.timeMillis)
                ? new Peak(value, knot + time) : best;
    }
}
