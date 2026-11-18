package com.example.examplemod.vitals;

import com.example.examplemod.zones.match.MatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Authoritative use and recovery lifecycle. Nothing pending survives death, logout or another match. */
public final class PubgMedicineService {
    private static final UUID ORDINARY_WORLD = new UUID(0, 0);
    private static final Map<MinecraftServer, Map<UUID, State>> STATES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> ORDINARY_CLOCKS = new WeakHashMap<>();
    private static int nextContinuationToken;
    private record Context(UUID session, long clock, boolean running) {}
    private static final class Use {
        final PubgMedicineItem item;
        final ItemStack stack;
        final InteractionHand hand;
        final int selected;
        final long started;
        int inputRequest;
        int continuationToken;
        Vec3 lastPosition;
        Use(ServerPlayer p, PubgMedicineItem item, InteractionHand hand, long started) {
            this.item = item; this.hand = hand; this.started = started;
            this.stack = p.getItemInHand(hand); this.selected = p.getInventory().selected; this.lastPosition = p.position();
        }
    }
    private static final class State {
        final UUID session;
        Use use;
        Use repeatBandage;
        final List<PubgMedicineModel.Recovery> recoveries = new ArrayList<>();
        State(UUID session) { this.session = session; }
    }
    private PubgMedicineService() {}
    public static void register(IEventBus bus) {
        bus.addListener(PubgMedicineService::tick);
        bus.addListener((LivingEvent.LivingJumpEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) stopUse(p);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) forget(p);
        });
        bus.addListener((PlayerEvent.Clone e) -> {
            if (e.getOriginal() instanceof ServerPlayer p) forget(p);
        });
        bus.addListener((ServerStoppedEvent e) -> {
            STATES.remove(e.getServer()); ORDINARY_CLOCKS.remove(e.getServer());
        });
    }
    private static Context context(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || com.example.examplemod.combat.CombatService.downed(p)) return null;
        var participant = MatchService.participantGame(p.server, p.getUUID());
        var active = MatchVitalsService.activeGame(p);
        if (participant != null && !participant.finished() && active == null) return null;
        boolean running = p.server.tickRateManager().runsNormally();
        return active == null ? new Context(ORDINARY_WORLD, ORDINARY_CLOCKS.getOrDefault(p.server, 0L), running)
                : new Context(active.session().sessionId(), active.combatTicks(), running && !active.paused());
    }
    private static State state(ServerPlayer p) {
        var states = STATES.get(p.server);
        return states == null ? null : states.get(p.getUUID());
    }
    /** PlayerTick.Post must leave the hand intact until ServerTick.Post decides the next bandage. */
    public static boolean pendingContinuation(ServerPlayer player) {
        State state = state(player);
        return state != null && state.repeatBandage != null;
    }
    public static void startHeld(ServerPlayer p, int requestedSlot, int request) {
        int duration = 0;
        if (p.getInventory().selected == requestedSlot && !p.isUsingItem() && p.containerMenu == p.inventoryMenu
                && p.getMainHandItem().getItem() instanceof PubgMedicineItem medicine) {
            medicine.startTreatment(p.level(), p, InteractionHand.MAIN_HAND);
            State state = state(p);
            if (p.isUsingItem() && state != null && state.use != null) {
                state.use.inputRequest = request;
                duration = medicine.getUseDuration(p.getMainHandItem(), p);
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new MatchVitalsPayloads.MedicineStarted(requestedSlot, request, duration));
    }
    public static void cancelRequest(ServerPlayer p, int request) {
        State state = state(p);
        if (state != null && state.use != null && state.use.inputRequest == request) stopUse(p);
    }
    public static void cancelContinuation(ServerPlayer p, UUID session, int token) {
        State state = state(p);
        if (state != null && state.session.equals(session) && state.use != null
                && state.use.continuationToken == token) stopUse(p);
    }
    public static boolean begin(ServerPlayer p, PubgMedicineItem item, InteractionHand hand) {
        Context context = context(p);
        if (context == null || !context.running || unsafeMovement(p) || p.isUsingItem()
                || com.example.examplemod.combat.CombatService.actionBlocked(p)
                || p.getItemInHand(hand).isEmpty() || p.getItemInHand(hand).getItem() != item) return false;
        double boost = item.kind().isBoost() ? MatchVitalsService.currentBoost(p) : 0;
        if (!item.kind().canUse(p.getHealth(), p.getMaxHealth(), boost)) return false;
        var states = STATES.computeIfAbsent(p.server, ignored -> new HashMap<>());
        State state = states.get(p.getUUID());
        if (state == null || !state.session.equals(context.session)) {
            state = new State(context.session); states.put(p.getUUID(), state);
        }
        state.repeatBandage = null;
        state.use = new Use(p, item, hand, context.clock);
        return true;
    }
    private static boolean unsafeMovement(ServerPlayer p) {
        return p.isSprinting() || p.isFallFlying() || p.isSwimming() || p.getAbilities().flying
                || !p.isPassenger() && (p.getDeltaMovement().y > .15 || p.fallDistance > .75f);
    }
    private static boolean valid(ServerPlayer p, State state, Context context) {
        if (state == null || state.use == null || context == null || !context.running
                || !context.session.equals(state.session) || unsafeMovement(p)
                || com.example.examplemod.combat.CombatService.actionBlocked(p)) return false;
        Use use = state.use;
        return p.isUsingItem() && p.getUsedItemHand() == use.hand
                && p.getInventory().selected == use.selected
                && p.getUseItem() == use.stack && p.getItemInHand(use.hand) == use.stack
                && !use.stack.isEmpty() && use.stack.getItem() == use.item
                && use.item.kind().canUse(p.getHealth(), p.getMaxHealth(),
                        use.item.kind().isBoost() ? MatchVitalsService.currentBoost(p) : 0)
                && !PubgMedicineModel.movedTooFar(p.position().distanceToSqr(use.lastPosition));
    }
    public static void validateUse(ServerPlayer p) {
        if (!valid(p, state(p), context(p))) stopUse(p);
    }
    public static void cancelUse(ServerPlayer p) {
        State state = state(p);
        if (state != null) { state.use = null; state.repeatBandage = null; }
    }
    private static void stopUse(ServerPlayer p) {
        State state = state(p);
        Use use = state == null ? null : state.use != null ? state.use : state.repeatBandage;
        boolean reachedLimit = use != null && use.item.kind() == PubgMedicineModel.Kind.BANDAGE
                && p.isAlive() && p.getHealth() >= p.getMaxHealth() * .75 - 1.0e-6;
        cancelUse(p);
        if (p.isUsingItem() && p.getUseItem().getItem() instanceof PubgMedicineItem) p.stopUsingItem();
        if (reachedLimit) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new MatchVitalsPayloads.MedicineInterrupted(state.session));
    }
    private static void forget(ServerPlayer p) {
        stopUse(p);
        var states = STATES.get(p.server);
        if (states != null) states.remove(p.getUUID());
        syncRecovery(p, null, null);
    }
    public static boolean finish(ServerPlayer p, PubgMedicineItem item, ItemStack stack) {
        Context context = context(p);
        State state = state(p);
        if (!valid(p, state, context)) { stopUse(p); return false; }
        Use use = state.use;
        if (use.item != item || use.stack != stack
                || !PubgMedicineModel.completed(use.started, context.clock, item.getUseDuration(stack, p), p.getTicksUsingItem())) {
            stopUse(p); return false;
        }
        state.use = null;
        var kind = item.kind();
        if (kind.isBoost()) return MatchVitalsService.addBoost(p, kind.boost());
        double budget = kind.healingBudget(p.getHealth(), p.getMaxHealth());
        if (budget <= 0) return false;
        if (kind == PubgMedicineModel.Kind.BANDAGE) state.repeatBandage = use;
        if (kind.recoveryTicks() == 0) p.heal((float) budget);
        else {
            var settings = com.example.examplemod.pubgsettings.PubgSettingsService.server(p.server);
            int duration = settings.integer(kind == PubgMedicineModel.Kind.FIRST_AID_KIT
                    ? "medicine.first_aid_recovery_ticks" : "medicine.bandage_recovery_ticks");
            state.recoveries.add(new PubgMedicineModel.Recovery(budget, kind.ceiling(), duration,
                    context.clock, 0, kind.recoverySteps()));
            syncRecovery(p, state, context);
        }
        return true;
    }
    /** Runs after vanilla consumes the completed stack and clears its old use state. */
    private static void continueBandage(ServerPlayer p, State state, Context context) {
        Use previous = state.repeatBandage;
        if (previous == null) return;
        state.repeatBandage = null;
        if (!context.running || unsafeMovement(p) || com.example.examplemod.combat.CombatService.actionBlocked(p)
                || p.isUsingItem() || p.getInventory().selected != previous.selected
                || PubgMedicineModel.movedTooFar(p.position().distanceToSqr(previous.lastPosition))
                || !PubgMedicineModel.Kind.BANDAGE.canUse(p.getHealth(), p.getMaxHealth(), 0)) return;
        ItemStack held = p.getItemInHand(previous.hand);
        if (!held.isEmpty() && held.getItem() != previous.item) return;
        if (held.isEmpty()) {
            for (int slot = 0; slot < p.getInventory().items.size(); slot++) {
                ItemStack available = p.getInventory().getItem(slot);
                if (available.isEmpty() || available.getItem() != previous.item) continue;
                p.getInventory().setItem(slot, ItemStack.EMPTY);
                p.setItemInHand(previous.hand, available);
                break;
            }
        }
        if (p.getItemInHand(previous.hand).isEmpty()) return;
        previous.item.startTreatment(p.level(), p, previous.hand);
        if (state.use == null || !p.isUsingItem()) return;
        state.use.inputRequest = previous.inputRequest;
        nextContinuationToken = nextContinuationToken == Integer.MAX_VALUE ? 1 : nextContinuationToken + 1;
        state.use.continuationToken = nextContinuationToken;
        p.getInventory().setChanged();
        p.inventoryMenu.broadcastChanges();
        if (p.containerMenu != p.inventoryMenu) p.containerMenu.broadcastChanges();
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new MatchVitalsPayloads.BandageContinued(state.session, previous.selected, previous.hand,
                        previous.item.getUseDuration(p.getItemInHand(previous.hand), p), nextContinuationToken));
    }
    private static void syncRecovery(ServerPlayer p, State state, Context context) {
        double remaining = 0, next = 0, ceiling = 0;
        int ticks = 0;
        if (state != null && context != null) for (var recovery : state.recoveries) {
            remaining += recovery.remainingBudget(); next += recovery.nextBudget();
            ceiling = Math.max(ceiling, recovery.ceiling());
            ticks = Math.max(ticks, (int) (recovery.duration() - recovery.advanced()));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, remaining > 0
                ? new MatchVitalsPayloads.RecoveryState(state.session, remaining, Math.min(remaining, next), ceiling, ticks)
                : MatchVitalsPayloads.RecoveryState.INACTIVE);
    }
    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.tickRateManager().runsNormally()) ORDINARY_CLOCKS.merge(server, 1L, Long::sum);
        var states = STATES.get(server);
        if (states == null) return;
        var iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Context context = p == null ? null : context(p);
            State state = entry.getValue();
            if (context == null || !state.session.equals(context.session)) {
                if (p != null) stopUse(p);
                if (p != null) syncRecovery(p, null, null);
                iterator.remove(); continue;
            }
            if (state.use != null) {
                if (!valid(p, state, context)) stopUse(p);
                else state.use.lastPosition = p.position();
            }
            if (!context.running) { state.repeatBandage = null; continue; }
            boolean hadRecovery = !state.recoveries.isEmpty();
            boolean advancedPulse = false;
            float previousHealth = p.getHealth();
            var recoveries = state.recoveries.listIterator();
            while (recoveries.hasNext()) {
                var recovery = recoveries.next();
                var step = recovery.advance(context.clock, p.getHealth(), p.getMaxHealth());
                advancedPulse |= step.recovery().remainingBudget() < recovery.remainingBudget();
                if (step.healing() > 0) p.heal((float) step.healing());
                if (step.recovery().finished()) recoveries.remove();
                else recoveries.set(step.recovery());
            }
            // A recovery pulse can reach 75% after this tick's ordinary use validation.
            // Stop that new cast immediately, before it can consume another bandage.
            if ((state.repeatBandage != null || state.use != null && state.use.item.kind() == PubgMedicineModel.Kind.BANDAGE)
                    && p.getHealth() >= p.getMaxHealth() * .75 - 1.0e-6) stopUse(p);
            continueBandage(p, state, context);
            // Publish the new health before reducing its forecast, so both HUD edges move together.
            if (p.getHealth() != previousHealth) p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHealthPacket(
                    p.getHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel()));
            if (advancedPulse || !state.recoveries.isEmpty() && context.clock % 10 == 0 || hadRecovery && state.recoveries.isEmpty()
                    || state.use == null && state.recoveries.isEmpty())
                syncRecovery(p, state, context);
            if (state.use == null && state.repeatBandage == null && state.recoveries.isEmpty()) iterator.remove();
        }
    }
}
