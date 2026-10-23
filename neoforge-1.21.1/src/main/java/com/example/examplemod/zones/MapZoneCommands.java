package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.commands.MapCommandContext;
import com.example.examplemod.commands.PubgCommandHelp;
import com.example.examplemod.commands.PubgSuggestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Map authoring and protection controls. Match lifecycle remains in MatchCommands. */
public final class MapZoneCommands {
    private static final Map<MinecraftServer, Map<UUID, Long>> SELECTIONS = new WeakHashMap<>();

    private MapZoneCommands() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(MapZoneCommands::onLogout);
        gameBus.addListener(MapZoneCommands::onServerStopped);
    }

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        var createMap = Commands.literal("create").requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos1", Vec2Argument.vec2(false))
                        .then(Commands.argument("pos2", Vec2Argument.vec2(false))
                                .executes(context -> createMap(context.getSource(),
                                        Vec2Argument.getVec2(context, "pos1"), Vec2Argument.getVec2(context, "pos2"))))).build();
        var destroyMap = mapCommand("destroy", MapZoneCommands::destroyMap).build();
        var map = PubgCommandHelp.group("map")
                .then(createMap).then(destroyMap)
                .then(mapCommand("freeze", (source, id) -> freeze(source, id, true)))
                .then(mapCommand("unfreeze", (source, id) -> freeze(source, id, false)))
                .then(Commands.literal("select").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("id", LongArgumentType.longArg(1)).suggests(PubgSuggestions.MAPS)
                                .executes(context -> selectCommand(context.getSource(), LongArgumentType.getLong(context, "id")))))
                .then(Commands.literal("list").requires(source -> source.hasPermission(2))
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("info").requires(source -> source.hasPermission(2))
                        .executes(context -> info(context.getSource(), 0))
                        .then(Commands.argument("id", LongArgumentType.longArg(1)).suggests(PubgSuggestions.MAPS)
                                .executes(context -> info(context.getSource(), LongArgumentType.getLong(context, "id")))));
        root.then(map);
        var create = PubgCommandHelp.group("create").then(PubgCommandHelp.alias("map", createMap))
                .then(circleCommand("safezone", MapZoneCommands::createSafeZone))
                .then(circleCommand("bombingzone", MapZoneCommands::createBombingZone))
                .then(circleCommand("staging", MapZoneCommands::createStagingSpawn));
        create.then(Commands.literal("flightpath").requires(source -> source.hasPermission(2))
                .then(Commands.argument("start", Vec2Argument.vec2(false))
                        .then(Commands.argument("end", Vec2Argument.vec2(false))
                                .executes(context -> createFlightPath(context.getSource(),
                                        Vec2Argument.getVec2(context, "start"), Vec2Argument.getVec2(context, "end"), 0))
                                .then(mapArgument().executes(context -> createFlightPath(context.getSource(),
                                        Vec2Argument.getVec2(context, "start"), Vec2Argument.getVec2(context, "end"),
                                        LongArgumentType.getLong(context, "mapId")))))));
        root.then(create);
        var destroy = PubgCommandHelp.group("destroy").then(PubgCommandHelp.alias("map", destroyMap))
                .then(mapCommand("flightpath", MapZoneCommands::removeFlightPath))
                .then(mapCommand("safezone", MapZoneCommands::removeSafeZone))
                .then(mapCommand("staging", MapZoneCommands::removeStagingSpawn));
        destroy.then(Commands.literal("bombingzone").requires(source -> source.hasPermission(2))
                .then(Commands.argument("zoneId", LongArgumentType.longArg(1)).suggests(PubgSuggestions.BOMBING_ZONES)
                        .executes(context -> removeBombingZone(context.getSource(), LongArgumentType.getLong(context, "zoneId"), 0))
                        .then(mapArgument().executes(context -> removeBombingZone(context.getSource(),
                                LongArgumentType.getLong(context, "zoneId"), LongArgumentType.getLong(context, "mapId"))))));
        root.then(destroy);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Long> mapArgument() {
        return Commands.argument("mapId", LongArgumentType.longArg(1)).suggests(PubgSuggestions.MAPS);
    }
    private static LiteralArgumentBuilder<CommandSourceStack> mapCommand(String name, MapAction action) {
        return Commands.literal(name).requires(source -> source.hasPermission(2))
                .executes(context -> action.run(context.getSource(), 0))
                .then(mapArgument().executes(context -> action.run(context.getSource(), LongArgumentType.getLong(context, "mapId"))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> circleCommand(String name, CircleAction action) {
        return Commands.literal(name).requires(source -> source.hasPermission(2))
                .then(Commands.argument("center", Vec2Argument.vec2(false))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.000001))
                                .executes(context -> circle(context, action, 0))
                                .then(mapArgument().executes(context -> circle(context, action, LongArgumentType.getLong(context, "mapId"))))));
    }
    private static int circle(CommandContext<CommandSourceStack> context, CircleAction action, long mapId) throws CommandSyntaxException {
        return action.run(context.getSource(), Vec2Argument.getVec2(context, "center"), DoubleArgumentType.getDouble(context, "radius"), mapId);
    }
    @FunctionalInterface private interface MapAction { int run(CommandSourceStack source, long mapId); }
    @FunctionalInterface private interface CircleAction { int run(CommandSourceStack source, Vec2 center, double radius, long mapId); }

    /** The explicit selection is retained across dimension changes; it never guesses another map. */
    public static MapZoneModel.MapDefinition selectedMap(ServerPlayer player) {
        if (!player.hasPermissions(2)) return null;
        Map<UUID, Long> selections = SELECTIONS.get(player.server);
        if (selections == null) return null;
        Long id = selections.get(player.getUUID());
        if (id == null) return null;
        MapZoneModel.MapDefinition selected = MapZoneSavedData.get(player.server).map(id);
        if (selected == null) selections.remove(player.getUUID());
        return selected;
    }

    public static void select(ServerPlayer player, long id) {
        requirePermission(player);
        MapZoneSavedData data = availableData(player.server);
        if (id < 1 || data.map(id) == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_commands.map_does_not_exist_use_maplist_view_ids", id));
        SELECTIONS.computeIfAbsent(player.server, ignored -> new HashMap<>()).put(player.getUUID(), id);
    }

    private static int createMap(CommandSourceStack source, Vec2 first, Vec2 second) throws CommandSyntaxException {
        try {
            MapZoneSavedData data = availableData(source.getServer());
            var draft = new MapZoneModel.MapDefinition(0, MapDefaultNames.next(data.maps().stream()
                    .map(map -> new MapDefaultNames.Existing(map.id(), map.name())).toList()), source.getLevel().dimension().location(),
                    Math.min(first.x, second.x), Math.min(first.y, second.y),
                    Math.max(first.x, second.x), Math.max(first.y, second.y), null,
                    MapZoneModel.DEFAULT_BLUE_DAMAGE_PER_SECOND, List.of());
            var saved = data.saveMap(data.revision(), draft);
            if (source.getEntity() instanceof ServerPlayer player) select(player, saved.id());
            success(source, Texts.tr("message.tacz_pubg.map_zone_commands.created_selected_map_xz_bounds_full_map_automatic", saved.id(), bounds(saved), com.example.examplemod.backup.MapResetService.NOTICE));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            return failure(source, error);
        }
    }

    private static int createSafeZone(CommandSourceStack source, Vec2 center, double radius, long mapId) {
        try {
            var selected = MapCommandContext.resolveForCoordinates(source, mapId);
            if (selected.safeZone() != null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_commands.map_already_has_safe_zone_edit_config_createsafezone", selected.id()));
            MapZoneSavedData data = availableData(source.getServer());
            var saved = data.saveMap(data.revision(), copy(selected,
                    new MapZoneModel.Circle(center.x, center.y, radius), selected.bombingZones()));
            success(source, Texts.tr("message.tacz_pubg.map_zone_commands.created_safe_zone_map_center_radius_m_inside", saved.id(), point(center.x, center.y), number(radius), number(saved.blueDamagePerSecond())));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            return failure(source, error);
        }
    }

    private static int createBombingZone(CommandSourceStack source, Vec2 center, double radius, long mapId) {
        try {
            var selected = MapCommandContext.resolveForCoordinates(source, mapId);
            if (selected.bombingZones().size() >= MapZoneModel.MAX_BOMBING_ZONES) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_commands.each_map_allows_most_bombardment_zones", MapZoneModel.MAX_BOMBING_ZONES));
            }
            List<MapZoneModel.BombingZone> bombs = new ArrayList<>(selected.bombingZones());
            bombs.add(new MapZoneModel.BombingZone(0, new MapZoneModel.Circle(center.x, center.y, radius),
                    MapZoneModel.DEFAULT_MISSILES_PER_MINUTE, MapZoneModel.DEFAULT_MISSILE_DAMAGE,
                    MapZoneModel.DEFAULT_BLAST_RADIUS));
            MapZoneSavedData data = availableData(source.getServer());
            var saved = data.saveMap(data.revision(), copy(selected, selected.safeZone(), bombs));
            var created = saved.bombingZones().get(saved.bombingZones().size() - 1);
            success(source, Texts.tr("message.tacz_pubg.map_zone_commands.created_bombardment_zone_map_center_radius_m_impacts", saved.id(), created.id(), point(center.x, center.y), number(radius), created.missilesPerMinute(), number(created.missileDamage()), number(created.blastRadius())));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            return failure(source, error);
        }
    }

    private static MapZoneModel.MapDefinition copy(MapZoneModel.MapDefinition map, MapZoneModel.Circle safe,
            List<MapZoneModel.BombingZone> bombs) {
        return new MapZoneModel.MapDefinition(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(),
                map.maxX(), map.maxZ(), safe, map.blueDamagePerSecond(), bombs, map.flightPath(), map.visualStyle(), map.stagingSpawn());
    }

    private static int createFlightPath(CommandSourceStack source, Vec2 start, Vec2 end, long mapId) {
        try {
            var selected = MapCommandContext.resolveForCoordinates(source, mapId);
            if (selected.flightPath() != null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_commands.map_already_has_flight_route_edit_config_use", selected.id()));
            MapZoneSavedData data = availableData(source.getServer());
            var saved = data.saveMap(data.revision(), withFlightPath(selected,
                    new MapZoneModel.FlightPath(start.x, start.y, end.x, end.y)));
            success(source, Texts.tr("message.tacz_pubg.map_zone_commands.created_flight_route_map_display_configuration_only_aircraft", saved.id(), point(start.x, start.y), point(end.x, end.y)));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static int removeFlightPath(CommandSourceStack source, long mapId) {
        try {
            var selected = MapCommandContext.resolve(source, mapId);
            if (selected.flightPath() == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_commands.map_has_no_flight_route", selected.id()));
            MapZoneSavedData data = availableData(source.getServer());
            data.saveMap(data.revision(), withFlightPath(selected, null));
            success(source, Texts.tr("message.tacz_pubg.map_zone_commands.removed_flight_route_map", selected.id()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static MapZoneModel.MapDefinition withFlightPath(MapZoneModel.MapDefinition map, MapZoneModel.FlightPath path) {
        return new MapZoneModel.MapDefinition(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(),
                map.maxX(), map.maxZ(), map.safeZone(), map.blueDamagePerSecond(), map.bombingZones(), path, map.visualStyle(), map.stagingSpawn());
    }

    private static int createStagingSpawn(CommandSourceStack source, Vec2 center, double radius, long mapId) {
        try {
            var map = MapCommandContext.resolveForCoordinates(source, mapId);
            if (map.stagingSpawn() != null) throw new IllegalStateException(Texts.tr("command.tacz_pubg.map.staging_exists", map.id()));
            var data = availableData(source.getServer());
            data.saveMap(data.revision(), withStaging(map, new MapZoneModel.Circle(center.x, center.y, radius)));
            success(source, Texts.tr("command.tacz_pubg.map.staging_created", map.id(), point(center.x, center.y), number(radius)));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static MapZoneModel.MapDefinition withStaging(MapZoneModel.MapDefinition map, MapZoneModel.Circle staging) {
        return new MapZoneModel.MapDefinition(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(),
                map.maxX(), map.maxZ(), map.safeZone(), map.blueDamagePerSecond(), map.bombingZones(),
                map.flightPath(), map.visualStyle(), staging);
    }

    private static int destroyMap(CommandSourceStack source, long mapId) {
        try {
            var map = MapCommandContext.resolve(source, mapId);
            var data = availableData(source.getServer());
            data.deleteMap(data.revision(), map.id());
            success(source, Texts.tr("command.tacz_pubg.map.deleted", map.id(), map.name()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static int removeSafeZone(CommandSourceStack source, long mapId) {
        try {
            var map = MapCommandContext.resolve(source, mapId);
            if (map.safeZone() == null) throw new IllegalStateException(Texts.tr("command.tacz_pubg.map.safe_missing", map.id()));
            var data = availableData(source.getServer());
            data.saveMap(data.revision(), copy(map, null, map.bombingZones()));
            success(source, Texts.tr("command.tacz_pubg.map.safe_removed", map.id()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static int removeBombingZone(CommandSourceStack source, long zoneId, long mapId) {
        try {
            var map = MapCommandContext.resolve(source, mapId);
            var bombs = map.bombingZones().stream().filter(zone -> zone.id() != zoneId).toList();
            if (bombs.size() == map.bombingZones().size())
                throw new IllegalArgumentException(Texts.tr("command.tacz_pubg.map.bombing_missing", zoneId, map.id()));
            var data = availableData(source.getServer());
            data.saveMap(data.revision(), copy(map, map.safeZone(), bombs));
            success(source, Texts.tr("command.tacz_pubg.map.bombing_removed", zoneId, map.id()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static int removeStagingSpawn(CommandSourceStack source, long mapId) {
        try {
            var map = MapCommandContext.resolve(source, mapId);
            if (map.stagingSpawn() == null) throw new IllegalStateException(Texts.tr("command.tacz_pubg.map.staging_missing", map.id()));
            var data = availableData(source.getServer());
            data.saveMap(data.revision(), withStaging(map, null));
            success(source, Texts.tr("command.tacz_pubg.map.staging_removed", map.id()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static int freeze(CommandSourceStack source, long mapId, boolean frozen) {
        try {
            var map = MapCommandContext.resolve(source, mapId);
            if (frozen) com.example.examplemod.backup.MapResetService.freeze(source.getServer(), map.id());
            else com.example.examplemod.backup.MapResetService.unfreeze(source.getServer(), map.id());
            source.sendSuccess(() -> Texts.of(frozen ? "journal.freeze_started" : "journal.unfrozen", map.id()), true);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error); }
    }

    private static int selectCommand(CommandSourceStack source, long id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        try {
            select(player, id);
            success(source, Texts.tr("message.tacz_pubg.map_zone_commands.selected_map_zone_creation_checks_current_dimension", id, selectedMap(player).name()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            return failure(source, error);
        }
    }

    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        try {
            List<MapZoneModel.MapDefinition> maps = availableData(source.getServer()).maps();
            if (maps.isEmpty()) success(source, Texts.tr("message.tacz_pubg.map_zone_commands.world_has_no_maps_yet_use_createmap_pos"));
            for (var map : maps) success(source, Texts.tr("message.tacz_pubg.map_zone_commands.safe_zone_bombardment_zones_route", map.id(), map.name(), map.dimension(), bounds(map), (map.safeZone() == null ? Texts.tr("message.tacz_pubg.map_zone_commands.undefined") : Texts.tr("message.tacz_pubg.map_zone_commands.defined")), map.bombingZones().size(), (map.flightPath() == null ? Texts.tr("message.tacz_pubg.map_zone_commands.undefined") : Texts.tr("message.tacz_pubg.map_zone_commands.defined"))));
            return maps.size();
        } catch (IllegalArgumentException | IllegalStateException error) {
            return failure(source, error);
        }
    }

    private static int info(CommandSourceStack source, long id) throws CommandSyntaxException {
        try {
            var map = MapCommandContext.resolve(source, id);
            success(source, Texts.tr("message.tacz_pubg.map_zone_commands.map_dimension_xz_bounds_area_m", map.id(), map.name(), map.dimension(), bounds(map), number((map.maxX() - map.minX()) * (map.maxZ() - map.minZ()))));
            if (map.safeZone() == null) success(source, Texts.tr("message.tacz_pubg.map_zone_commands.safe_zone_undefined_blue_zone_cannot_determined_yet"));
            else success(source, Texts.tr("message.tacz_pubg.map_zone_commands.safe_zone_center_radius_m_blue_zone_damage", point(map.safeZone().centerX(), map.safeZone().centerZ()), number(map.safeZone().radius()), number(map.blueDamagePerSecond())));
            for (var bomb : map.bombingZones()) success(source, Texts.tr("message.tacz_pubg.map_zone_commands.bombardment_zone_center_radius_m_impacts_minute_damage", bomb.id(), point(bomb.area().centerX(), bomb.area().centerZ()), number(bomb.area().radius()), bomb.missilesPerMinute(), number(bomb.missileDamage()), number(bomb.blastRadius())));
            if (map.flightPath() != null) success(source, Texts.tr("message.tacz_pubg.map_zone_commands.flight_route", point(map.flightPath().startX(), map.flightPath().startZ()), point(map.flightPath().endX(), map.flightPath().endZ())));
            if (map.stagingSpawn() != null) success(source, Texts.tr("command.tacz_pubg.map.staging_info",
                    point(map.stagingSpawn().centerX(), map.stagingSpawn().centerZ()), number(map.stagingSpawn().radius())));
            success(source, com.example.examplemod.backup.MapResetService.description(source.getServer(), map.id()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            return failure(source, error);
        }
    }

    private static MapZoneSavedData availableData(MinecraftServer server) {
        MapZoneSavedData data = MapZoneSavedData.get(server);
        if (!data.loadError().isEmpty()) throw new IllegalStateException(data.loadError());
        return data;
    }

    private static void requirePermission(ServerPlayer player) {
        if (!player.hasPermissions(2)) throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_zone_commands.permission_level_required_edit_maps_zones"));
    }

    private static int failure(CommandSourceStack source, RuntimeException error) {
        source.sendFailure(Texts.of("message.tacz_pubg.map_zone_commands.map_operation_incomplete", error.getMessage()));
        return 0;
    }

    private static void success(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Texts.component(text), false);
    }

    private static String bounds(MapZoneModel.MapDefinition map) {
        return point(map.minX(), map.minZ()) + Texts.tr("message.tacz_pubg.map_zone_commands.bounds") + point(map.maxX(), map.maxZ());
    }

    private static String point(double x, double z) { return "(" + number(x) + ", " + number(z) + ")"; }
    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Map<UUID, Long> selected = SELECTIONS.get(player.server);
            if (selected != null) selected.remove(player.getUUID());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) { SELECTIONS.remove(event.getServer()); }
}
