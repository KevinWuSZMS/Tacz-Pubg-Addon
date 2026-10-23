package com.example.examplemod.combat;

import java.util.HashSet;

/** Stable global IDs and label contrast are guaranteed; universal colour discrimination is not. */
public final class TeamPaletteChecks {
    public static void main(String[] args) throws Exception {
        var unique = new HashSet<Integer>(); int checks = 0;
        StringBuilder html = new StringBuilder("<!doctype html><meta charset='utf-8'><title>Global team colours 1–100</title><style>body{background:#181d24;font-family:system-ui;color:white;max-width:1050px;margin:30px auto}main{display:grid;grid-template-columns:repeat(10,1fr);gap:8px}.tile{text-align:center;padding:18px 4px;border-radius:4px}p{line-height:1.6}</style><h1>Global team colours 1–100</h1><p>256 candidates → deterministic perceptual max-min selection. Number labels remain mandatory. 100 colours cannot all be uniquely identified by eye, particularly with colour-vision deficiency. Administrator overrides are independent.</p><main>");
        double minimum = Double.POSITIVE_INFINITY;
        for (int team = 1; team <= 100; team++) {
            int color = TeamPalette.color(team), text = TeamPalette.textColor(team);
            if (!unique.add(color) || color >>> 24 != 255) throw new AssertionError("Colour uniqueness/alpha"); checks++;
            double contrast = TeamPalette.contrastRatio(color, text); minimum = Math.min(minimum, contrast);
            if (contrast < 4.5) throw new AssertionError("Unreadable number for team " + team + ": " + contrast); checks++;
            html.append(String.format("<div class='tile' style='background:#%06X;color:#%06X'><b>%d</b><small><br>#%06X</small></div>", color & 0xFFFFFF, text & 0xFFFFFF, team, color & 0xFFFFFF));
        }
        int saved = TeamPalette.color(1); int[] copy = TeamPalette.colors(); copy[0] = 0;
        if (TeamPalette.color(1) != saved) throw new AssertionError("Mutable palette escaped"); checks++;
        for (int invalid : new int[] {-1, 0, 101, Integer.MAX_VALUE}) {
            try { TeamPalette.color(invalid); throw new AssertionError("Invalid team accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        html.append("</main><p>Normal/CVD-aware max-min score is a practical adaptation, not proof of a globally optimal palette. Text contrast minimum: " + minimum + ":1.</p><p><a href='https://bottosson.github.io/posts/oklab/'>Oklab source</a> · <a href='https://pubmed.ncbi.nlm.nih.gov/19834201/'>Machado, Oliveira &amp; Fernandes 2009</a></p>");
        if (args.length > 0) java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), html);
        System.out.println("TeamPaletteChecks: " + checks + " passed; minimum number contrast " + minimum + ":1; no game launched.");
    }
}
