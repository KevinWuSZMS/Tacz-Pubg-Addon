package com.example.examplemod.keybindings;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.resources.language.I18n;

/** Name/category resolve on demand so changing the game language also changes search results. */
public record BindingAction(String id, String modId, String modName, KeyMapping mapping) {
    public String name() { return mapping.getDisplayName().getString(); }
    public String category() { return I18n.get(mapping.getCategory()); }
}
