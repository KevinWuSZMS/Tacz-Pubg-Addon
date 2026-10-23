package com.example.examplemod.gunbalance.client.reload;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.reload.MagazineReloadTiming;
import com.example.examplemod.gunbalance.reload.WorkbenchMagazineReload;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.ObjectAnimationSoundChannel;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import com.tacz.guns.api.client.animation.statemachine.AnimationStateContext;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.client.animation.statemachine.GunAnimationConstant;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import com.tacz.guns.client.gameplay.LocalPlayerReload;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.sound.GunSoundInstance;
import com.tacz.guns.client.sound.SoundPlayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/** Client-only timing changes for the exact workbench IDs selected at reload start. */
@OnlyIn(Dist.CLIENT)
public final class WorkbenchMagazineReloadClient {
    private static final LuaValue INTRO_SHELL_TIME = LuaValue.valueOf("intro_shell_ejecting_time");
    private static final Set<WorkbenchReloadAnimationContext> CONTEXTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<ObjectAnimationRunner, WeakReference<WorkbenchReloadAnimationContext>> RUNNERS = new WeakHashMap<>();
    private static final Set<GunSoundInstance> SOUNDS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> RECORD_ANIMATION_SOUNDS = ThreadLocal.withInitial(() -> false);

    private WorkbenchMagazineReloadClient() {}

    public static void beginReload(GunDisplayInstance display, ItemStack gunStack) {
        var machine = display.getAnimationStateMachine();
        if (machine == null) return;
        var context = machine.getContext();
        if (context instanceof WorkbenchReloadAnimationContext snapshot) {
            double rate = stackRate(gunStack);
            snapshot.taczPubg$beginReloadAnimation(rate);
            if (MagazineReloadTiming.accelerated(rate)) CONTEXTS.add(snapshot);
            else CONTEXTS.remove(snapshot);
        }
    }

    private static double stackRate(ItemStack gunStack) {
        var player = Minecraft.getInstance().player;
        return player == null || !GunExperimentState.clientMagazineEnabled()
                ? 1.0 : WorkbenchMagazineReload.rate(gunStack, player.level());
    }

    public static double rate(AnimationStateContext context) {
        if (!(context instanceof GunAnimationStateContext gunContext)
                || !(context instanceof WorkbenchReloadAnimationContext snapshot)) return 1.0;
        if (!GunExperimentState.clientMagazineEnabled()) {
            snapshot.taczPubg$clearReloadAnimation();
            return 1.0;
        }
        double rate = snapshot.taczPubg$getReloadAnimationRate();
        if (!MagazineReloadTiming.accelerated(rate)) return 1.0;

        // Refitting during an active reload keeps its snapshot; after it finishes, a new attachment cannot inherit it.
        double liveRate = stackRate(snapshot.taczPubg$getReloadAnimationGunItem());
        if (gunContext.getReloadStateType() == ReloadState.StateType.NOT_RELOADING.ordinal()
                && Double.compare(liveRate, rate) != 0) {
            snapshot.taczPubg$clearReloadAnimation();
            return 1.0;
        }

        var machine = context.getStateMachine();
        if (machine != null) {
            snapshot.taczPubg$retireReloadAnimationTracks(track -> {
                ObjectAnimationRunner runner = machine.getAnimationController().getAnimation(track);
                if (runner == null) return true;
                ObjectAnimationRunner target = runner.getTransitionTo() == null ? runner : runner.getTransitionTo();
                // HOLD may be the boundary between reload loop clips, so only STOP retires a reload clip.
                return target.isStopped() || !isReloadAnimation(target.getAnimation().name);
            });
        }
        rate = snapshot.taczPubg$getReloadAnimationRate();
        return MagazineReloadTiming.accelerated(rate) ? rate : 1.0;
    }

    public static double rate(GunDisplayInstance display) {
        var machine = display.getAnimationStateMachine();
        return machine == null ? 1.0 : rate(machine.getContext());
    }

    public static boolean isReloadAnimation(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("reload");
    }

    public static float transitionTime(AnimationStateContext context, String name, float original) {
        double rate = rate(context);
        return isReloadAnimation(name) && MagazineReloadTiming.accelerated(rate)
                ? (float) (original / rate) : original;
    }

    public static void bindRunner(AnimationStateContext context, String name, int track) {
        if (!(context instanceof GunAnimationStateContext)
                || !(context instanceof WorkbenchReloadAnimationContext reloadContext) || track < 0) return;
        var machine = context.getStateMachine();
        if (machine == null) return;
        ObjectAnimationRunner runner = machine.getAnimationController().getAnimation(track);
        if (runner == null) return;
        ObjectAnimationRunner target = runner.getTransitionTo() == null ? runner : runner.getTransitionTo();
        // A missing prototype leaves the previous runner in place; never mark that old animation.
        if (!name.equals(target.getAnimation().name)) return;
        if (isReloadAnimation(name)) {
            double rate = rate(context);
            if (MagazineReloadTiming.accelerated(rate) && target instanceof WorkbenchReloadAnimationRate snapshot) {
                snapshot.taczPubg$setReloadAnimationRate(rate);
                reloadContext.taczPubg$bindReloadAnimationTrack(track);
                RUNNERS.put(target, new WeakReference<>(reloadContext));
            }
        } else {
            reloadContext.taczPubg$finishReloadAnimationTrack(track);
        }
    }

    /** Effective runner timing always follows the server-confirmed gate; enabling cannot restore an old snapshot. */
    public static double runnerRate(WorkbenchReloadAnimationRate runner) {
        if (!GunExperimentState.clientMagazineEnabled()) {
            runner.taczPubg$setReloadAnimationRate(1.0);
            return 1.0;
        }
        double rate = runner.taczPubg$getReloadAnimationRate();
        return MagazineReloadTiming.accelerated(rate) ? rate : 1.0;
    }

    /** Keep the native keyframe scheduler, and identify only the sounds emitted by this accelerated runner. */
    public static void playAnimationSounds(ObjectAnimationSoundChannel channel, double from, double to,
            Entity entity, int distance, float volume, float pitch, WorkbenchReloadAnimationRate runner) {
        double rate = runnerRate(runner);
        boolean record = MagazineReloadTiming.accelerated(rate);
        boolean previous = RECORD_ANIMATION_SOUNDS.get();
        RECORD_ANIMATION_SOUNDS.set(record);
        try {
            channel.playSound(from, to, entity, distance, volume, record ? (float) (pitch * rate) : pitch);
        } finally {
            RECORD_ANIMATION_SOUNDS.set(previous);
        }
    }

    public static void rememberAnimationSound(GunSoundInstance sound) {
        if (sound != null && RECORD_ANIMATION_SOUNDS.get() && GunExperimentState.clientMagazineEnabled()) {
            SOUNDS.add(sound);
        }
    }

    public static GunSoundInstance rememberReloadSound(GunSoundInstance sound, double rate) {
        if (sound != null && MagazineReloadTiming.accelerated(rate) && GunExperimentState.clientMagazineEnabled()) {
            SOUNDS.add(sound);
        }
        return sound;
    }

    /** Called on the client thread after a disabled confirmation for the current connection. */
    public static void cancelActive() {
        cancelActive(true);
    }

    /** Connection lifecycle cleanup never sends an old player's cancel packet to a newly installed connection. */
    public static void clear() {
        cancelActive(false);
    }

    private static void cancelActive(boolean sendNativeCancel) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        GunDisplayInstance display = player == null ? null : TimelessAPI.getGunDisplay(player.getMainHandItem()).orElse(null);
        var machine = display == null ? null : display.getAnimationStateMachine();
        var context = machine == null ? null : machine.getContext();
        if (player != null && context instanceof WorkbenchReloadAnimationContext snapshot && CONTEXTS.contains(snapshot)) {
            boolean reloading = IGunOperator.fromLivingEntity(player).getSynReloadState().getStateType().isReloading();
            if (reloading || hasActiveRunner(context, snapshot)) {
                if (sendNativeCancel && reloading && minecraft.getConnection() != null) {
                    // TaCZ's public local helper sends its normal cancel packet and performs its Lua cancel action.
                    new LocalPlayerReload(IClientPlayerGunOperator.fromLocalPlayer(player).getDataHolder(), player).cancelReload();
                } else {
                    // Lifecycle cleanup is local only; the server may also have cleared its state before confirmation.
                    machine.trigger(GunAnimationConstant.INPUT_CANCEL_RELOAD);
                }
                SoundPlayManager.stopPlayGunSound();
            }
        }

        // Keyframe clips are not held by TaCZ's tmpSoundInstance; stop only instances recorded for our six IDs.
        for (GunSoundInstance sound : new ArrayList<>(SOUNDS)) sound.setStop();
        for (ObjectAnimationRunner runner : new ArrayList<>(RUNNERS.keySet())) {
            if (runner instanceof WorkbenchReloadAnimationRate snapshot) snapshot.taczPubg$setReloadAnimationRate(1.0);
            // Preserve any newly created, unmarked native reload_end transition target.
            runner.stop();
        }
        for (WorkbenchReloadAnimationContext snapshot : new ArrayList<>(CONTEXTS)) snapshot.taczPubg$clearReloadAnimation();
        SOUNDS.clear();
        RUNNERS.clear();
        CONTEXTS.clear();
    }

    private static boolean hasActiveRunner(AnimationStateContext context, WorkbenchReloadAnimationContext owner) {
        var machine = context.getStateMachine();
        if (machine == null) return false;
        var controller = machine.getAnimationController();
        Iterable<Integer> tracks = controller.getUpdatingTrackArray();
        if (tracks == null) return false;
        for (int track : tracks) {
            ObjectAnimationRunner runner = controller.getAnimation(track);
            if (isActiveRunner(runner, owner) || (runner != null && isActiveRunner(runner.getTransitionTo(), owner))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isActiveRunner(ObjectAnimationRunner runner, WorkbenchReloadAnimationContext owner) {
        if (runner == null || runner.isStopped()) return false;
        WeakReference<WorkbenchReloadAnimationContext> registeredOwner = RUNNERS.get(runner);
        return registeredOwner != null && registeredOwner.get() == owner
                && (runner.isRunning() || runner.isPausing() || runner.isHolding() || runner.isTransitioning());
    }

    public static LuaTable stateMachineParams(LuaTable original, double rate) {
        if (original == null || !MagazineReloadTiming.accelerated(rate)) return original;
        LuaValue introTime = original.get(INTRO_SHELL_TIME);
        if (introTime.type() != LuaValue.TNUMBER) return original;

        // Display parameters are shared between contexts; copy top-level entries and preserve its metatable.
        LuaTable copy = new LuaTable();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs entry = original.next(key);
            key = entry.arg1();
            if (key.isnil()) break;
            copy.rawset(key, entry.arg(2));
        }
        copy.setmetatable(original.getmetatable());
        copy.rawset(INTRO_SHELL_TIME, LuaValue.valueOf(introTime.checkdouble() / rate));
        return copy;
    }
}
