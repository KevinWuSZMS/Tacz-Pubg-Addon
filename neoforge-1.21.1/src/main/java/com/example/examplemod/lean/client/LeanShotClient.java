package com.example.examplemod.lean.client;

import com.example.examplemod.lean.LeanService;
import com.example.examplemod.lean.LeanShotPayloads;
import com.tacz.guns.api.event.common.GunDrawEvent;
import com.tacz.guns.api.item.IGun;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/** Capture player/lean state exclusively on the client game thread, never TaCZ's sound scheduler. */
public final class LeanShotClient {
    private static final LeanShotSession SESSION = new LeanShotSession();
    private static final ThreadLocal<PendingShot> SCHEDULED = new ThreadLocal<>();
    private record GunIdentity(Item item, ResourceLocation gun, ResourceLocation display) { }
    private static final class PendingShot {
        final LeanShotDispatch dispatch;
        long timestamp;
        Runnable nativeSend;
        PendingShot(LeanShotDispatch dispatch) { this.dispatch = dispatch; }
    }
    private LeanShotClient() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener((GunDrawEvent event) -> {
            if (event.getLogicalSide() == LogicalSide.CLIENT && event.getEntity() == Minecraft.getInstance().player)
                invalidate();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> invalidate());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> observe());
    }

    public static void invalidate() {
        requireClientThread();
        SESSION.invalidate();
    }

    public static void observe() {
        requireClientThread();
        SESSION.observe(identity(Minecraft.getInstance().player));
    }

    /** Local inventory actions distinguish same-model replacement from ordinary server ammo synchronization. */
    public static void inventoryAction(Packet<?> packet) {
        if (!(packet instanceof ServerboundContainerClickPacket || packet instanceof ServerboundPickItemPacket
                || packet instanceof ServerboundSetCarriedItemPacket || packet instanceof ServerboundSetCreativeModeSlotPacket
                || packet instanceof ServerboundPlayerActionPacket action && switch (action.getAction()) {
                    case DROP_ITEM, DROP_ALL_ITEMS, SWAP_ITEM_WITH_OFFHAND -> true;
                    default -> false;
                })) return;
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            var connection = minecraft.getConnection();
            minecraft.execute(() -> {
                if (connection != null && minecraft.getConnection() == connection) inventoryAction(packet);
            });
            return;
        }
        if (packet instanceof ServerboundSetCarriedItemPacket) {
            observe();
        } else if (!(packet instanceof ServerboundSetCreativeModeSlotPacket creative)
                || minecraft.player != null && creative.slotNum() == 36 + minecraft.player.getInventory().selected) {
            invalidate();
        }
    }

    /** Each scheduled burst owns its context, captured BEFORE cooldown and main-thread queue delays. */
    public static Runnable scheduled(LocalPlayer player, ItemStack expectedGun, Runnable task) {
        requireClientThread();
        var live = identity(Minecraft.getInstance().player);
        // A stale operator or a gun changed by a shoot-event listener must not capture the new owner's session.
        var ticket = live != null && live.player() == player && Objects.equals(live.gun(), gunIdentity(expectedGun))
                ? SESSION.capture(live) : new LeanShotSession.Ticket(null, Long.MIN_VALUE);
        PendingShot group = new PendingShot(new LeanShotDispatch(ticket));
        return () -> {
            PendingShot previous = SCHEDULED.get();
            SCHEDULED.set(group);
            try { task.run(); }
            finally {
                if (previous == null) SCHEDULED.remove();
                else SCHEDULED.set(previous);
            }
        };
    }

    /** Scheduler only: save the request instead of creating a second client-thread task. */
    public static void defer(long timestamp, Runnable nativeSend) {
        PendingShot group = pending();
        group.timestamp = timestamp;
        group.nativeSend = nativeSend;
    }

    /** Scheduler only: freeze mutable burst state before the native counter advances. */
    public static Runnable roundTask(LocalPlayer player, int ordinal, Runnable nativeAnimation) {
        PendingShot pending = pending();
        LeanShotDispatch group = pending.dispatch;
        long timestamp = pending.timestamp;
        Runnable nativeSend = pending.nativeSend;
        pending.nativeSend = null;
        return () -> {
            requireClientThread();
            // Observe the live owner, not the old player captured by a task that survived respawn/reconnection.
            group.dispatch(SESSION, identity(Minecraft.getInstance().player), ordinal,
                    () -> round(player, timestamp, ordinal), nativeSend, nativeAnimation);
        };
    }

    private static PendingShot pending() {
        PendingShot group = SCHEDULED.get();
        if (group == null) throw new IllegalStateException("Missing scheduled shot context");
        return group;
    }

    private static LeanShotSession.Identity identity(LocalPlayer player) {
        var minecraft = Minecraft.getInstance();
        if (player == null || minecraft.player != player || minecraft.level == null
                || minecraft.getConnection() == null || !player.isAlive() || minecraft.isPaused()) return null;
        GunIdentity gun = gunIdentity(player.getMainHandItem());
        if (gun == null) return null;
        // TaCZ's equip identity, without mutable ammo/heat/attachment components or the stack reference.
        return new LeanShotSession.Identity(player, minecraft.level, minecraft.getConnection(),
                player.getInventory().selected, gun);
    }

    private static GunIdentity gunIdentity(ItemStack held) {
        if (held.isEmpty() || !(held.getItem() instanceof IGun gun)) return null;
        return new GunIdentity(held.getItem(), gun.getGunId(held), gun.getGunDisplayId(held));
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Shot state belongs to the client thread");
    }

    private static void round(LocalPlayer player, long timestamp, int ordinal) {
        // Already validated once by dispatch; pose, request and effects share one queue boundary.
        PacketDistributor.sendToServer(new LeanShotPayloads.Pose(timestamp, ordinal,
                LeanService.inputSequence(player), LeanService.inputAgeSeconds(player), player.getXRot(), Mth.wrapDegrees(player.getYRot())));
    }
}
