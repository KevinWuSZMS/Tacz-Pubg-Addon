package com.example.examplemod.gunbalance.reload;

import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.PubgMagazineCompatibility;
import com.mojang.logging.LogUtils;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.entity.sync.ModSyncedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class WorkbenchMagazineReload {
    private WorkbenchMagazineReload() {}

    /** A call without its logical world cannot establish server authorization. */
    public static double rate(ItemStack gunStack) { return 1.0; }

    public static double rate(ItemStack gunStack, Level level) {
        if (!GunExperimentState.magazineEnabled(level)) return 1.0;
        if (gunStack == null || gunStack.isEmpty()) return 1.0;
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null) return 1.0;
        ResourceLocation attachmentId = gun.getAttachmentId(gunStack, AttachmentType.EXTENDED_MAG);
        if (attachmentId == null || !PubgMagazineCompatibility.isExperimentEnabled(
                gun.getGunId(gunStack), attachmentId)) return 1.0;
        return MagazineReloadTiming.rateForId(attachmentId.toString());
    }

    public static double snapshot(ShooterDataHolder holder, Level level) {
        if (holder == null || holder.reloadTimestamp == -1 || !(holder instanceof WorkbenchReloadRate state)) return 1.0;
        if (!GunExperimentState.magazineEnabled(level) || state.taczPubg$getReloadGunId() == null
                || state.taczPubg$getReloadMagazineId() == null || !PubgMagazineCompatibility.isExperimentEnabled(
                        state.taczPubg$getReloadGunId(), state.taczPubg$getReloadMagazineId()))
            return 1.0;
        return state.taczPubg$getMagazineReloadRate();
    }

    /** Snapshot both identities so a refit cannot rewind a reload already past its feed point. */
    public static void begin(ShooterDataHolder holder, ItemStack gunStack, Level level) {
        if (!(holder instanceof WorkbenchReloadRate state)) return;
        IGun gun = gunStack == null || gunStack.isEmpty() ? null : IGun.getIGunOrNull(gunStack);
        state.taczPubg$setReloadMagazineSelection(gun == null ? null : gun.getGunId(gunStack),
                gun == null ? null : gun.getAttachmentId(gunStack, AttachmentType.EXTENDED_MAG));
        state.taczPubg$setMagazineReloadRate(rate(gunStack, level));
    }

    /** Exact six quick-mag IDs, deliberately independent of a gate that is being switched off. */
    public static boolean hasAcceleratingMagazine(ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty()) return false;
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null) return false;
        ResourceLocation attachmentId = gun.getAttachmentId(gunStack, AttachmentType.EXTENDED_MAG);
        return attachmentId != null && MagazineReloadTiming.accelerated(
                MagazineReloadTiming.rateForId(attachmentId.toString()));
    }

    /**
     * Called on the server thread before withdrawing the magazine profile. Do not change a running
     * accelerated clock back to rate 1: crossing a feed threshold twice could consume ammunition twice.
     * Only reload state is cleared; the gun's ammunition, attachment and inventory are left intact.
     */
    public static int cancelActive(MinecraftServer server) {
        if (server == null) return 0;
        int cancelled = 0;
        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity shooter)) continue;
            IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
            ShooterDataHolder holder = operator.getDataHolder();
            if (holder == null || holder.reloadTimestamp == -1 || holder.reloadStateType == null
                    || !holder.reloadStateType.isReloading()) continue;
            boolean capturedQuick = holder instanceof WorkbenchReloadRate state
                    && MagazineReloadTiming.accelerated(state.taczPubg$getMagazineReloadRate())
                    && state.taczPubg$getReloadMagazineId() != null
                    && MagazineReloadTiming.accelerated(MagazineReloadTiming.rateForId(
                            state.taczPubg$getReloadMagazineId().toString()));
            if (!capturedQuick && (holder.currentGunItem == null
                    || !hasAcceleratingMagazine(holder.currentGunItem.get()))) continue;
            try {
                // Let a custom gun script perform its native interrupt cleanup first.
                operator.cancelReload();
            } catch (RuntimeException error) {
                LogUtils.getLogger().warn("Cannot interrupt workbench magazine reload for entity {}", shooter.getId(), error);
            } finally {
                // The r7 default interrupt has no reset implementation, so this is required as well.
                holder.reloadTimestamp = -1;
                holder.reloadStateType = ReloadState.StateType.NOT_RELOADING;
                if (holder instanceof WorkbenchReloadRate state) {
                    state.taczPubg$setMagazineReloadRate(1.0);
                    state.taczPubg$setReloadMagazineSelection(null, null);
                }
                ModSyncedEntityData.RELOAD_STATE_KEY.setValue(shooter, new ReloadState());
            }
            cancelled++;
        }
        return cancelled;
    }
}
