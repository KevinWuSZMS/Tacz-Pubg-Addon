package com.example.examplemod.movement;

import com.example.examplemod.combat.CombatService;
import com.example.examplemod.movement.MovementState.Mode;
import com.example.examplemod.zones.match.flight.MatchFlightService;
import com.example.examplemod.zones.match.flight.ParachuteItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Pose;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Authoritative stance ownership, replicated through the player's normal entity metadata. */
public final class MovementService {
    private MovementService() { }

    public static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(EventPriority.LOWEST, (PlayerTickEvent.Pre event) -> {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            byte state = data(player).taczPubg$movementMode();
            if (state == 0) return;
            if (!eligible(player)) clear(player);
            else if (MovementModes.decode(state) != Mode.SPRINT) player.setSprinting(false);
        });
        bus.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) clear(player);
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> clear(event.getEntity()));
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> clear(event.getEntity()));
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> clear(event.getEntity()));
        bus.addListener((PlayerEvent.Clone event) -> {
            clear(event.getOriginal());
            clear(event.getEntity());
        });
    }

    public static boolean eligible(Player player) {
        boolean rescue=CombatService.rescuing(player);
        if (!player.isAlive() || player.isSpectator() || player.getAbilities().flying || player.isPassenger()
                || player.isSleeping() || (!rescue&&(player.isSwimming() || player.isInWater())) || player.isUnderWater() || player.isInLava()
                || player.isFallFlying() || player.isAutoSpinAttack() || CombatService.downed(player)) return false;
        if (player instanceof ServerPlayer server && MatchFlightService.airborne(server.server, player.getUUID())) return false;
        return !ParachuteItem.isEquipped(player) || player.onGround();
    }

    public static boolean active(Player player) {
        return data(player).taczPubg$movementMode() != 0 && eligible(player);
    }

    public static Mode mode(Player player) {
        return MovementModes.decode(data(player).taczPubg$movementMode());
    }

    /** Local prediction uses exactly the same metadata as the later server acknowledgement. */
    public static void predict(Player player, Mode mode, boolean active) {
        update(player, MovementModes.authorize(mode, active, eligible(player)), false);
    }

    public static void input(ServerPlayer player, Mode mode, boolean active) {
        // Stance input ends rescue before the new stance is authorized and applied.
        if (CombatService.rescuing(player) && (!active || mode != Mode.CROUCH))
            CombatService.rescue(player, player.getUUID(), true);
        // Force a metadata acknowledgement even when rejection leaves the server's old value unchanged.
        update(player, MovementModes.authorize(mode, active, eligible(player)), true);
    }

    /** Rescue uses the same replicated ground crouch as the match controls. */
    public static void beginRescue(ServerPlayer player) {
        update(player, MovementModes.encode(Mode.CROUCH, true), true);
        player.setSprinting(false);
        player.setPose(Pose.CROUCHING);
    }

    public static void clear(Player player) { update(player, (byte) 0, false); }

    private static void update(Player player, byte next, boolean acknowledge) {
        MovementStateAccess state = data(player);
        byte previous = state.taczPubg$movementMode();
        state.taczPubg$movementMode(next, acknowledge);
        if (MovementModes.stopsSprint(previous, next)) player.setSprinting(false);
    }

    private static MovementStateAccess data(Player player) { return (MovementStateAccess) player; }
}
