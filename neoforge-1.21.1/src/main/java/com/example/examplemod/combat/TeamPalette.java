package com.example.examplemod.combat;

import java.util.Arrays;

/** Stable global team colours, independent of local squad-member colours and settings. */
public final class TeamPalette {
    private static final int[] COLORS = select();
    private TeamPalette() {}
    public static int color(int teamNumber) {
        if (teamNumber < 1 || teamNumber > 100) throw new IllegalArgumentException("Team number must be 1..100");
        return COLORS[teamNumber - 1];
    }
    public static int[] colors() { return COLORS.clone(); }
    public static int textColor(int teamNumber) { return contrastText(color(teamNumber)); }
    public static int contrastText(int argb) { return luminance(argb) > .179 ? 0xFF000000 : 0xFFFFFFFF; }
    public static double contrastRatio(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
    private static double luminance(int rgb) {
        return .2126 * linear((rgb >> 16 & 255) / 255D) + .7152 * linear((rgb >> 8 & 255) / 255D)
                + .0722 * linear((rgb & 255) / 255D);
    }
    private static int[] select() {
        int[] candidates = new int[256];
        double[][][] appearances = new double[256][4][];
        // Sixteen hue families, four saturations, four lightnesses: exactly 256 candidates.
        for (int i = 0; i < 256; i++) {
            candidates[i] = hsl((i % 16) / 16D, new double[] {.46, .62, .78, .94}[(i / 16) % 4],
                    new double[] {.35, .48, .61, .74}[i / 64]);
            appearances[i][0] = lab(candidates[i], null);
            appearances[i][1] = lab(candidates[i], new double[] {.152286, 1.052583, -.204868, .114503, .786281, .099216, -.003882, -.048116, 1.051998});
            appearances[i][2] = lab(candidates[i], new double[] {.367322, .860646, -.227968, .280085, .672501, .047413, -.011820, .042940, .968881});
            appearances[i][3] = lab(candidates[i], new double[] {1.255528, -.076749, -.178779, -.078411, .930809, .147602, .004733, .691367, .303900});
        }
        int[] selected = new int[100];
        boolean[] used = new boolean[256];
        double[] nearest = new double[256]; Arrays.fill(nearest, Double.POSITIVE_INFINITY);
        int next = 34 + 2 * 64; // Stable vivid yellow seed; team identity never depends on roster iteration.
        for (int slot = 0; slot < 100; slot++) {
            selected[slot] = candidates[next]; used[next] = true;
            for (int i = 0; i < 256; i++) if (!used[i]) nearest[i] = Math.min(nearest[i], distance(appearances[i], appearances[next]));
            int best = -1;
            for (int i = 0; i < 256; i++) if (!used[i] && (best < 0 || nearest[i] > nearest[best])) best = i;
            next = best;
        }
        return selected;
    }
    private static double distance(double[][] a, double[][] b) {
        double normal = squared(a[0], b[0]), cvd = Double.POSITIVE_INFINITY;
        for (int i = 1; i < 4; i++) cvd = Math.min(cvd, squared(a[i], b[i]));
        // Max-min selection considers common CVD confusions but cannot make 100 colours uniquely identifiable.
        return .65 * normal + .35 * cvd;
    }
    private static double squared(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2]; return x * x + y * y + z * z;
    }
    private static double[] lab(int rgb, double[] matrix) {
        double r = linear((rgb >> 16 & 255) / 255D), g = linear((rgb >> 8 & 255) / 255D), b = linear((rgb & 255) / 255D);
        if (matrix != null) {
            double rr = matrix[0] * r + matrix[1] * g + matrix[2] * b;
            double gg = matrix[3] * r + matrix[4] * g + matrix[5] * b;
            b = Math.clamp(matrix[6] * r + matrix[7] * g + matrix[8] * b, 0, 1);
            r = Math.clamp(rr, 0, 1); g = Math.clamp(gg, 0, 1);
        }
        double l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b);
        double m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b);
        double s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
        return new double[] {.2104542553 * l + .793617785 * m - .0040720468 * s,
                1.9779984951 * l - 2.428592205 * m + .4505937099 * s,
                .0259040371 * l + .7827717662 * m - .808675766 * s};
    }
    private static double linear(double v) { return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
    private static int hsl(double hue, double saturation, double lightness) {
        double chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
        double h = hue * 6, x = chroma * (1 - Math.abs(h % 2 - 1)), m = lightness - chroma / 2;
        double[] rgb = switch ((int) h) {
            case 0 -> new double[] {chroma, x, 0}; case 1 -> new double[] {x, chroma, 0};
            case 2 -> new double[] {0, chroma, x}; case 3 -> new double[] {0, x, chroma};
            case 4 -> new double[] {x, 0, chroma}; default -> new double[] {chroma, 0, x};
        };
        return 0xFF000000 | (int) Math.round((rgb[0] + m) * 255) << 16
                | (int) Math.round((rgb[1] + m) * 255) << 8 | (int) Math.round((rgb[2] + m) * 255);
    }
}
