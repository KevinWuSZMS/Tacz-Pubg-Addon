package com.example.examplemod.keybindings;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.glfw.GLFW;

/** Exercises user-visible binding identity; no client, window, world or user config is constructed. */
public final class InputBindingChecks {
    private static int checks;
    public static void main(String[] args) {
        BindingProfileMigrationChecks.run();
        InputBinding chord = InputBinding.chord("ControlLeft", "G");
        check(chord.removeExact(List.of("G")).equals(chord), "Clearing G must retain Ctrl+G");
        check(chord.removeExact(List.of("ControlLeft")).equals(chord), "Clearing Ctrl must retain Ctrl+G");
        check(chord.removeExact(List.of("G", "ControlLeft")).isEmpty(), "A complete chord clears independent of selection order");
        check(!InputBinding.chord("G").containsExact(List.of("ControlLeft", "G")), "Selecting a chord cannot target a standalone member");
        InputBinding either = new InputBinding(List.of("G", "Mouse4"), InputBinding.Mode.ANY);
        check(either.removeExact(List.of("G")).keys().equals(List.of("Mouse4")), "OR alternatives retain their independent bindings");
        check(either.removeExact(List.of("G", "Mouse4")).equals(either), "An OR pair is not a two-key chord");
        check(either.groups().equals(List.of(List.of("G"), List.of("Mouse4"))), "OR produces two atomic inputs");
        check(chord.groups().equals(List.of(List.of("ControlLeft", "G"))), "A chord produces one atomic input");
        check(InputBinding.empty().groups().isEmpty(), "Unbound produces no trigger");
        check(!InputBinding.empty().containsExact(List.of()), "Empty selection never targets any mapping");
        check(InputBinding.chord("ControlAny", "G").removeExact(List.of("ControlLeft", "G"))
                .equals(InputBinding.chord("ControlAny", "G")), "Generic Ctrl binding remains an explicitly distinct saved trigger");
        var mutable = new ArrayList<>(List.of("G", "G", "Mouse4"));
        InputBinding independent = new InputBinding(mutable, InputBinding.Mode.ANY);
        mutable.clear();
        check(independent.keys().equals(List.of("G", "Mouse4")), "Saved binding owns an immutable deduplicated copy");
        var json = new Gson();
        for (InputBinding original : List.of(InputBinding.empty(), chord, either,
                InputBinding.chord("AltAny", "ScrollUp"), InputBinding.chord("ScanCode:123"))) {
            check(original.equals(json.fromJson(json.toJson(original), InputBinding.class)), "JSON retains complete trigger identity and mode");
        }
        for (String id : List.of("Esc", "W", "ControlLeft", "ControlRight", "NumpadEnter", "F25", "Mouse1", "Mouse5", "ScanCode:123")) {
            check(id.equals(BindingKeys.fromKey(BindingKeys.toKey(id))), "Physical key round trip: " + id);
        }
        check(BindingKeys.fromKeyboard(GLFW.GLFW_KEY_ESCAPE, 0).equals("Esc"), "Escape uses diagram ID");
        check(BindingKeys.fromMouse(1).equals("Mouse2"), "Mouse right is the second diagram button");
        check(BindingKeys.toKey("ScrollUp").equals(InputConstants.UNKNOWN), "Scroll pulse is routed separately from native primary keys");
        check(BindingKeys.aliases("ControlAny").containsAll(List.of("ControlLeft", "ControlRight")), "Native generic Ctrl supports either side");
        check(!BindingKeys.aliases("ControlLeft").contains("ControlRight"), "Explicit left Ctrl remains left only");
        BehaviorOptions match = new BehaviorOptions(true, false, false);
        check(match.equals(json.fromJson(json.toJson(match), BehaviorOptions.class)), "Per-profile behavior survives JSON round trip");
        System.out.println("InputBindingChecks: " + checks + " checks passed; no game launched.");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
