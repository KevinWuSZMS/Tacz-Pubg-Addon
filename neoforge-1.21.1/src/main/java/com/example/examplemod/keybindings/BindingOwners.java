package com.example.examplemod.keybindings;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

/** Captures the container that actually registers each vanilla-visible mapping. */
public final class BindingOwners {
    public record Owner(String id, String name) {}
    private static final Owner VANILLA = new Owner("minecraft", "Minecraft");
    private static final Map<KeyMapping, Owner> OWNERS = new IdentityHashMap<>();
    private static Set<KeyMapping> before = Collections.newSetFromMap(new IdentityHashMap<>());
    private BindingOwners() {}

    public static void markVanilla(Options options) {
        for (KeyMapping mapping : options.keyMappings) OWNERS.putIfAbsent(mapping, VANILLA);
    }
    public static void beforeContainer(Options options) {
        before.clear();
        Collections.addAll(before, options.keyMappings);
    }
    public static void afterContainer(Options options, ModContainer container) {
        Owner owner = new Owner(container.getModId(), container.getModInfo().getDisplayName());
        for (KeyMapping mapping : options.keyMappings) {
            if (!before.contains(mapping)) OWNERS.put(mapping, owner);
        }
    }

    public static Owner owner(KeyMapping mapping) {
        Owner owner = OWNERS.get(mapping);
        if (owner != null) return owner;
        // A mod may append mappings later. Resolve its registered namespace where available.
        String name = mapping.getName(), category = mapping.getCategory();
        return ModList.get().getMods().stream()
                .filter(mod -> !mod.getModId().equals("minecraft"))
                .filter(mod -> name.startsWith("key." + mod.getModId() + ".")
                        || category.startsWith("key.categories." + mod.getModId())
                        || category.startsWith("key.category." + mod.getModId()))
                .map(mod -> new Owner(mod.getModId(), mod.getDisplayName())).findFirst()
                .orElse(new Owner("other", "其他"));
    }
}
