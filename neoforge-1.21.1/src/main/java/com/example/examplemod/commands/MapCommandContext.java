package com.example.examplemod.commands;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

/** Command defaults follow the caller's actual location, independently of the editor selection. */
public final class MapCommandContext {
    private MapCommandContext() {}

    public static MapDefinition resolve(CommandSourceStack source, long explicitId) {
        if (!source.hasPermission(2))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_commands.command_requires_op_permission_level"));
        if (explicitId == 0 && !(source.getEntity() instanceof ServerPlayer))
            throw new IllegalStateException(Texts.tr("command.tacz_pubg.context.console_id"));
        var data = MapZoneSavedData.get(source.getServer());
        if (!data.loadError().isEmpty()) throw new IllegalStateException(data.loadError());
        if (explicitId > 0) {
            var map = data.map(explicitId);
            if (map == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_commands.map_does_not_exist", explicitId));
            return map;
        }
        var player = (ServerPlayer) source.getEntity();
        var candidates = containing(data.maps(), player.serverLevel().dimension().location(), player.getX(), player.getZ());
        if (candidates.isEmpty()) throw new IllegalStateException(Texts.tr("command.tacz_pubg.context.no_map"));
        var map = candidates.getFirst();
        if (candidates.size() > 1) source.sendSuccess(() -> Texts.of("command.tacz_pubg.context.overlap",
                candidates.size(), map.id(), map.name()), false);
        return map;
    }

    public static MapDefinition resolveForCoordinates(CommandSourceStack source, long explicitId) {
        var map = resolve(source, explicitId);
        if (!map.dimension().equals(source.getLevel().dimension().location()))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_zone_commands.selected_map_belongs_different_dimension_go_there_explicitly",
                    map.id(), map.dimension()));
        return map;
    }

    /**
     * IDs are assigned as max(surviving IDs) + 1 by MapZoneSavedData, including after deletion.
     * Consequently every surviving higher ID is newer. This is not a lifetime high-water mark.
     */
    public static List<MapDefinition> containing(List<MapDefinition> maps, ResourceLocation dimension, double x, double z) {
        return maps.stream().filter(map -> map.dimension().equals(dimension) && map.contains(x, z))
                .sorted(Comparator.comparingLong(MapDefinition::id).reversed()).toList();
    }
}
