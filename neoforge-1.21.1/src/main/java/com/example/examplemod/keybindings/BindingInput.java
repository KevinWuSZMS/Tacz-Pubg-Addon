package com.example.examplemod.keybindings;

import com.example.examplemod.keybindings.mixin.KeyMappingAccess;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Resolves the existing registered KeyMappings against the player's active input profile. */
public final class BindingInput {
    public interface ToggleMode { boolean taczPubg$isToggleMode(); }
    private static final Map<KeyMapping, InputBinding> ACTIVE = new IdentityHashMap<>();
    private static final Set<String> PHYSICAL = new LinkedHashSet<>();
    private static final Set<KeyMapping> PULSES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<String> NATIVE_MODIFIED = new LinkedHashSet<>();
    private static Set<String> before = Set.of();
    private static String eventKey;
    private static int eventAction = GLFW.GLFW_RELEASE;
    private static BindingProfile applied;
    private static long revision = -1;
    private static boolean registered, savingOptions;
    private static final int SCROLL_UP_SCAN = 0x6fffff1, SCROLL_DOWN_SCAN = 0x6fffff2;

    private BindingInput() {}

    public static void register() {
        if (registered) return;
        registered = true;
        BindingInputCompatibility.register();
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ClientTickEvent.Pre event) -> refresh());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ClientTickEvent.Post event) -> {
            for (var mapping : PULSES) if (!physicallyHeld(mapping)) mapping.setDown(false);
            PULSES.clear();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            releasePhysical();
            applied = null;
        });
    }

    /** Also called by the binding screen after a save, so menus immediately show updated hints. */
    public static void refresh() {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || savingOptions) return;
        var profiles = BindingProfiles.get();
        profiles.ensureInitialized();
        BindingProfile profile = profiles.activeProfile();
        if (applied != profile || revision != profiles.revision()) {
            releasePhysical();
            applied = profile;
            revision = profiles.revision();
            ACTIVE.clear();
            for (var action : profiles.actions()) ACTIVE.put(action.mapping(), profiles.binding(profile, action.id()));
            project(profile);
        }
        if (!mc.isWindowActive()) releasePhysical();
        // A context can close without a physical key release (inventory, chat, disconnect).
        for (var mapping : ACTIVE.keySet()) if (!mapping.getKeyConflictContext().isActive()) {
            ((KeyMappingAccess) mapping).taczPubg$setRawDown(false);
        }
    }

    public static boolean managed(KeyMapping mapping) { return ACTIVE.containsKey(mapping); }
    public static InputBinding binding(KeyMapping mapping) { return ACTIVE.get(mapping); }
    static boolean registeredMatch(Set<String> ids, String physical) {
        return ACTIVE.keySet().stream().anyMatch(mapping -> ids.contains(mapping.getName()) && matches(mapping, physical));
    }

    public static void keyboard(long window, int key, int scan, int action) {
        if (!validWindow(window)) return;
        record(BindingKeys.fromKeyboard(key, scan), action);
    }

    public static void mouse(long window, int button, int action) {
        if (!validWindow(window)) return;
        record(BindingKeys.fromMouse(button), action);
    }

    private static boolean validWindow(long window) {
        var mc = Minecraft.getInstance();
        return mc != null && mc.getWindow() != null && mc.getWindow().getWindow() == window;
    }

    private static void record(String id, int action) {
        if (id == null) return;
        before = Set.copyOf(PHYSICAL);
        eventKey = id;
        eventAction = action;
        NATIVE_MODIFIED.clear();
        if (action == GLFW.GLFW_RELEASE) PHYSICAL.remove(id); else PHYSICAL.add(id);
        // Run releases before cancellable addon handlers: Ctrl may be the first released member.
        if (action == GLFW.GLFW_RELEASE) {
            for (var entry : ACTIVE.entrySet()) {
                if (held(entry.getValue(), before) && !held(entry.getValue(), PHYSICAL)
                        && !PULSES.contains(entry.getKey())) entry.getKey().setDown(false);
            }
        }
        if (action != GLFW.GLFW_RELEASE) for (var entry : ACTIVE.entrySet()) {
            if (held(entry.getValue(), PHYSICAL)
                    && entry.getValue().groups().stream().filter(group -> held(group, PHYSICAL))
                        .allMatch(group -> shadowed(entry.getKey(), group, PHYSICAL))) entry.getKey().setDown(false);
        }
    }

    public static String keyboardId(int key, int scan) {
        if (key == GLFW.GLFW_KEY_UNKNOWN && scan == BindingInputCompatibility.FORWARDED_SCAN) return BindingInputCompatibility.forwardedId();
        if (key == GLFW.GLFW_KEY_UNKNOWN && scan == SCROLL_UP_SCAN) return "ScrollUp";
        if (key == GLFW.GLFW_KEY_UNKNOWN && scan == SCROLL_DOWN_SCAN) return "ScrollDown";
        return BindingKeys.fromKeyboard(key, scan);
    }

    public static String mouseId(int button) {
        return button == BindingInputCompatibility.FORWARDED_BUTTON
                ? BindingInputCompatibility.forwardedId() : BindingKeys.fromMouse(button);
    }

    /** Native event consumers (including TaCZ) keep receiving their own registered KeyMapping. */
    public static boolean matches(KeyMapping mapping, String id) {
        var value = ACTIVE.get(mapping);
        if (value == null || id == null) return false;
        boolean releasing = id.equals(eventKey) && eventAction == GLFW.GLFW_RELEASE;
        if (!triggerMatches(value, id, before, PHYSICAL, releasing)) return false;
        // Native event-driven holds must receive their release even after another chord shadows them.
        if (releasing) return true;
        Set<String> state = releasing ? before : PHYSICAL;
        return value.groups().stream().anyMatch(group -> includes(group, id) && held(group, state)
                && !shadowed(mapping, group, state));
    }

    static boolean triggerMatches(InputBinding value, String id, Set<String> before, Set<String> after, boolean releasing) {
        if (releasing && held(value, after)) return false;
        Set<String> state = releasing ? before : after;
        return value.groups().stream().anyMatch(group -> includes(group, id) && held(group, state));
    }

    private static boolean shadowed(KeyMapping mapping, List<String> group, Set<String> state) {
        if (group.size() == 1 && !modifier(group.getFirst())
                && BindingKeys.aliases(group.getFirst()).stream().anyMatch(NATIVE_MODIFIED::contains)) return true;
        for (var other : ACTIVE.entrySet()) {
            if (other.getKey() == mapping || !other.getKey().getKeyConflictContext().isActive()) continue;
            if (!mapping.getKeyConflictContext().conflicts(other.getKey().getKeyConflictContext())
                    && !other.getKey().getKeyConflictContext().conflicts(mapping.getKeyConflictContext())) continue;
            if (other.getValue().groups().stream().anyMatch(specific -> modifiedVersion(specific, group) && held(specific, state))) return true;
        }
        return false;
    }

    /** NeoForge gives an active modifier chord precedence over its unmodified primary. */
    static boolean modifiedVersion(List<String> specific, List<String> general) {
        if (general.size() != 1 || modifier(general.getFirst()) || specific.size() <= 1) return false;
        return specific.stream().filter(key -> !modifier(key)).count() == 1
                && specific.stream().filter(key -> !modifier(key)).anyMatch(key ->
                    !Collections.disjoint(BindingKeys.aliases(key), BindingKeys.aliases(general.getFirst())));
    }

    private static boolean modifier(String key) {
        return key.startsWith("Control") || key.startsWith("Shift") || key.startsWith("Alt") || key.startsWith("Meta");
    }

    private static boolean includes(List<String> group, String id) {
        return group.stream().anyMatch(member -> BindingKeys.aliases(member).contains(id));
    }

    public static boolean held(InputBinding binding, Set<String> physical) {
        return binding.groups().stream().anyMatch(group -> held(group, physical));
    }

    private static boolean held(List<String> group, Set<String> physical) {
        return !group.isEmpty() && group.stream().allMatch(member ->
                BindingKeys.aliases(member).stream().anyMatch(physical::contains));
    }

    /** Opening a radial screen releases logical keys but must retain the physical trigger chord. */
    public static boolean physicallyHeld(KeyMapping mapping) {
        var value = ACTIVE.get(mapping);
        if (value != null) return held(value, PHYSICAL);
        var key = mapping.getKey();
        long window = Minecraft.getInstance().getWindow().getWindow();
        return key.getType() == InputConstants.Type.MOUSE
                ? GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS
                : key.getType() == InputConstants.Type.KEYSYM && key.getValue() >= 0
                    && GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
    }

    /** Screen lifetimes must recover even when an input consumer loses the release callback. */
    public static boolean pollHeld(KeyMapping mapping) {
        return pollHeld(mapping, null, false);
    }

    /** Inventory movement permits keyboard members only; mouse input belongs to the UI. */
    public static boolean pollKeyboardHeld(KeyMapping mapping) { return pollHeld(mapping, null, true); }

    /** A new member of an already held alternative/chord is not a fresh complete trigger. */
    public static boolean freshPress(KeyMapping mapping, String physicalKey) {
        return physicalKey != null && !pollHeld(mapping, physicalKey, false);
    }

    private static boolean pollHeld(KeyMapping mapping, String excludedKey, boolean keyboardOnly) {
        var mc = Minecraft.getInstance();
        if (!mc.isWindowActive()) return false;
        long window = mc.getWindow().getWindow();
        var binding = ACTIVE.get(mapping);
        if (binding == null) binding = BindingKeys.fromMapping(mapping, false);
        Predicate<String> nativeHeld = id -> {
            var key = BindingKeys.toKey(id);
            if (key.getType() == InputConstants.Type.MOUSE) {
                int button = key.getValue();
                return button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST
                        && GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
            }
            int code = key.getType() == InputConstants.Type.SCANCODE ? BindingKeys.keySymbol(key.getValue()) : key.getValue();
            return code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST
                    && GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
        };
        return keyboardOnly ? pollKeyboardHeld(binding, nativeHeld) : pollHeld(binding, nativeHeld, excludedKey);
    }

    static boolean pollKeyboardHeld(InputBinding binding, Predicate<String> nativeDown) {
        return pollHeld(binding, id -> BindingKeys.toKey(id).getType() != InputConstants.Type.MOUSE && nativeDown.test(id));
    }

    static boolean pollHeld(InputBinding binding, Predicate<String> nativeDown) {
        return pollHeld(binding, nativeDown, null);
    }

    static boolean pollHeld(InputBinding binding, Predicate<String> nativeDown, String excludedKey) {
        return binding.groups().stream().anyMatch(group -> !group.isEmpty() && group.stream().allMatch(member ->
                BindingKeys.aliases(member).stream().anyMatch(key -> !key.equals(excludedKey) && nativeDown.test(key))));
    }

    /** Extends NeoForge's lookup while retaining mappings private to mods outside vanilla Controls. */
    public static List<KeyMapping> lookup(InputConstants.Key key, boolean releasing, List<KeyMapping> original) {
        if (ACTIVE.isEmpty() || savingOptions) return original;
        String id = BindingKeys.fromKey(key);
        var result = new ArrayList<KeyMapping>();
        for (var mapping : original) if (!managed(mapping)) {
            result.add(mapping);
            if (!releasing && id != null && mapping.getKeyModifier() != KeyModifier.NONE) NATIVE_MODIFIED.add(id);
        }
        for (var mapping : ACTIVE.keySet()) {
            var binding = ACTIVE.get(mapping);
            if (releasing) {
                if (id != null && binding.groups().stream().anyMatch(group -> includes(group, id))
                        && !held(binding, PHYSICAL)) result.add(mapping);
            } else if (mapping.getKeyConflictContext().isActive() && matches(mapping, id)) result.add(mapping);
        }
        return result;
    }

    public static boolean repeatedToggle(KeyMapping mapping, boolean value) {
        var binding = ACTIVE.get(mapping);
        return value && binding != null && (eventAction == GLFW.GLFW_REPEAT
                || held(binding, before) && held(binding, PHYSICAL));
    }

    public static void restoreDown(KeyMapping mapping, boolean vanilla) {
        if (!managed(mapping)) { mapping.setDown(vanilla); return; }
        boolean down = physicallyHeld(mapping) && mapping.getKeyConflictContext().isActive();
        // Regaining pointer capture must not toggle crouch merely because its key is still held.
        if (!(mapping instanceof ToggleMode toggle) || !toggle.taczPubg$isToggleMode()) mapping.setDown(down);
    }

    /** Shared interaction routing compares complete triggers, not just the representative key. */
    public static boolean overlaps(KeyMapping first, KeyMapping second) {
        var a = ACTIVE.get(first); var b = ACTIVE.get(second);
        if (a == null || b == null) return first.getKey().equals(second.getKey()) && first.getKeyModifier() == second.getKeyModifier();
        return a.groups().stream().anyMatch(ga -> b.groups().stream().anyMatch(gb -> sameGroup(ga, gb)));
    }

    private static boolean sameGroup(List<String> a, List<String> b) {
        return a.size() == b.size() && a.stream().allMatch(ka -> b.stream().anyMatch(kb ->
                !Collections.disjoint(BindingKeys.aliases(ka), BindingKeys.aliases(kb))));
    }

    public static void releasePhysical() {
        BindingInputCompatibility.releaseHeld();
        PHYSICAL.clear(); before = Set.of(); eventKey = null; eventAction = GLFW.GLFW_RELEASE;
        PULSES.clear();
        NATIVE_MODIFIED.clear();
        for (var mapping : ACTIVE.keySet()) {
            ((KeyMappingAccess) mapping).taczPubg$setRawDown(false);
            ((KeyMappingAccess) mapping).taczPubg$setClicks(0);
        }
    }

    /** Scroll is an impulse. Native click consumers and native key-event consumers see one press. */
    public static boolean scroll(long window, double amount) {
        var mc = Minecraft.getInstance();
        if (!validWindow(window) || amount == 0 || mc.screen != null || mc.player == null || mc.getOverlay() != null) return false;
        String id = amount > 0 ? "ScrollUp" : "ScrollDown";
        record(id, GLFW.GLFW_PRESS);
        List<KeyMapping> matches = ACTIVE.keySet().stream()
                .filter(mapping -> mapping.getKeyConflictContext().isActive() && matches(mapping, id)).toList();
        if (matches.isEmpty()) { record(id, GLFW.GLFW_RELEASE); return false; }
        // Zoom owns the wheel while aiming; ordinary hotbar scrolling remains available otherwise.
        if (matches.size() == 1 && matches.getFirst().getName().equals("key.tacz.zoom.desc")
                && !com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator.fromLocalPlayer(mc.player).isAim()) {
            record(id, GLFW.GLFW_RELEASE); return false;
        }
        for (var mapping : matches) {
            PULSES.add(mapping);
            mapping.setDown(true);
            var access = (KeyMappingAccess) mapping;
            access.taczPubg$setClicks(access.taczPubg$getClicks() + 1);
        }
        int scan = amount > 0 ? SCROLL_UP_SCAN : SCROLL_DOWN_SCAN;
        int modifiers = modifiers();
        try {
            NeoForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_UNKNOWN, scan, GLFW.GLFW_PRESS, modifiers));
        } finally {
            record(id, GLFW.GLFW_RELEASE);
            NeoForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_UNKNOWN, scan, GLFW.GLFW_RELEASE, modifiers));
        }
        return true;
    }

    private static int modifiers() {
        int flags = 0;
        if (BindingKeys.aliases("ControlAny").stream().anyMatch(PHYSICAL::contains)) flags |= GLFW.GLFW_MOD_CONTROL;
        if (BindingKeys.aliases("ShiftAny").stream().anyMatch(PHYSICAL::contains)) flags |= GLFW.GLFW_MOD_SHIFT;
        if (BindingKeys.aliases("AltAny").stream().anyMatch(PHYSICAL::contains)) flags |= GLFW.GLFW_MOD_ALT;
        return flags;
    }

    private static void project(BindingProfile profile) {
        var profiles = BindingProfiles.get();
        for (var action : profiles.actions()) {
            var value = profiles.binding(profile, action.id());
            InputConstants.Key primary = InputConstants.UNKNOWN;
            KeyModifier modifier = KeyModifier.NONE;
            for (String id : value.keys()) {
                var key = BindingKeys.toKey(id);
                if (!key.equals(InputConstants.UNKNOWN)) primary = key;
                if (value.mode() == InputBinding.Mode.CHORD) {
                    if (id.equals("ControlAny")) modifier = KeyModifier.CONTROL;
                    if (id.equals("ShiftAny")) modifier = KeyModifier.SHIFT;
                    if (id.equals("AltAny")) modifier = KeyModifier.ALT;
                }
            }
            action.mapping().setKeyModifierAndCode(modifier, primary);
        }
        KeyMapping.resetMapping();
        var behavior = profiles.behavior(profile);
        var options = Minecraft.getInstance().options;
        options.toggleCrouch().set(behavior.toggleCrouch());
        options.toggleSprint().set(behavior.toggleSprint());
        options.autoJump().set(behavior.autoJump());
    }

    /** Vanilla options.txt remains a general-profile projection, including the three moved options. */
    public static void beforeOptionsSave() {
        if (savingOptions || applied == null || !BindingProfiles.get().initialized()) return;
        var options = Minecraft.getInstance().options;
        var actual = new BehaviorOptions(options.toggleCrouch().get(), options.toggleSprint().get(), options.autoJump().get());
        var profiles = BindingProfiles.get();
        // Accessibility also exposes these native options; its edits belong to the active profile.
        if (!actual.equals(profiles.behavior(applied))) profiles.saveBehavior(applied, actual);
        savingOptions = true;
        project(BindingProfile.GENERAL);
    }

    public static void afterOptionsSave() {
        if (!savingOptions) return;
        try { project(applied); } finally { savingOptions = false; }
    }
}
