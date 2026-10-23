package com.example.examplemod.visibility;

import com.example.examplemod.localization.Texts;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Frame-local proof of native drawing, independent of terrain cache/compilation and shadow passes. */
public final class TargetRenderLedger {
    private final Set<Long> candidates = new HashSet<>(), rendered = new HashSet<>();
    private boolean capturing;
    public void begin(Collection<Long> keys) {
        var next = new HashSet<>(keys);
        if (next.size() > VisibilityPolicy.MAX_TARGETS) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.target_render_ledger.too_many_target_draw_candidates"));
        candidates.clear(); candidates.addAll(next); rendered.clear(); capturing = true;
    }
    public void record(long key) { if (capturing && candidates.contains(key)) rendered.add(key); }
    public void finish() { capturing = false; }
    public boolean wasRendered(long key) { return rendered.contains(key); }
    public void clear() { capturing = false; candidates.clear(); rendered.clear(); }
}
