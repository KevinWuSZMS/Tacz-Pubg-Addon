package com.example.examplemod.hud.client;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The entry snapshot survives reset and failed saves until the user chooses an exit action. */
final class HudLayoutDraft {
    @FunctionalInterface interface Writer { void write(Map<String, HudLayoutModel.Position> positions) throws IOException; }
    private final Map<String, HudLayoutModel.Position> original;
    private final Map<String, HudLayoutModel.Position> working;
    private final Map<String, HudLayoutModel.Position> view;
    HudLayoutDraft(Map<String, HudLayoutModel.Position> initial) {
        original = Map.copyOf(initial); working = new LinkedHashMap<>(original);
        view = Collections.unmodifiableMap(working);
    }
    Map<String, HudLayoutModel.Position> positions() { return view; }
    Map<String, HudLayoutModel.Position> original() { return original; }
    void put(String id, HudLayoutModel.Position position) { working.put(id, position); }
    void remove(String id) { working.remove(id); }
    void clear() { working.clear(); }
    boolean containsKey(String id) { return working.containsKey(id); }
    HudLayoutModel.Position get(String id) { return working.get(id); }
    Map<String, HudLayoutModel.Position> save(Writer writer) throws IOException {
        var next = Map.copyOf(working);
        writer.write(next);
        return next;
    }
}
