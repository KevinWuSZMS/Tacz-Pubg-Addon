package com.example.examplemod.spawn;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.lootconfig.LootConfigCommands;
import com.example.examplemod.lootspawn.LootRespawnService;
import com.example.examplemod.zones.MapZoneCommands;
import com.example.examplemod.backup.MapJournalCommands;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread authoring operations. Vanilla block interactions supply selection coordinates. */
public final class SpawnAreaService {
    public static final int MAX_SELECTION_FLOORS = 4_096;
    private static final Map<UUID, AuthorState> AUTHORS = new HashMap<>();

    private SpawnAreaService() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(SpawnAreaService::registerCommands);
        gameBus.addListener(SpawnAreaService::onServerTick);
        gameBus.addListener(SpawnAreaService::onLogout);
        gameBus.addListener(SpawnAreaService::onChangedDimension);
        gameBus.addListener(SpawnAreaService::onRespawn);
        gameBus.addListener(SpawnAreaService::onServerStopped);
    }

    public static boolean holdsTool(Player player) {
        return player.getMainHandItem().getItem() instanceof ItemSpawnAreaStick
                || player.getOffhandItem().getItem() instanceof ItemSpawnAreaStick;
    }

    public static com.example.examplemod.lootconfig.LootTier heldTier(Player player) {
        if (player.getMainHandItem().getItem() instanceof ItemSpawnAreaStick stick) return stick.tier();
        if (player.getOffhandItem().getItem() instanceof ItemSpawnAreaStick stick) return stick.tier();
        return com.example.examplemod.lootconfig.LootTier.HIGH;
    }

    /** Safe, read-only selection for explicit author commands such as itemRespawn. */
    @Nullable
    public static List<BlockPos> currentSelection(ServerPlayer player) {
        if (!canEdit(player)) return null;
        List<BlockPos> selection = validatedSelection(player);
        return selection == null ? null : List.copyOf(selection);
    }

    private static boolean insideMap(ServerPlayer player, BlockPos first, BlockPos second) {
        boolean inside = MapSpawnBounds.containedByOneMap(
                com.example.examplemod.zones.MapZoneSavedData.get(player.server).maps(),
                player.level().dimension().location(), first, second);
        if (!inside) message(player, Texts.tr("message.tacz_pubg.spawn_area_service.inside_map_required"));
        return inside;
    }
    public static boolean selectFirst(ServerPlayer player, BlockPos floorPos) {
        if (!canSelect(player, floorPos)) {
            return false;
        }
        if (!insideMap(player, floorPos, floorPos)) return false;
        AuthorState state = state(player);
        state.dimension = player.level().dimension().location();
        state.first = floorPos.immutable();
        state.second = null;
        state.selectionRevision++;
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.first_floor_point_set_right_click_floor_same", coordinates(floorPos)));
        sync(player, true);
        return true;
    }

    public static boolean selectSecond(ServerPlayer player, BlockPos floorPos) {
        if (!canSelect(player, floorPos)) {
            return false;
        }
        AuthorState state = state(player);
        alignDimension(player, state);
        if (state.first == null) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.left_click_floor_first_point_before_right_clicking"));
            return false;
        }
        if (state.first.getY() != floorPos.getY()) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.both_points_same_height_first_y_current_y", state.first.getY(), floorPos.getY()));
            return false;
        }
        if (!insideMap(player, state.first, floorPos)) return false;
        long area = rectangleSize(state.first, floorPos);
        if (area > MAX_SELECTION_FLOORS) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.selection_contains_blocks_limit_per_operation_original_selection", area, MAX_SELECTION_FLOORS));
            return false;
        }
        state.second = floorPos.immutable();
        state.selectionRevision++;
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.second_floor_point_set_block_rectangle_not_saved", coordinates(floorPos), area));
        sync(player, true);
        return true;
    }

    private static boolean canSelect(ServerPlayer player, BlockPos pos) {
        if (!canEdit(player)) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(new AABB(pos))) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.position_outside_world_border_build_height"));
            return false;
        }
        if (!player.canInteractWithBlock(pos, 1.0)) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.floor_too_far_away_move_closer_before_selecting"));
            return false;
        }
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.floor_s_chunk_not_loaded_unloaded_chunks_not"));
            return false;
        }
        return true;
    }

    private static boolean canEdit(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.permission_level_required_edit_loot_spawn_regions"));
            return false;
        }
        if (!holdsTool(player)) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.hold_loot_region_edit_wand_either_hand"));
            return false;
        }
        return true;
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("pubgdebug")
                .requires(source -> source.hasPermission(2));
        var area = Commands.literal("area")
                .executes(context -> info(context.getSource().getPlayerOrException()))
                .then(Commands.literal("add")
                        .executes(context -> editSelection(context.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("remove")
                        .executes(context -> editSelection(context.getSource().getPlayerOrException(), false)))
                .then(Commands.literal("clear")
                        .executes(context -> explainClear(context.getSource()))
                        .then(Commands.literal("confirm")
                                .executes(context -> clearDimension(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource().getPlayerOrException()))
                        .then(Commands.argument("regionId", LongArgumentType.longArg(1))
                                .suggests(com.example.examplemod.commands.PubgSuggestions.REGIONS)
                                .executes(context -> regionInfo(context.getSource(),
                                        LongArgumentType.getLong(context, "regionId")))))
                .then(Commands.literal("list")
                        .executes(context -> listRegions(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listRegions(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("clearSelection")
                        .executes(context -> clearSelection(context.getSource().getPlayerOrException()))).build();
        root.then(com.example.examplemod.commands.PubgCommandHelp.group("loot")
                .then(Commands.literal("show")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setVisibility(context.getSource().getPlayerOrException(),
                                        BoolArgumentType.getBool(context, "enabled")))))
                .then(area));
        root.then(com.example.examplemod.commands.PubgCommandHelp.group("create")
                .then(com.example.examplemod.commands.PubgCommandHelp.alias("loot", area.getChild("add"))));
        root.then(com.example.examplemod.commands.PubgCommandHelp.group("destroy")
                .then(com.example.examplemod.commands.PubgCommandHelp.alias("loot", area.getChild("remove"))));
        LootConfigCommands.attach(root);
        LootRespawnService.attach(root);
        MapZoneCommands.attach(root);
        com.example.examplemod.zones.match.MatchCommands.attach(root);
        MapJournalCommands.attach(root);
        com.example.examplemod.commands.PubgCommandHelp.attach(root);
        event.getDispatcher().register(root);
    }

    private static int setVisibility(ServerPlayer player, boolean visible) {
        if (!player.hasPermissions(2)) {
            return 0;
        }
        state(player).visible = visible;
        SpawnAreaSavedData.get(player.server).setShowPreference(player.getUUID(), visible);
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.your_personal_loot_region_display_now_holding_edit", (visible ? Texts.tr("screen.tacz_pubg.common.enabled") : Texts.tr("screen.tacz_pubg.common.disabled"))));
        sync(player, true);
        return 1;
    }

    private static int editSelection(ServerPlayer player, boolean adding) {
        if (!canEdit(player)) {
            return 0;
        }
        List<BlockPos> selection = validatedSelection(player);
        if (selection == null) {
            return 0;
        }
        ResourceLocation dimension = player.level().dimension().location();
        SpawnAreaSavedData data = SpawnAreaSavedData.get(player.server);
        if (!adding) {
            int removed = data.remove(dimension, selection);
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.removed_blocks_dimension_selected_blocks_were_not_confirmed", removed, (selection.size() - removed)));
            resetSelection(state(player));
            sync(player, true);
            return removed;
        }
        var author = state(player);
        if (!insideMap(player, author.first, author.second)) return 0;
        List<BlockPos> valid = new ArrayList<>();
        int existing = 0;
        int invalid = 0;
        for (BlockPos pos : selection) {
            if (data.contains(dimension, pos)) {
                existing++;
            } else if (SpawnFloorGeometry.isValidFloor(player.serverLevel(), pos)) {
                valid.add(pos);
            } else {
                invalid++;
            }
        }
        if ((long) data.count(dimension) + valid.size() > SpawnAreaSavedData.MAX_FLOORS_PER_DIMENSION
                || (long) data.totalCount() + valid.size() > SpawnAreaSavedData.MAX_TOTAL_FLOORS) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.confirmation_would_exceed_limits_blocks_per_dimension_per", SpawnAreaSavedData.MAX_FLOORS_PER_DIMENSION, SpawnAreaSavedData.MAX_TOTAL_FLOORS));
            return 0;
        }
        int added = data.add(dimension, valid, heldTier(player));
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.confirmed_new_floor_blocks_skipped_invalid_floors_clearance", added, invalid, existing, data.count(dimension)));
        List<Long> connected = selection.stream().map(pos -> data.regionAt(dimension, pos))
                .filter(java.util.Objects::nonNull).map(SpawnAreaSavedData.Region::id).distinct().sorted().toList();
        if (!connected.isEmpty()) message(player, Texts.tr("message.tacz_pubg.spawn_area_service.region_ids_selection_adjacent_areas_connections_within_vertical", connected.stream().limit(8).toList(), (connected.size() > 8 ? Texts.tr("message.tacz_pubg.spawn_area_service.others_regions_total", connected.size()) : "")));
        // A successful commit ends this edit, even when every approved cell already existed.
        // Clear both corners BEFORE sending the snapshot, so no stale white preview survives.
        resetSelection(state(player));
        sync(player, true);
        return added;
    }

    /** Rejects the entire operation before geometry reads if any selected chunk is absent. */
    @Nullable
    private static List<BlockPos> validatedSelection(ServerPlayer player) {
        AuthorState state = state(player);
        alignDimension(player, state);
        if (state.first == null || state.second == null) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.incomplete_selection_left_click_first_point_right_click"));
            return null;
        }
        if (state.first.getY() != state.second.getY()
                || rectangleSize(state.first, state.second) > MAX_SELECTION_FLOORS) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.selection_horizontal_rectangle_one_height_no_more_than", MAX_SELECTION_FLOORS));
            return null;
        }
        int minX = Math.min(state.first.getX(), state.second.getX());
        int maxX = Math.max(state.first.getX(), state.second.getX());
        int minZ = Math.min(state.first.getZ(), state.second.getZ());
        int maxZ = Math.max(state.first.getZ(), state.second.getZ());
        int y = state.first.getY();
        ServerLevel level = player.serverLevel();
        if (level.isOutsideBuildHeight(y)
                || !level.getWorldBorder().isWithinBounds(new AABB(minX, y, minZ, maxX + 1.0, y + 1.0, maxZ + 1.0))) {
            message(player, Texts.tr("message.tacz_pubg.spawn_area_service.selection_exceeds_current_world_border_build_height_no"));
            return null;
        }
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) {
                    message(player, Texts.tr("message.tacz_pubg.spawn_area_service.selection_contains_unloaded_chunk_entire_operation_was_cancelled", x, z));
                    return null;
                }
            }
        }
        List<BlockPos> positions = new ArrayList<>((int) rectangleSize(state.first, state.second));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                positions.add(new BlockPos(x, y, z));
            }
        }
        return positions;
    }

    private static int clearDimension(ServerPlayer player) {
        if (!canEdit(player)) {
            return 0;
        }
        int removed = SpawnAreaSavedData.get(player.server).clear(player.level().dimension().location());
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.cleared_all_confirmed_blocks_current_dimension_other_dimensions", removed));
        sync(player, true);
        return removed;
    }

    private static int explainClear(CommandSourceStack source) throws CommandSyntaxException {
        message(source.getPlayerOrException(), Texts.tr("message.tacz_pubg.spawn_area_service.clearing_deletes_every_confirmed_region_dimension_confirm_run"));
        return 0;
    }

    private static int info(ServerPlayer player) {
        SpawnAreaSavedData data = SpawnAreaSavedData.get(player.server);
        ResourceLocation dimension = player.level().dimension().location();
        AuthorState state = state(player);
        alignDimension(player, state);
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.dimension_confirmed_blocks_persistent_regions_blocks_world_wide", dimension, data.count(dimension), data.regions(dimension).size(), data.totalCount(), state.visible, holdsTool(player)));
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.point_point_maximum_blocks_per_operation_final_regions", coordinates(state.first), coordinates(state.second), (state.first != null && state.second != null
                        ? Texts.tr("message.tacz_pubg.spawn_area_service.horizontal_rectangle_blocks_not_submitted", rectangleSize(state.first, state.second)) : "。"), MAX_SELECTION_FLOORS));
        if (state.first != null) {
            SpawnAreaSavedData.Region region = data.regionAt(dimension, state.first);
            if (region != null) message(player, describeRegion(region));
        }
        return data.count(dimension);
    }

    private static int regionInfo(CommandSourceStack source, long id) {
        SpawnAreaSavedData.Region region = SpawnAreaSavedData.get(source.getServer()).region(id);
        if (region == null) {
            source.sendFailure(Texts.of("message.tacz_pubg.spawn_area_service.region_does_not_exist_use_pubgdebug_area_list", id));
            return 0;
        }
        source.sendSuccess(() -> Texts.component(describeRegion(region)), false);
        return region.area();
    }

    private static int listRegions(CommandSourceStack source, int page) {
        List<SpawnAreaSavedData.Region> regions = SpawnAreaSavedData.get(source.getServer()).regions();
        int pages = Math.max(1, (regions.size() + 7) / 8);
        if (page > pages) {
            source.sendFailure(Texts.of("message.tacz_pubg.spawn_area_service.region_list_has_pages", pages));
            return 0;
        }
        source.sendSuccess(() -> Texts.of("message.tacz_pubg.spawn_area_service.loot_regions_world_wide_page", regions.size(), page, pages), false);
        for (int i = (page - 1) * 8; i < Math.min(page * 8, regions.size()); i++) {
            String description = describeRegion(regions.get(i));
            source.sendSuccess(() -> Texts.component(description), false);
        }
        return regions.size();
    }

    private static String describeRegion(SpawnAreaSavedData.Region region) {
        return Texts.tr("message.tacz_pubg.spawn_area_service.region_area_m_rarity_bounds_actual_shape_follows", region.id(), region.dimension(), region.area(), region.rarity(), coordinates(region.min()), coordinates(region.max()));
    }

    private static int clearSelection(ServerPlayer player) {
        AuthorState state = state(player);
        resetSelection(state);
        message(player, Texts.tr("message.tacz_pubg.spawn_area_service.cleared_your_temporary_selection_confirmed_regions_unchanged"));
        sync(player, true);
        return 1;
    }

    private static void resetSelection(AuthorState state) {
        state.first = null;
        state.second = null;
        state.selectionRevision++;
    }

    /** Authoring shortcuts never receive client supplied floor coordinates. */
    public static void handleShortcut(ServerPlayer player, SpawnAreaPayloads.ToolAction action) {
        handleShortcut(player, action, null);
    }
    public static void handleShortcut(ServerPlayer player, SpawnAreaPayloads.ToolAction action, @Nullable ResourceLocation previewDimension) {
        if (action == SpawnAreaPayloads.ToolAction.MAP_PREVIEW || action == SpawnAreaPayloads.ToolAction.END_MAP_PREVIEW) {
            if (!player.hasPermissions(2)) return;
            var author = state(player);
            author.mapPreview = action == SpawnAreaPayloads.ToolAction.MAP_PREVIEW;
            author.previewDimension = author.mapPreview ? previewDimension == null ? player.level().dimension().location() : previewDimension : null;
            sync(player, true); return;
        }
        if (!canEdit(player)) return;
        switch (action) {
            case CLEAR -> clearSelection(player);
            case SAVE -> editSelection(player, true);
            case ERASE -> editSelection(player, false);
            case MAP_PREVIEW, END_MAP_PREVIEW -> {}
        }
    }

    private static AuthorState state(ServerPlayer player) {
        return AUTHORS.computeIfAbsent(player.getUUID(), ignored -> {
            AuthorState state = new AuthorState();
            state.dimension = player.level().dimension().location();
            state.visible = SpawnAreaSavedData.get(player.server).showPreference(player.getUUID());
            return state;
        });
    }

    private static void alignDimension(ServerPlayer player, AuthorState state) {
        ResourceLocation current = player.level().dimension().location();
        if (!current.equals(state.dimension)) {
            state.dimension = current;
            state.first = null;
            state.second = null;
            state.selectionRevision++;
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.hasPermissions(2) || AUTHORS.containsKey(player.getUUID())) {
                sync(player, false);
            }
        }
    }

    private static void sync(ServerPlayer player, boolean force) {
        AuthorState state = state(player);
        alignDimension(player, state);
        boolean authorized = player.hasPermissions(2);
        boolean visible = authorized && state.visible;
        boolean subscribed = authorized && (visible || holdsTool(player) || state.mapPreview);
        boolean fullSnapshot = subscribed && (holdsTool(player) || state.mapPreview);
        ResourceLocation dimension = subscribed && state.mapPreview && state.previewDimension != null
                ? state.previewDimension : player.level().dimension().location();
        ChunkPos center = player.chunkPosition();
        SpawnAreaSavedData data = SpawnAreaSavedData.get(player.server);
        var config = com.example.examplemod.lootconfig.LootConfigStore.get(player.serverLevel());
        var zoneSettings = config.document().general().zoneSettings();
        if (!force && dimension.equals(state.lastDimension) && (fullSnapshot || center.equals(state.lastChunk))
                && data.revision() == state.lastDataRevision && config.revision() == state.lastConfigRevision && state.selectionRevision == state.lastSelectionRevision
                && visible == state.lastVisible && subscribed == state.lastSubscribed
                && fullSnapshot == state.lastFullSnapshot && heldTier(player) == state.lastTier) {
            return;
        }
        List<BlockPos> nearby = subscribed
                ? fullSnapshot ? data.floors(dimension).stream().sorted(SpawnRegionTopology.POSITION_ORDER).toList()
                        : data.nearby(dimension, center, SpawnAreaPayloads.SYNC_CHUNK_RADIUS) : List.of();
        Map<Long, com.example.examplemod.lootconfig.LootTier> tiers = new HashMap<>();
        for (BlockPos floor : nearby) {
            var region = data.regionAt(dimension, floor);
            if (region != null) tiers.put(floor.asLong(), region.tier());
        }
        PacketDistributor.sendToPlayer(player, new SpawnAreaPayloads.Snapshot(dimension, visible,
                data.revision(), nearby, subscribed && dimension.equals(state.dimension) ? state.first : null,
                subscribed && dimension.equals(state.dimension) ? state.second : null, false, tiers, heldTier(player), zoneSettings.visualStyle().lootTierRgb(),
                zoneSettings.visualStyle().lootOverlapBlackLayers(), zoneSettings.useGlobalVisualStyle()));
        state.lastDimension = dimension;
        state.lastChunk = center;
        state.lastDataRevision = data.revision();
        state.lastSelectionRevision = state.selectionRevision;
        state.lastVisible = visible;
        state.lastSubscribed = subscribed;
        state.lastFullSnapshot = fullSnapshot;
        state.lastTier = heldTier(player);
        state.lastConfigRevision = config.revision();
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        AUTHORS.remove(event.getEntity().getUUID());
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AuthorState state = state(player);
            alignDimension(player, state);
            sync(player, true);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        AUTHORS.clear();
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.hasPermissions(2)) {
            sync(player, true);
        }
    }

    private static long rectangleSize(BlockPos first, BlockPos second) {
        return (Math.abs((long) first.getX() - second.getX()) + 1L)
                * (Math.abs((long) first.getZ() - second.getZ()) + 1L);
    }

    private static String coordinates(@Nullable BlockPos pos) {
        return pos == null ? Texts.tr("message.tacz_pubg.spawn_area_service.not_set") : "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Texts.component(text));
    }

    private static final class AuthorState {
        private ResourceLocation dimension;
        private boolean visible;
        private boolean mapPreview;
        private ResourceLocation previewDimension;
        private BlockPos first;
        private BlockPos second;
        private long selectionRevision;
        private ResourceLocation lastDimension;
        private ChunkPos lastChunk;
        private long lastDataRevision = Long.MIN_VALUE;
        private long lastConfigRevision = Long.MIN_VALUE;
        private long lastSelectionRevision = Long.MIN_VALUE;
        private boolean lastVisible;
        private boolean lastSubscribed;
        private boolean lastFullSnapshot;
        private com.example.examplemod.lootconfig.LootTier lastTier;
    }
}
