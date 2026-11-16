package com.example.examplemod.keybindings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** A chord is one atomic trigger. ANY contains independently removable single-key triggers. */
public record InputBinding(List<String> keys, Mode mode) {
    public enum Mode { CHORD, ANY }
    private static final InputBinding EMPTY = new InputBinding(List.of(), Mode.CHORD);

    public InputBinding {
        Objects.requireNonNull(keys, "keys");
        keys = List.copyOf(new LinkedHashSet<>(keys.stream()
                .filter(Objects::nonNull).filter(key -> !key.isBlank()).toList()));
        mode = mode == null ? Mode.CHORD : mode;
    }

    public static InputBinding empty() { return EMPTY; }
    public static InputBinding chord(String... keys) { return new InputBinding(List.of(keys), Mode.CHORD); }
    public boolean isEmpty() { return keys.isEmpty(); }

    public List<List<String>> groups() {
        if (keys.isEmpty()) return List.of();
        return mode == Mode.CHORD ? List.of(keys) : keys.stream().map(List::of).toList();
    }

    public boolean containsExact(List<String> trigger) {
        if (trigger.isEmpty()) return false;
        Set<String> expected = Set.copyOf(trigger);
        return groups().stream().anyMatch(group -> Set.copyOf(group).equals(expected));
    }

    /** Clearing G leaves Ctrl+G untouched; clearing G from G OR Mouse4 preserves Mouse4. */
    public InputBinding removeExact(List<String> trigger) {
        if (!containsExact(trigger)) return this;
        if (mode == Mode.CHORD) return empty();
        var remaining = new ArrayList<>(keys);
        remaining.removeAll(trigger);
        return new InputBinding(remaining, mode);
    }

    public String display() {
        if (keys.isEmpty()) return "未绑定";
        return keys.stream().map(BindingKeys::label).collect(Collectors.joining(mode == Mode.CHORD ? " + " : " / "));
    }
}
