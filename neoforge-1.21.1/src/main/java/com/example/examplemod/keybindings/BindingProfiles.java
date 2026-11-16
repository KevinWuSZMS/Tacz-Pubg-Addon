package com.example.examplemod.keybindings;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

/** Personal profiles contain only mappings exposed in Minecraft's normal key-binding list. */
public final class BindingProfiles {
    private static final BindingProfiles INSTANCE = new BindingProfiles();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, String> MATCH_OVERRIDES = Map.of(
            "key.togglePerspective", "V", "key.tacz.interact.desc", "F", "key.drop", "Backspace",
            "key.tacz.fire_select.desc", "B", "key.tacz.zoom.desc", "ScrollUp");
    private final EnumMap<BindingProfile, ProfileData> data = new EnumMap<>(BindingProfile.class);
    private final LinkedHashMap<String, BindingAction> catalog = new LinkedHashMap<>();
    private KeyMapping[] observedMappings;
    private boolean initialized;
    private long revision;
    private BehaviorOptions globalBehavior, globalBehaviorDefaults;

    private static final class ProfileData {
        final Map<String, InputBinding> bindings = new LinkedHashMap<>();
        final Map<String, InputBinding> defaults = new LinkedHashMap<>();
    }

    private BindingProfiles() { }
    public static BindingProfiles get() { return INSTANCE; }
    public boolean initialized() { return initialized; }
    public long revision() { return revision; }
    public BindingProfile activeProfile() { return MatchVitalsHud.active() ? BindingProfile.MATCH : BindingProfile.GENERAL; }
    public Path file() { return FMLPaths.CONFIGDIR.get().resolve("tacz_pubg-keybindings.json"); }

    /** Call after Options has loaded, before projecting any profile into the native KeyMappings. */
    public void ensureInitialized() {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) return;
        if (initialized && observedMappings == minecraft.options.keyMappings) return;
        boolean changed = false;
        if (!initialized) {
            var options = minecraft.options;
            BehaviorOptions current = new BehaviorOptions(options.toggleCrouch().get(),
                    options.toggleSprint().get(), options.autoJump().get());
            globalBehavior = current;
            globalBehaviorDefaults = current;
            for (BindingProfile profile : BindingProfile.values()) {
                ProfileData entry = new ProfileData();
                data.put(profile, entry);
            }
            load();
            initialized = true;
            changed = true;
        }
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            BindingAction previous = catalog.get(mapping.getName());
            if (previous != null && previous.mapping() == mapping) continue;
            BindingOwners.Owner owner = BindingOwners.owner(mapping);
            catalog.put(mapping.getName(), new BindingAction(mapping.getName(), owner.id(), owner.name(), mapping));
            InputBinding current = BindingKeys.fromMapping(mapping, false);
            InputBinding factory = BindingKeys.fromMapping(mapping, true);
            boolean matchAction = BindingProfileMigration.MATCH_ACTION_DEFAULTS.containsKey(mapping.getName());
            ProfileData general = data.get(BindingProfile.GENERAL), match = data.get(BindingProfile.MATCH);
            general.bindings.putIfAbsent(mapping.getName(), matchAction ? InputBinding.empty() : current);
            general.defaults.putIfAbsent(mapping.getName(), matchAction ? InputBinding.empty() : factory);
            InputBinding matchSeed = matchAction ? InputBinding.chord(BindingProfileMigration.MATCH_ACTION_DEFAULTS.get(mapping.getName()))
                    : mapping.getName().equals("key.tacz.crawl.desc") ? InputBinding.empty()
                    : mapping.getName().equals("key.inventory") ? new InputBinding(List.of("Tab", "I"), InputBinding.Mode.ANY)
                    : MATCH_OVERRIDES.containsKey(mapping.getName()) ? InputBinding.chord(MATCH_OVERRIDES.get(mapping.getName()))
                    : general.bindings.get(mapping.getName());
            match.bindings.putIfAbsent(mapping.getName(), matchSeed);
            match.defaults.putIfAbsent(mapping.getName(), matchSeed);
            changed = true;
        }
        var present = java.util.Arrays.stream(minecraft.options.keyMappings).map(KeyMapping::getName)
                .collect(java.util.stream.Collectors.toSet());
        changed |= catalog.keySet().removeIf(id -> !present.contains(id));
        observedMappings = minecraft.options.keyMappings;
        if (changed) {
            revision++;
            try { write(); }
            catch (IOException exception) { ExampleMod.LOGGER.error("Could not save keybinding profiles", exception); }
        }
    }

    public List<BindingAction> actions() {
        ensureInitialized();
        var result = new ArrayList<>(catalog.values());
        result.sort(Comparator.comparingInt((BindingAction action) -> action.modId().equals("minecraft") ? 0 : 1)
                .thenComparing(BindingAction::modName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(BindingAction::mapping));
        return Collections.unmodifiableList(result);
    }

    public BindingAction action(String id) { ensureInitialized(); return catalog.get(id); }
    public InputBinding binding(BindingProfile profile, String id) {
        ensureInitialized();
        return data.get(profile).bindings.getOrDefault(id, InputBinding.empty());
    }
    public InputBinding defaults(BindingProfile profile, String id) {
        ensureInitialized();
        return data.get(profile).defaults.getOrDefault(id, InputBinding.empty());
    }
    public BehaviorOptions behavior() {
        ensureInitialized();
        return globalBehavior;
    }
    public BehaviorOptions defaultBehavior() {
        ensureInitialized();
        return globalBehaviorDefaults;
    }
    /** Compatibility entry points: behavior is shared by both key profiles. */
    public BehaviorOptions behavior(BindingProfile ignored) { return behavior(); }
    public BehaviorOptions defaultBehavior(BindingProfile ignored) { return defaultBehavior(); }

    /** Commit all screen edits in one file replacement; no Options.save or runtime projection here. */
    public void saveBindings(BindingProfile profile, Map<String, InputBinding> changes) {
        save(profile, changes, behavior(profile));
    }

    /** Key edits and behavior settings form a single undoable, atomic profile transaction. */
    public void save(BindingProfile profile, Map<String, InputBinding> changes, BehaviorOptions behavior) {
        ensureInitialized();
        ProfileData target = data.get(profile);
        Map<String, InputBinding> before = new LinkedHashMap<>(target.bindings);
        BehaviorOptions behaviorBefore = globalBehavior;
        globalBehavior = java.util.Objects.requireNonNull(behavior);
        changes.forEach((id, binding) -> {
            if (catalog.containsKey(id) && binding != null) target.bindings.put(id, binding);
        });
        commit(() -> {
            target.bindings.clear(); target.bindings.putAll(before); globalBehavior = behaviorBefore;
        });
    }

    public void saveBehavior(BehaviorOptions behavior) {
        ensureInitialized();
        BehaviorOptions before = globalBehavior;
        globalBehavior = java.util.Objects.requireNonNull(behavior);
        commit(() -> globalBehavior = before);
    }
    public void saveBehavior(BindingProfile ignored, BehaviorOptions behavior) { saveBehavior(behavior); }

    public void reset(BindingProfile profile) {
        ensureInitialized();
        ProfileData target = data.get(profile);
        Map<String, InputBinding> before = new LinkedHashMap<>(target.bindings);
        BehaviorOptions behaviorBefore = globalBehavior;
        for (String id : catalog.keySet()) target.bindings.put(id, target.defaults.get(id));
        globalBehavior = globalBehaviorDefaults;
        commit(() -> {
            target.bindings.clear(); target.bindings.putAll(before); globalBehavior = behaviorBefore;
        });
    }

    private void commit(Runnable rollback) {
        try { write(); revision++; }
        catch (IOException exception) {
            rollback.run();
            throw new UncheckedIOException("Could not save keybinding profiles", exception);
        }
    }

    private void load() {
        Path path = file();
        if (!Files.exists(path)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("version") && root.get("version").getAsInt() < BindingProfileMigration.VERSION) {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".pre-v" + BindingProfileMigration.VERSION + "-" + System.currentTimeMillis() + ".bak"));
            }
            root = BindingProfileMigration.upgrade(root);
            globalBehavior = readBehavior(root.get("behavior"));
            globalBehaviorDefaults = readBehavior(root.get("behaviorDefaults"));
            JsonObject profiles = root.getAsJsonObject("profiles");
            for (BindingProfile profile : BindingProfile.values()) {
                if (!profiles.has(profile.name())) continue;
                JsonObject stored = profiles.getAsJsonObject(profile.name());
                ProfileData target = data.get(profile);
                readBindings(stored.getAsJsonObject("bindings"), target.bindings);
                readBindings(stored.getAsJsonObject("defaults"), target.defaults);
            }
        } catch (IOException | RuntimeException exception) {
            // Keep the user's complete original when recovering from a partial or externally edited file.
            try { Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis() + ".bak")); }
            catch (IOException backupFailure) { exception.addSuppressed(backupFailure); }
            ExampleMod.LOGGER.error("Could not read keybinding profiles; retaining readable values and initial settings", exception);
        }
    }

    private static BehaviorOptions readBehavior(JsonElement json) {
        JsonObject options = json.getAsJsonObject();
        return new BehaviorOptions(options.get("toggleCrouch").getAsBoolean(),
                options.get("toggleSprint").getAsBoolean(), options.get("autoJump").getAsBoolean());
    }

    private static void readBindings(JsonObject json, Map<String, InputBinding> destination) {
        if (json == null) return;
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonObject binding = entry.getValue().getAsJsonObject();
            List<String> keys = new ArrayList<>();
            for (JsonElement key : binding.getAsJsonArray("keys")) keys.add(key.getAsString());
            InputBinding.Mode mode = InputBinding.Mode.valueOf(binding.get("mode").getAsString());
            destination.put(entry.getKey(), new InputBinding(keys, mode));
        }
    }

    private void write() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", BindingProfileMigration.VERSION);
        root.add("behavior", JSON.toJsonTree(globalBehavior));
        root.add("behaviorDefaults", JSON.toJsonTree(globalBehaviorDefaults));
        JsonObject profiles = new JsonObject();
        for (BindingProfile profile : BindingProfile.values()) {
            ProfileData source = data.get(profile);
            JsonObject entry = new JsonObject();
            entry.add("bindings", JSON.toJsonTree(source.bindings));
            entry.add("defaults", JSON.toJsonTree(source.defaults));
            profiles.add(profile.name(), entry);
        }
        root.add("profiles", profiles);
        Path path = file();
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "tacz_pubg-keybindings-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
