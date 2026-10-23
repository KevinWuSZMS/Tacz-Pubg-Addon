package com.example.examplemod.commands;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.spawn.SpawnAreaSavedData;
import com.example.examplemod.zones.MapZoneSavedData;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Existing persistent identities with human-readable hints; never invents new identifiers. */
public final class PubgSuggestions {
    private PubgSuggestions() {}

    public static final SuggestionProvider<CommandSourceStack> MAPS = (context, builder) -> {
        if (!context.getSource().hasPermission(2)) return builder.buildFuture();
        for (var map : MapZoneSavedData.get(context.getSource().getServer()).maps()) {
            String id = Long.toString(map.id());
            if (id.startsWith(builder.getRemaining())) builder.suggest(id,
                    Texts.component(map.name() + " · " + map.dimension()));
        }
        return builder.buildFuture();
    };

    public static final SuggestionProvider<CommandSourceStack> REGIONS = (context, builder) -> {
        if (!context.getSource().hasPermission(2)) return builder.buildFuture();
        for (var region : SpawnAreaSavedData.get(context.getSource().getServer()).regions()) {
            String id = Long.toString(region.id());
            if (id.startsWith(builder.getRemaining())) builder.suggest(id,
                    Texts.component(region.dimension() + " · " + region.floors().size()
                            + Texts.tr("message.tacz_pubg.pubg_suggestions.m_rarity_factor") + region.rarity() + " · " + region.anchor().toShortString()));
        }
        return builder.buildFuture();
    };

    public static final SuggestionProvider<CommandSourceStack> BOMBING_ZONES = (context, builder) -> {
        if (!context.getSource().hasPermission(2)) return builder.buildFuture();
        for (var map : MapZoneSavedData.get(context.getSource().getServer()).maps())
            for (var zone : map.bombingZones()) {
                String id = Long.toString(zone.id());
                if (id.startsWith(builder.getRemaining()))
                    builder.suggest(id, Texts.component("#" + map.id() + " · " + map.name()));
            }
        return builder.buildFuture();
    };
}
