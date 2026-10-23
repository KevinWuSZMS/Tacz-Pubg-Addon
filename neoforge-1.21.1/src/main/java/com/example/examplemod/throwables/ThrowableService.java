package com.example.examplemod.throwables;

import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.zones.match.MatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import me.xjqsh.lrtactical.init.ModEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Held grenades and thrown entities are server-owned; input packets only request cook or cancel. */
public final class ThrowableService {
    private static final Map<MinecraftServer, Map<UUID, Held>> HELD = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> CLOCKS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Long>> COOLDOWNS = new WeakHashMap<>();
    private static final class Held {
        final PubgThrowableItem item;
        final ItemStack original;
        final InteractionHand hand;
        final int slot;
        final Vec3 origin;
        final ServerLevel level;
        final UUID session;
        final long map, started;
        final BlockHitResult placement;
        long cooked = -1;
        ItemStack escrow = ItemStack.EMPTY;
        Held(ServerPlayer p, PubgThrowableItem item, InteractionHand hand, long clock, BlockHitResult placement) {
            this.item = item; original = p.getItemInHand(hand); this.hand = hand; slot = p.getInventory().selected;
            origin = p.position(); level = p.serverLevel(); started = clock; this.placement = placement;
            var game = MatchService.participantGame(p.server, p.getUUID());
            session = game == null ? null : game.session().sessionId();
            map = game == null ? 0 : game.session().map().id();
        }
    }
    public static void register(IEventBus bus) {
        MolotovBurns.register(bus);
        bus.addListener(ThrowableService::tick);
        bus.addListener(EventPriority.HIGHEST, ThrowableService::toss);
        bus.addListener(EventPriority.HIGHEST, (LivingDeathEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) release(p, true);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) release(p, true);
        });
        bus.addListener((ServerStoppedEvent e) -> { HELD.remove(e.getServer()); CLOCKS.remove(e.getServer()); COOLDOWNS.remove(e.getServer()); });
        bus.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
            for (ServerPlayer player : e.getServer().getPlayerList().getPlayers()) release(player, true);
        });
        bus.addListener(ThrowableService::explosion);
    }
    private static long clock(ServerPlayer p) {
        var game = MatchService.participantGame(p.server, p.getUUID());
        return game == null ? CLOCKS.getOrDefault(p.server, 0L) : game.combatTicks();
    }
    private static Held held(ServerPlayer p) {
        var states = HELD.get(p.server); return states == null ? null : states.get(p.getUUID());
    }
    private static Held remove(ServerPlayer p) {
        var states = HELD.get(p.server); return states == null ? null : states.remove(p.getUUID());
    }
    private static boolean canUse(ServerPlayer p) {
        if (com.example.examplemod.combat.CombatService.actionBlocked(p)) return false;
        if (!p.isAlive() || p.isSpectator() || !p.server.tickRateManager().runsNormally()
                || MapResetService.isLocked(p.serverLevel(), p.blockPosition())) return false;
        var game = MatchService.participantGame(p.server, p.getUUID());
        return game == null || !game.finished() && !game.paused() && game.alive(p.getUUID())
                && game.elapsedTicks() >= game.settings().preparationSeconds() * 20L;
    }
    private static boolean sameScope(ServerPlayer p, Held held) {
        var game = MatchService.participantGame(p.server, p.getUUID());
        return p.serverLevel() == held.level && (held.session == null ? game == null
                : game != null && held.session.equals(game.session().sessionId()));
    }
    private static boolean sameHeld(ServerPlayer p, Held held) {
        return p.isUsingItem() && p.getUsedItemHand() == held.hand && p.getInventory().selected == held.slot
                && p.getUseItem() == held.original && p.getItemInHand(held.hand) == held.original
                && !held.original.isEmpty() && held.original.getItem() == held.item;
    }
    public static boolean begin(ServerPlayer p, PubgThrowableItem item, InteractionHand hand) {
        if (!canUse(p)) return false;
        if (COOLDOWNS.getOrDefault(p.server, Map.of()).getOrDefault(p.getUUID(), 0L)
                > CLOCKS.getOrDefault(p.server, 0L)) return false;
        if (held(p) != null && held(p).cooked >= 0) return false;
        if (held(p) != null) cancel(p);
        BlockHitResult placement = null;
        if (item.kind == ThrowableKind.C4) {
            if (p.isPassenger() || !p.onGround()) return false;
            placement = placement(p);
            if (placement == null) return false;
        }
        HELD.computeIfAbsent(p.server, ignored -> new HashMap<>()).put(p.getUUID(),
                new Held(p, item, hand, clock(p), placement));
        PacketDistributor.sendToPlayer(p, new ThrowablePayloads.Effect(0, 0, -1));
        return true;
    }
    private static BlockHitResult placement(ServerPlayer p) {
        var hit = ThrowableBallistics.placement(p);
        if (hit == null || MapResetService.isLocked(p.serverLevel(), hit.getBlockPos())) return null;
        return hit;
    }
    public static void cook(ServerPlayer p) {
        Held held = held(p);
        if (held == null || !canUse(p) || !sameHeld(p, held) || !sameScope(p, held)
                || !ThrowableSettingsService.profile(held.item.kind).cookable() || held.cooked >= 0
                || clock(p) - held.started < ThrowableSettingsService.profile(held.item.kind).prepareTicks()) return;
        // Pulling the pin commits exactly one grenade. Container moves, Q-drop, stack replacement,
        // or the last item becoming empty can no longer remove the committed projectile.
        held.escrow = held.original.copyWithCount(1);
        held.original.consume(1, p);
        held.cooked = clock(p);
        PacketDistributor.sendToPlayer(p, new ThrowablePayloads.Effect(0, 0,
                ThrowableSettingsService.profile(held.item.kind).cookedFuseTicks()));
        p.inventoryMenu.broadcastChanges();
    }
    public static void cancel(ServerPlayer p) {
        Held held = held(p);
        if (held == null) return;
        if (held.cooked >= 0) release(p, true);
        else remove(p);
        p.stopUsingItem();
        PacketDistributor.sendToPlayer(p, new ThrowablePayloads.Effect(0, 0, -1));
    }
    public static void release(ServerPlayer p, boolean dropped) {
        Held held = held(p);
        // Vanilla auto-releases an empty stack. A cooked last grenade waits for the explicit
        // physical-use-button release packet, or for its server fuse/death/slot-change fallback.
        if (held != null && held.cooked >= 0 && !dropped) return;
        releaseNow(p, dropped);
    }
    public static void throwCooked(ServerPlayer p) {
        Held held = held(p);
        if (held == null || held.cooked < 0 || !sameScope(p, held)) return;
        if (paused(held.level, held.session)) return;
        releaseNow(p, false);
        p.stopUsingItem();
    }
    public static boolean cooking(ServerPlayer p) {
        Held held = held(p);
        return held != null && held.cooked >= 0;
    }
    private static void releaseNow(ServerPlayer p, boolean dropped) {
        Held held = remove(p);
        if (held == null) return;
        PacketDistributor.sendToPlayer(p, new ThrowablePayloads.Effect(0, 0, -1));
        if (held.item.kind == ThrowableKind.C4 || dropped && held.cooked < 0 || !sameScope(p, held)) return;
        if (held.cooked < 0 && clock(p) - held.started < ThrowableSettingsService.profile(held.item.kind).prepareTicks()) return;
        ItemStack supply = held.cooked >= 0 ? held.escrow : findSupply(p, held);
        if (supply.isEmpty()) return;
        int fuse = held.cooked >= 0 ? remainingFuse(held, clock(p))
                : ThrowableSettingsService.profile(held.item.kind).fuseTicks();
        if (spawn(p, held, supply, fuse, dropped, null)) {
            if (held.cooked < 0) supply.consume(1, p);
        } else if (held.cooked >= 0 && !p.isCreative()) {
            // A cancelled entity spawn must not silently delete a paid item.
            if (!p.getInventory().add(supply)) p.drop(supply, false);
        }
    }
    private static ItemStack findSupply(ServerPlayer p, Held held) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack == held.original && !stack.isEmpty()) return stack;
        }
        // A container move/merge may replace the stack object; consume exactly one matching registered item.
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack.getItem() == held.item && !stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }
    public static void deploy(ServerPlayer p) {
        Held held = remove(p);
        if (held == null || held.item.kind != ThrowableKind.C4 || !canUse(p) || !sameHeld(p, held)
                || !sameScope(p, held) || clock(p) - held.started < ThrowableSettingsService.profile(held.item.kind).deploymentTicks() - 1
                || p.getTicksUsingItem() < ThrowableSettingsService.profile(held.item.kind).deploymentTicks() - 1 || p.isPassenger()
                || p.position().distanceToSqr(held.origin) > .01) return;
        var now = placement(p);
        if (now == null || !now.getBlockPos().equals(held.placement.getBlockPos())
                || now.getLocation().distanceToSqr(held.placement.getLocation()) > .25) return;
        if (spawn(p, held, held.original, ThrowableSettingsService.profile(ThrowableKind.C4).fuseTicks(), false, now)) held.original.consume(1, p);
    }
    private static boolean spawn(ServerPlayer p, Held held, ItemStack item, int fuse, boolean dropped, BlockHitResult surface) {
        if (item.isEmpty() || !scopeExists(held.level, held.session, held.map, p.position())) return false;
        int count = 0;
        for (Entity entity : held.level.getAllEntities()) if (held.cooked < 0
                && (entity instanceof PubgThrowableEntity || entity instanceof PubgSmokeGrenadeEntity)
                && ++count >= ThrowableRules.MAX_TRACKED_PER_LEVEL) return false;
        if (held.item.kind == ThrowableKind.SMOKE) {
            var smoke = new PubgSmokeGrenadeEntity(ThrowableRegistration.SMOKE.get(), held.level);
            smoke.initialize(p, item, fuse, held.session, held.map);
            ThrowableBallistics.launch(smoke, p, dropped, ThrowableKind.SMOKE);
            boolean added = held.level.addFreshEntity(smoke);
            if (added) cooldown(p, held.item.kind);
            return added;
        }
        var entity = new PubgThrowableEntity(ThrowableRegistration.THROWABLE.get(), held.level);
        entity.initialize(held.item.kind, p, item, Math.max(1, fuse), held.session, held.map);
        if (surface != null) entity.attach(surface.getLocation().add(Vec3.atLowerCornerOf(surface.getDirection().getNormal()).scale(.06)));
        else ThrowableBallistics.launch(entity, p, dropped);
        boolean added = held.level.addFreshEntity(entity);
        if (added) cooldown(p, held.item.kind);
        return added;
    }
    private static int remainingFuse(Held held, long now) {
        return Math.max(0, ThrowableSettingsService.profile(held.item.kind).cookedFuseTicks()
                - (int) Math.clamp(now - held.cooked, 0, 72000));
    }
    private static void cooldown(ServerPlayer player, ThrowableKind kind) {
        COOLDOWNS.computeIfAbsent(player.server, ignored -> new HashMap<>()).put(player.getUUID(),
                CLOCKS.getOrDefault(player.server, 0L) + ThrowableSettingsService.profile(kind).cooldownTicks());
    }
    private static void toss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer p)) return;
        Held held = held(p);
        if (held == null || held.cooked < 0 || event.getEntity().getItem().getItem() != held.item) return;
        // The tossed stack contains only uncommitted grenades; the pinned one is already escrowed.
        release(p, true); p.stopUsingItem();
    }
    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.tickRateManager().runsNormally()) CLOCKS.merge(server, 1L, Long::sum);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var data = player.getPersistentData();
            if (!data.hasUUID("tacz_pubg_flash_session")) continue;
            UUID session = data.getUUID("tacz_pubg_flash_session");
            if (MatchService.activeGames(server).stream().noneMatch(g -> !g.finished()
                    && g.session().sessionId().equals(session) && g.alive(player.getUUID()))) {
                player.removeEffect(ModEffects.BLIND); player.removeEffect(ModEffects.DEAFENED);
                data.remove("tacz_pubg_flash_session"); data.remove("tacz_pubg_flash_until");
            }
        }
        var states = HELD.get(server);
        if (states == null) return;
        for (UUID id : java.util.List.copyOf(states.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Held held = states.get(id);
            if (p == null) { states.remove(id); continue; }
            if (!sameScope(p, held)) { states.remove(id); p.stopUsingItem(); continue; }
            if (paused(held.level, held.session)) continue;
            boolean holding = held.cooked >= 0
                    ? p.getInventory().selected == held.slot && p.containerMenu == p.inventoryMenu
                    : sameHeld(p, held);
            if (!canUse(p) || !holding || held.item.kind == ThrowableKind.C4
                    && (p.position().distanceToSqr(held.origin) > .01 || p.isPassenger())) {
                release(p, true); p.stopUsingItem(); continue;
            }
            if (held.cooked >= 0) {
                int remaining = remainingFuse(held, clock(p));
                if (remaining <= 0) { release(p, true); p.stopUsingItem(); }
                else if (server.getTickCount() % 5 == 0)
                    PacketDistributor.sendToPlayer(p, new ThrowablePayloads.Effect(0, 0, remaining));
            }
        }
    }
    static boolean scopeExists(ServerLevel level, UUID session, long mapId, Vec3 position) {
        if (MapResetService.isLocked(level, BlockPos.containing(position))
                || MapResetService.isRestoring(level, BlockPos.containing(position))) return false;
        if (session == null) return true;
        var game = MatchService.activeGames(level.getServer()).stream()
                .filter(g -> g.session().sessionId().equals(session)).findFirst().orElse(null);
        return game != null && !game.finished() && game.session().map().id() == mapId
                && game.session().map().dimension().equals(level.dimension().location())
                && game.session().map().contains(position.x, position.z);
    }
    static boolean paused(ServerLevel level, UUID session) {
        if (!level.getServer().tickRateManager().runsNormally()) return true;
        return session != null && MatchService.activeGames(level.getServer()).stream()
                .anyMatch(g -> g.session().sessionId().equals(session) && g.paused());
    }
    static boolean mayAffect(PubgThrowableEntity source, LivingEntity victim) {
        if (!victim.isAlive() || victim.isSpectator()) return false;
        if (victim instanceof ServerPlayer p) {
            if (p.isCreative() && source.kind() != ThrowableKind.STUN) return false;
            var game = MatchService.participantGame(p.server, p.getUUID());
            if (source.session() == null) return game == null;
            return game != null && game.session().sessionId().equals(source.session()) && game.alive(p.getUUID());
        }
        return source.session() == null || scopeExists((ServerLevel) source.level(), source.session(), source.mapId(), victim.position());
    }
    public static boolean blocksSight(Level level, Vec3 from, Vec3 to) {
        AABB bounds = new AABB(from, to).inflate(5);
        for (var smoke : level.getEntitiesOfClass(me.xjqsh.lrtactical.entity.SmokeGrenadeEntity.class, bounds,
                e -> e instanceof PubgSmokeGrenadeEntity custom ? custom.emitting() : e.tickCount >= 40))
            if (new AABB(smoke.position().add(-5.5, -4.5, -5.5), smoke.position().add(5.5, 4.5, 5.5))
                    .clip(from, to).isPresent()) return true;
        return false;
    }
    public static boolean blinded(Entity observer) {
        return observer instanceof LivingEntity living && living.hasEffect(ModEffects.BLIND);
    }
    private static void explosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Vec3 center = event.getExplosion().center();
        // The native affected list includes armed C4 even when ordinary melee/bullets cannot damage it.
        for (Entity entity : java.util.List.copyOf(event.getAffectedEntities()))
            if (entity instanceof PubgThrowableEntity t && t.kind() == ThrowableKind.C4) t.discard();
        if (event.getExplosion() instanceof ThrowableExplosion explosion) {
            event.getAffectedEntities().clear();
            event.getAffectedBlocks().removeIf(pos -> !explosion.allowed(pos));
        }
    }
    private ThrowableService() {}
}
