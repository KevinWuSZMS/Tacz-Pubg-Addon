package com.example.examplemod.zones.match.flight;

import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.bot.MatchBotService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-owned takeoff, seat order and one-flight descent. No persistent flight abilities are granted. */
public final class MatchFlightService {
    private static final String RECOVERY = "tacz_pubg_flight_recovery";
    private static final Map<MinecraftServer, Map<UUID, Flight>> FLIGHTS = new WeakHashMap<>();
    private static boolean releasingPassenger;
    private static final class Rider {
        boolean onboard = true;
        boolean parachute;
        boolean canDeploy;
        double jumpHeight;
        FlightPayloads.State sent;
    }
    private static final class Flight {
        final MatchEngine game;
        final Map<UUID, Rider> riders = new LinkedHashMap<>();
        TransportFormationEntity carrier;
        boolean launched;
        double height;
        Flight(MatchEngine game) { this.game = game; }
    }
    private MatchFlightService() {}

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST, (EntityMountEvent event) -> {
            // Client passenger snapshots eject and rebuild their list; never cancel that synchronization.
            // Removed riders/carriers must also be able to detach during logout and entity cleanup.
            if (!releasingPassenger && event.isDismounting()
                    && event.getEntityBeingMounted() instanceof TransportFormationEntity carrier
                    && carrier.level() instanceof ServerLevel && !carrier.isRemoved()
                    && !event.getEntityMounting().isRemoved())
                event.setCanceled(true);
        });
        bus.addListener(EventPriority.HIGHEST, (LivingFallEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p && airborne(p.server, p.getUUID())) {
                event.setCanceled(true);
                p.fallDistance = 0;
            }
        });
        // Return the stored equipment before vanilla collects death drops.
        bus.addListener(EventPriority.HIGHEST, (LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p) restoreEquipment(p);
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p) {
                recoverLogin(p);
                migrateLegacyChestPack(p);
            }
        });
        bus.addListener(EventPriority.HIGHEST, (PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p) {
                // Keep the round's Rider record for reconnect, but save normal equipment to disk.
                restoreEquipment(p);
                if (p.getVehicle() instanceof TransportFormationEntity) release(p);
            }
        });
        bus.addListener((PlayerEvent.Clone event) -> {
            // Non-death replacement (for example returning from the End) keeps the inventory.
            // Its equipment recovery must follow the new player entity as well.
            if (!event.isWasDeath() && event.getOriginal().getPersistentData().contains(RECOVERY))
                event.getEntity().getPersistentData().put(RECOVERY,
                        event.getOriginal().getPersistentData().getCompound(RECOVERY).copy());
        });
    }
    public static void preparing(MinecraftServer server, MatchEngine game) {
        FLIGHTS.computeIfAbsent(server, ignored -> new LinkedHashMap<>())
                .put(game.session().sessionId(), new Flight(game));
    }
    public static boolean airborne(MinecraftServer server, UUID player) {
        return FLIGHTS.getOrDefault(server, Map.of()).values().stream().anyMatch(f -> f.riders.containsKey(player));
    }
    public static boolean onboard(MinecraftServer server, UUID player) {
        return FLIGHTS.getOrDefault(server, Map.of()).values().stream()
                .anyMatch(f -> f.riders.containsKey(player) && f.riders.get(player).onboard);
    }
    public static int descentStage(ServerPlayer player) {
        for (Flight flight : FLIGHTS.getOrDefault(player.server, Map.of()).values()) {
            Rider rider = flight.riders.get(player.getUUID());
            if (rider != null && !rider.onboard && flight.game.alive(player.getUUID()))
                return rider.parachute ? 3 : 2;
        }
        return 0;
    }
    public static void tick(MinecraftServer server) {
        for (Flight flight : List.copyOf(FLIGHTS.getOrDefault(server, Map.of()).values())) {
            var game = flight.game;
            if (game.finished()) { stop(server, game.session().sessionId()); continue; }
            if (!flight.launched && (game.paused()
                    || game.elapsedTicks() < game.settings().preparationSeconds() * 20L)) continue;
            try {
                if (!flight.launched) launch(server, flight);
                advance(server, flight);
            } catch (RuntimeException error) {
                com.example.examplemod.ExampleMod.LOGGER.error("Could not run flight for map {}", game.session().map().id(), error);
                server.getPlayerList().getPlayers().stream().filter(p -> p.hasPermissions(2))
                        .forEach(p -> p.sendSystemMessage(Texts.of("message.tacz_pubg.flight.failed", error.getMessage())));
                MatchService.end(server, game.session().map().id());
            }
        }
    }
    private static void launch(MinecraftServer server, Flight flight) {
        var game = flight.game;
        MapResetService.started(server, game.session().map().id());
        flight.launched = true;
        if (!game.settings().flightRules().enabled()) return;
        var map = game.session().map();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, map.dimension()));
        if (level == null || game.flightPath() == null) throw new IllegalStateException("Flight world or route unavailable");
        double base = level.getSeaLevel();
        for (var member : game.roster()) {
            var p = server.getPlayerList().getPlayer(member.id());
            if (p != null && game.alive(member.id())) base = Math.max(base, p.getY());
        }
        flight.height = base + game.settings().flightRules().heightAboveGround();
        var route = game.flightPath();
        TransportFormationEntity carrier = FlightRegistration.TRANSPORT.get().create(level);
        if (carrier == null) throw new IllegalStateException("Could not create transport");
        carrier.assign(game.roster().stream().map(MatchEngine.Participant::id).toList());
        carrier.setYRot((float) (Math.toDegrees(Math.atan2(route.endZ() - route.startZ(),
                route.endX() - route.startX())) - 90));
        double lead = game.settings().flightRules().speedBlocksPerSecond()
                * game.settings().flightRules().jumpDelaySeconds();
        double length = Math.hypot(route.endX() - route.startX(), route.endZ() - route.startZ());
        carrier.setPos(route.startX() - (route.endX() - route.startX()) / length * lead, flight.height,
                route.startZ() - (route.endZ() - route.startZ()) / length * lead);
        if (!level.addFreshEntity(carrier)) throw new IllegalStateException("Could not add transport");
        flight.carrier = carrier;
        for (var member : game.roster()) {
            var p = server.getPlayerList().getPlayer(member.id());
            if (p == null || !game.alive(member.id())) continue;
            remember(p, game.session().sessionId());
            flight.riders.put(member.id(), new Rider());
            board(p, carrier);
        }
    }
    private static void board(ServerPlayer p, TransportFormationEntity carrier) {
        release(p);
        Vec3 at = carrier.getPassengerRidingPosition(p);
        p.teleportTo((ServerLevel) carrier.level(), at.x, at.y, at.z, p.getYRot(), p.getXRot());
        p.setOnGround(false);
        p.fallDistance = 0;
        p.setDeltaMovement(Vec3.ZERO);
        if (!p.startRiding(carrier, true)) throw new IllegalStateException("Could not board " + p.getGameProfile().getName());
    }
    private static void advance(MinecraftServer server, Flight flight) {
        var game = flight.game;
        var route = game.flightPath();
        if (flight.carrier == null) return;
        double progress = game.flightRouteProgress();
        if (!flight.carrier.isRemoved()) {
            flight.carrier.setPos(route.startX() + (route.endX() - route.startX()) * progress,
                    flight.height, route.startZ() + (route.endZ() - route.startZ()) * progress);
            for (Entity passenger : flight.carrier.getPassengers()) flight.carrier.positionRider(passenger);
        }
        for (UUID id : List.copyOf(flight.riders.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Rider rider = flight.riders.get(id);
            if (p == null) continue; // Rejoin uses this round's current carrier, not the old coordinates.
            if (!game.alive(id) || !p.isAlive() || !game.session().map().dimension().equals(p.level().dimension().location())) {
                clear(p, flight); continue;
            }
            if (rider.onboard) {
                if (p.getVehicle() != flight.carrier) { clear(p, flight); continue; } // External teleport is authoritative.
                long delay = game.settings().flightRules().jumpDelaySeconds() * 20L;
                double botJump = .25 + (Math.floorMod(id.hashCode(), 50) / 100D);
                if (!game.paused() && (progress >= 1 || MatchBotService.isCarpetPlayer(p)
                        && game.combatTicks() >= delay && progress >= botJump)) jump(p, flight, true);
                else {
                    int wait = (int) Math.max(0, (delay - game.combatTicks() + 19) / 20);
                    send(p, rider, state(flight, rider, 1, wait));
                }
            } else {
                if (p.onGround() || p.isInWater() || p.isInLava() || p.isFallFlying()
                        || !ParachuteItem.isEquipped(p)) { clear(p, flight); continue; }
                rider.canDeploy |= rider.jumpHeight - p.getY() >= manualDeployDrop(game);
                BlockPos at = p.blockPosition();
                if (p.serverLevel().hasChunkAt(at)) {
                    int surface = p.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
                    if (p.getY() - surface <= game.settings().flightRules().autoDeployHeight()) rider.parachute = true;
                }
                // Motion is handled in Player.travel: locally for real players, on the server for bots.
                // Never send the server's stale real-player velocity back to the client.
                p.fallDistance = 0;
                send(p, rider, state(flight, rider, rider.parachute ? 3 : 2, 0));
            }
        }
        if (!game.paused() && progress >= 1 && !flight.carrier.isRemoved()) {
            // Offline passengers still have a Rider record; reconnect deploys them at the route end.
            flight.carrier.discard();
        }
    }
    public static void requestJump(ServerPlayer player, UUID session) {
        Flight flight = FLIGHTS.getOrDefault(player.server, Map.of()).get(session);
        if (flight != null && !flight.game.paused() && flight.game.alive(player.getUUID())
                && player.isAlive() && flight.carrier != null && !flight.carrier.isRemoved()
                && player.getVehicle() == flight.carrier
                && flight.game.session().map().dimension().equals(player.level().dimension().location()))
            jump(player, flight, false);
    }
    public static void requestDeploy(ServerPlayer player, UUID session) {
        Flight flight = FLIGHTS.getOrDefault(player.server, Map.of()).get(session);
        if (flight == null || flight.game.paused() || !flight.game.alive(player.getUUID())
                || !player.isAlive() || player.onGround() || player.isInWater() || player.isInLava()
                || player.isPassenger() || !ParachuteItem.isEquipped(player)
                || !flight.game.session().map().dimension().equals(player.level().dimension().location())) return;
        Rider rider = flight.riders.get(player.getUUID());
        if (rider == null || rider.onboard || rider.parachute) return;
        rider.canDeploy |= rider.jumpHeight - player.getY() >= manualDeployDrop(flight.game);
        if (!rider.canDeploy) return;
        rider.parachute = true;
        send(player, rider, state(flight, rider, 3, 0));
    }
    private static double manualDeployDrop(MatchEngine game) {
        // Minecraft adaptation, not a claimed PUBG engine constant.
        return Math.min(50, game.settings().flightRules().heightAboveGround() * .1);
    }
    private static FlightPayloads.State state(Flight flight, Rider rider, int stage, int wait) {
        return new FlightPayloads.State(flight.game.session().sessionId(), stage, wait,
                rider.canDeploy, flight.game.settings().flightRules().autoDeployHeight(), flight.height);
    }
    private static void jump(ServerPlayer p, Flight flight, boolean forced) {
        Rider rider = flight.riders.get(p.getUUID());
        if (rider == null || !rider.onboard) return;
        var game = flight.game;
        if (!forced && game.combatTicks() < game.settings().flightRules().jumpDelaySeconds() * 20L) return;
        var map = game.session().map();
        Vec3 at = p.position();
        // Tail seats can still be outside at route entry; everyone is allowed to jump when the
        // carrier reaches the start. The release position is clamped into the map below.
        if (!forced && !map.contains(flight.carrier.getX(), flight.carrier.getZ())) return;
        release(p);
        // The tail of the formation may straddle the edge. End-of-route ejection always lands inside.
        double mx = Math.min(.4, (map.maxX() - map.minX()) / 4), mz = Math.min(.4, (map.maxZ() - map.minZ()) / 4);
        p.teleportTo(p.serverLevel(), Math.clamp(at.x, map.minX() + mx, map.maxX() - mx), at.y,
                Math.clamp(at.z, map.minZ() + mz, map.maxZ() - mz), p.getYRot(), p.getXRot());
        // Teleports preserve the old ground flag; otherwise the first descent tick may immediately
        // end fall protection while the player is still hundreds of blocks above the ground.
        p.setOnGround(false);
        p.setDeltaMovement(0, -.1, 0);
        p.fallDistance = 0;
        rider.onboard = false;
        rider.jumpHeight = p.getY();
        send(p, rider, state(flight, rider, 2, 0));
    }
    private static void release(Entity entity) {
        boolean before = releasingPassenger;
        releasingPassenger = true;
        try { entity.stopRiding(); } finally { releasingPassenger = before; }
    }
    private static void send(ServerPlayer player, Rider rider, FlightPayloads.State next) {
        if (!next.equals(rider.sent) || player.server.getTickCount() % 20 == 0) {
            // The HUD payload goes only to its owner. Equipment synchronization also reaches
            // observers, including players who begin tracking this descent after the jump.
            ItemStack pack = player.getItemBySlot(EquipmentSlot.LEGS);
            if (ParachuteItem.isParachute(pack)) {
                CompoundTag marker = pack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                if (marker.hasUUID("flight_pack") && next.session().equals(marker.getUUID("flight_pack"))
                        && marker.getInt(ParachuteItem.FLIGHT_STAGE_TAG) != next.stage()) {
                    ItemStack updated = pack.copy();
                    marker.putInt(ParachuteItem.FLIGHT_STAGE_TAG, next.stage());
                    updated.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
                    player.setItemSlot(EquipmentSlot.LEGS, updated);
                }
            }
            PacketDistributor.sendToPlayer(player, next);
            rider.sent = next;
        }
    }
    private static void clear(ServerPlayer p, Flight flight) {
        if (p.getVehicle() instanceof TransportFormationEntity) release(p);
        flight.riders.remove(p.getUUID());
        restoreEquipment(p);
        p.getPersistentData().remove(RECOVERY);
        p.fallDistance = 0;
        PacketDistributor.sendToPlayer(p, FlightPayloads.State.NONE);
    }
    private static void remember(ServerPlayer p, UUID session) {
        restoreEquipment(p);
        CompoundTag tag = new CompoundTag();
        tag.putUUID("session", session);
        tag.putString("dimension", p.level().dimension().location().toString());
        tag.putDouble("x", p.getX()); tag.putDouble("y", p.getY()); tag.putDouble("z", p.getZ());
        p.getPersistentData().put(RECOVERY, tag);
        equip(p, tag, session);
    }
    private static void equip(ServerPlayer p, CompoundTag tag, UUID session) {
        ItemStack previous = p.getItemBySlot(EquipmentSlot.LEGS);
        tag.putBoolean("equipment_saved", true);
        tag.putString("equipment_slot", "legs");
        if (!previous.isEmpty()) tag.put("equipment", previous.save(p.registryAccess()));
        else tag.remove("equipment");
        tag.remove("chest");
        ItemStack pack = ParachuteItem.createStack();
        CompoundTag marker = new CompoundTag();
        marker.putUUID("flight_pack", session);
        pack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
        p.setItemSlot(EquipmentSlot.LEGS, pack);
    }
    private static void restoreEquipment(ServerPlayer p) {
        CompoundTag tag = p.getPersistentData().getCompound(RECOVERY);
        if (!tag.getBoolean("equipment_saved") || !tag.hasUUID("session")) return;
        UUID session = tag.getUUID("session");
        // A temporary pack moved to another inventory slot must not survive the flight.
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (temporaryPack(p.getInventory().getItem(i), session)) p.getInventory().setItem(i, ItemStack.EMPTY);
        if (temporaryPack(p.containerMenu.getCarried(), session)) p.containerMenu.setCarried(ItemStack.EMPTY);
        if (p.containerMenu != p.inventoryMenu && temporaryPack(p.inventoryMenu.getCarried(), session))
            p.inventoryMenu.setCarried(ItemStack.EMPTY);
        if (temporaryPack(p.getItemBySlot(EquipmentSlot.CHEST), session)) p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        if (temporaryPack(p.getItemBySlot(EquipmentSlot.LEGS), session)) p.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        // Recovery written before the slot migration contains a saved chest item. Keep its
        // original slot, so upgrading never turns a stored vest into a leggings item.
        EquipmentSlot slot = tag.getString("equipment_slot").equals("legs") ? EquipmentSlot.LEGS : EquipmentSlot.CHEST;
        String savedKey = slot == EquipmentSlot.LEGS ? "equipment" : "chest";
        ItemStack original = ItemStack.parseOptional(p.registryAccess(), tag.getCompound(savedKey));
        if (!original.isEmpty()) {
            if (p.getItemBySlot(slot).isEmpty()) p.setItemSlot(slot, original);
            else p.getInventory().placeItemBackInInventory(original);
        }
        tag.remove("chest"); tag.remove("equipment"); tag.remove("equipment_slot");
        tag.putBoolean("equipment_saved", false);
    }
    private static void migrateLegacyChestPack(ServerPlayer player) {
        ItemStack previous = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!ParachuteItem.isParachute(previous)) return;
        // A real vest/backpack in the destination is never overwritten. Full inventories use
        // vanilla's give-back handling, which leaves a recoverable item at the player's feet.
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        if (player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) player.setItemSlot(EquipmentSlot.LEGS, previous);
        else player.getInventory().placeItemBackInInventory(previous);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }
    private static boolean temporaryPack(ItemStack stack, UUID session) {
        if (!ParachuteItem.isParachute(stack)) return false;
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.hasUUID("flight_pack") && session.equals(data.getUUID("flight_pack"));
    }
    private static void returnToGround(ServerPlayer p) {
        CompoundTag tag = p.getPersistentData().getCompound(RECOVERY);
        if (tag.isEmpty()) return;
        var dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        var level = dimension == null ? null : p.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        release(p);
        if (level != null && p.isAlive()) p.teleportTo(level, tag.getDouble("x"), tag.getDouble("y"),
                tag.getDouble("z"), p.getYRot(), p.getXRot());
        p.fallDistance = 0;
        p.setDeltaMovement(Vec3.ZERO);
        restoreEquipment(p);
        p.getPersistentData().remove(RECOVERY);
    }
    private static void recoverLogin(ServerPlayer p) {
        var tag = p.getPersistentData().getCompound(RECOVERY);
        if (tag.isEmpty()) return;
        var flight = tag.hasUUID("session") ? FLIGHTS.getOrDefault(p.server, Map.of()).get(tag.getUUID("session")) : null;
        Rider rider = flight == null ? null : flight.riders.get(p.getUUID());
        if (rider == null || !flight.game.alive(p.getUUID())) { returnToGround(p); return; }
        // Logged-out players keep their own equipment. Re-equip without overwriting a recovery
        // snapshot left by an unexpected disconnect/server interruption.
        if (!tag.getBoolean("equipment_saved")) equip(p, tag, flight.game.session().sessionId());
        rider.sent = null;
        if (rider.onboard) {
            if (flight.carrier != null && !flight.carrier.isRemoved()) board(p, flight.carrier);
            else {
                var map = flight.game.session().map();
                var level = p.server.getLevel(ResourceKey.create(Registries.DIMENSION, map.dimension()));
                var path = flight.game.flightPath();
                if (level != null) p.teleportTo(level, path.endX(), flight.height, path.endZ(), p.getYRot(), p.getXRot());
                jump(p, flight, true);
            }
        }
        send(p, rider, state(flight, rider, rider.onboard ? 1 : rider.parachute ? 3 : 2,
                rider.onboard ? (int) Math.max(0, (flight.game.settings().flightRules().jumpDelaySeconds() * 20L
                        - flight.game.combatTicks() + 19) / 20) : 0));
    }
    public static void stop(MinecraftServer server, UUID session) {
        var flights = FLIGHTS.get(server);
        Flight flight = flights == null ? null : flights.remove(session);
        if (flight == null) return;
        for (UUID id : List.copyOf(flight.riders.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) { returnToGround(p); clear(p, flight); }
        }
        if (flight.carrier != null) flight.carrier.discard();
    }
    public static void stopped(MinecraftServer server) {
        for (UUID session : List.copyOf(FLIGHTS.getOrDefault(server, Map.of()).keySet())) stop(server, session);
        FLIGHTS.remove(server);
    }
}
