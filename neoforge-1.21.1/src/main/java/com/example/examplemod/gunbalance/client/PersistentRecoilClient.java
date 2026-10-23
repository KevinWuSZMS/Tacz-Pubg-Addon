package com.example.examplemod.gunbalance.client;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

/** Experimental aim recoil. All orientation edits are additive, so manual counter-steering survives. */
public final class PersistentRecoilClient {
    private static final PersistentRecoilState STATE = new PersistentRecoilState();
    private static final ThreadLocal<Capture> LOCAL_FIRE = new ThreadLocal<>();
    private static LocalPlayer owner;
    private static ClientLevel level;
    private static ResourceLocation gunId;
    private static int slot = -1;
    private static long baseTick;

    private PersistentRecoilClient() {}

    /** TaCZ's scheduler sends before its local fire callback; move the whole experimental send to the main thread. */
    public static void sendShootWithAim(CustomPacketPayload payload, CustomPacketPayload[] additional) {
        if (!GunExperimentClient.balanceEnabled()) {
            PacketDistributor.sendToServer(payload, additional);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer expectedPlayer = minecraft.player;
        var expectedConnection = minecraft.getConnection();
        minecraft.execute(() -> {
            // An old scheduled Burst task must never send its shot into a newly joined world/server.
            if (expectedPlayer == null || minecraft.player != expectedPlayer
                    || expectedConnection == null || minecraft.getConnection() != expectedConnection) return;
            if (activeContext() && owner == expectedPlayer) {
                applyDelta(STATE.sample(clockMillis()), null);
                // TaCZ's shoot payload carries no rotation. Vanilla's normal 20 Hz move packet may
                // still describe the preceding frame, so explicitly send aim before this trigger.
                expectedConnection.send(new ServerboundMovePlayerPacket.Rot(
                        owner.getYRot(), owner.getXRot(), owner.onGround()));
            }
            PacketDistributor.sendToServer(payload, additional);
        });
    }

    /** Wrap only TaCZ's local per-projectile fire dispatch, not the server's echoed fire event. */
    public static Event postLocalFire(IEventBus bus, Event rawEvent) {
        if (!(rawEvent instanceof GunFireEvent event) || !activeContext()
                || event.getShooter() != owner || !event.getLogicalSide().isClient()) return bus.post(rawEvent);
        // Settle older impulses before queuing this shot. Never use an absolute aim baseline.
        applyDelta(STATE.sample(clockMillis()), null);
        Capture previous = LOCAL_FIRE.get();
        Capture capture = new Capture(event, owner, level, gunId, slot);
        LOCAL_FIRE.set(capture);
        try {
            Event posted = bus.post(rawEvent);
            // GunFireEvent can be canceled by a later listener. A canceled shot must not move aim.
            if (!event.isCanceled() && capture.sampled && activeContext() && capture.sameContext()) {
                STATE.add(capture.pitch, capture.yaw, clockMillis());
            }
            return posted;
        } finally {
            if (previous == null) LOCAL_FIRE.remove();
            else LOCAL_FIRE.set(previous);
        }
    }

    /** Called after TaCZ sampled its exact random curves and all attachment/pose/ADS modifiers. */
    public static void capture(GunFireEvent event, PolynomialSplineFunction pitch,
                               PolynomialSplineFunction yaw) {
        Capture capture = LOCAL_FIRE.get();
        if (capture == null || capture.event != event) return;
        capture.pitch = peak(pitch, false);
        capture.yaw = peak(yaw, true);
        capture.sampled = true;
    }

    /** Return true only while the server-confirmed experimental scheme owns camera recoil. */
    public static boolean applyCamera(ViewportEvent.ComputeCameraAngles event) {
        if (!activeContext()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isPaused()) {
            applyDelta(STATE.sample(clockMillis()), event.getCamera().getEntity() == owner ? event : null);
        }
        return true;
    }

    public static void reset() {
        STATE.clear();
        owner = null;
        level = null;
        gunId = null;
        slot = -1;
        LOCAL_FIRE.remove();
    }

    private static boolean activeContext() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!GunExperimentClient.balanceEnabled() || player == null || minecraft.level == null
                || player.isDeadOrDying()) {
            reset();
            return false;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof IGun gun)) {
            reset();
            // Suppress any old TaCZ recovery even if the player just switched to an empty hand.
            return true;
        }
        ResourceLocation currentId = gun.getGunId(stack);
        int currentSlot = player.getInventory().selected;
        if (owner != player || level != minecraft.level || slot != currentSlot || !currentId.equals(gunId)) {
            STATE.clear();
            owner = player;
            level = minecraft.level;
            gunId = currentId;
            slot = currentSlot;
            baseTick = level.getGameTime();
        }
        return true;
    }

    private static double clockMillis() {
        if (level == null) return 0;
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        double safePartial = Float.isFinite(partial) ? Math.max(0, Math.min(1, partial)) : 0;
        // Minecraft game time freezes while paused; a wall clock would advance pending kicks in menus.
        return (level.getGameTime() - baseTick + safePartial) * 50.0;
    }

    private static void applyDelta(PersistentRecoilState.Delta delta, ViewportEvent.ComputeCameraAngles camera) {
        if (owner == null || !Double.isFinite(delta.pitch()) || !Double.isFinite(delta.yaw())) return;
        float beforePitch = owner.getXRot(), beforeYaw = owner.getYRot();
        // Entity.turn updates previous/current angles together and clamps pitch, just like mouse input.
        owner.turn(-delta.yaw() / .15, -delta.pitch() / .15);
        if (camera != null) {
            camera.setPitch(camera.getPitch() + owner.getXRot() - beforePitch);
            camera.setYaw(camera.getYaw() + owner.getYRot() - beforeYaw);
        }
    }

    private static PersistentRecoilMath.Peak peak(PolynomialSplineFunction function, boolean absolute) {
        if (function == null) return PersistentRecoilMath.Peak.ZERO;
        PolynomialFunction[] polynomials = function.getPolynomials();
        double[][] coefficients = new double[polynomials.length][];
        for (int i = 0; i < polynomials.length; i++) coefficients[i] = polynomials[i].getCoefficients();
        return PersistentRecoilMath.peak(function.getKnots(), coefficients, absolute);
    }

    private static final class Capture {
        private final GunFireEvent event;
        private final LocalPlayer shotOwner;
        private final ClientLevel shotLevel;
        private final ResourceLocation shotGunId;
        private final int shotSlot;
        private PersistentRecoilMath.Peak pitch = PersistentRecoilMath.Peak.ZERO;
        private PersistentRecoilMath.Peak yaw = PersistentRecoilMath.Peak.ZERO;
        private boolean sampled;
        private Capture(GunFireEvent event, LocalPlayer player, ClientLevel clientLevel,
                        ResourceLocation id, int selectedSlot) {
            this.event = event;
            shotOwner = player;
            shotLevel = clientLevel;
            shotGunId = id;
            shotSlot = selectedSlot;
        }
        private boolean sameContext() {
            return owner == shotOwner && level == shotLevel && slot == shotSlot && shotGunId.equals(gunId);
        }
    }
}
