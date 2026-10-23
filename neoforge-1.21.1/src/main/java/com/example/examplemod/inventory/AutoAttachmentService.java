package com.example.examplemod.inventory;

import com.example.examplemod.backpack.BackpackOwnership;
import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.backpack.BackpackWeights;
import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.PubgMagazineCompatibility;
import com.example.examplemod.gunbalance.reload.MagazineReloadTiming;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.util.AttachmentDataUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server-thread transactions over real gun/attachment stacks; no item is manufactured from an ID. */
public final class AutoAttachmentService {
    private AutoAttachmentService() {}

    public static boolean enabled(Player player) {
        return player instanceof ServerPlayer serverPlayer && serverPlayer.server.isSameThread()
                && player.isAlive() && InventoryConfig.active(player)
                && InventoryConfig.current(player).autoAttachmentEquip();
    }

    /**
     * Called before acquisition capacity checks. An attachment going directly into a gun has no
     * carried weight. The caller retains the exact unaccepted remainder in {@code incoming}.
     */
    public static int tryEquipPickedUp(Player player, ItemStack incoming) {
        if (!enabled(player) || !(incoming.getItem() instanceof IAttachment) || busy(player)) return 0;
        int before = incoming.getCount();
        for (int slot : gunPriority(player)) {
            if (incoming.isEmpty()) break;
            equipOne(player, slot, incoming);
        }
        return before - incoming.getCount();
    }

    /** Called after placing a newly acquired gun in its fixed gun slot, including an explicit swap. */
    public static void onGunAcquired(Player player, int gunSlot) {
        if (!enabled(player) || gunSlot < 0 || gunSlot > 2 || busy(player)) return;
        for (int slot = 3; slot < 36; slot++) {
            ItemStack attachment = player.getInventory().getItem(slot);
            if (attachment.getItem() instanceof IAttachment) equipOne(player, gunSlot, attachment);
        }
        refreshHeld(player, player.getInventory().getItem(gunSlot));
    }

    /**
     * Move compatible attachments into empty slots during an explicit gun swap. May operate on
     * the caller's two staged gun copies; the caller must commit BOTH copies, never just the new gun.
     * A loaded source magazine stays on its gun if removing it would leave more rounds than fit.
     */
    public static int transferCompatibleAttachments(Player player, ItemStack fromGun, ItemStack toGun) {
        if (!enabled(player) || busy(player)) return 0;
        return transfer(player, fromGun, toGun);
    }

    // Package visibility permits registry-backed GameTests of the real transaction independently
    // of creating a complete battle/plane session. Public entry points always enforce the rule gate.
    static boolean equipOne(Player player, int gunSlot, ItemStack incoming) {
        if (gunSlot < 0 || gunSlot > 2 || incoming.isEmpty()
                || !(incoming.getItem() instanceof IAttachment attachment)) return false;
        ItemStack gun = player.getInventory().getItem(gunSlot);
        if (gun == incoming || gun.getCount() != 1 || !(gun.getItem() instanceof IGun api)
                || api.hasAttachmentLock(gun)) return false;
        AttachmentType type = attachment.getType(incoming);
        if (!compatible(api, gun, incoming, type)) return false;
        ItemStack old = api.getAttachment(player.registryAccess(), gun, type);
        // An undecodable saved attachment must never be silently overwritten.
        if (old.isEmpty() && !DefaultAssets.isEmptyAttachmentId(api.getAttachmentId(gun, type))) return false;
        if (!old.isEmpty() && (old.getCount() != 1 || !magazineUpgrade(player, gun, old, incoming))) return false;

        ItemStack replacement = incoming.copyWithCount(1);
        ItemStack plannedGun = gun.copy();
        api.installAttachment(player.registryAccess(), plannedGun, replacement);
        if (!ItemStack.matches(replacement, api.getAttachment(player.registryAccess(), plannedGun, type))
                || !sameAmmunition(api, gun, plannedGun) || !fitsLoadedMagazine(plannedGun)) return false;

        boolean owned = owned(player, incoming);
        long used = BackpackService.used(player);
        long after = used + BackpackWeights.weight(player, plannedGun) - BackpackWeights.weight(player, gun)
                + BackpackWeights.weight(player, old) - (owned ? BackpackWeights.unit(player, incoming) : 0);
        if (after > BackpackService.capacity(player) && after > used) return false;
        int returnSlot = old.isEmpty() ? -1 : returnSlot(player, old, incoming);
        if (!old.isEmpty() && returnSlot < 0) return false;

        // No callbacks between validation and commit. Never call Inventory.add here: that hook is
        // our caller and would recursively auto-equip the returned old attachment.
        copyComponents(gun, plannedGun);
        incoming.shrink(1);
        if (!old.isEmpty()) {
            ItemStack destination = player.getInventory().getItem(returnSlot);
            if (destination.isEmpty()) player.getInventory().setItem(returnSlot, old);
            else destination.grow(old.getCount());
        }
        changed(player);
        refreshHeld(player, gun);
        return true;
    }

    static int transfer(Player player, ItemStack fromGun, ItemStack toGun) {
        if (fromGun == toGun || fromGun.getCount() != 1 || toGun.getCount() != 1
                || !(fromGun.getItem() instanceof IGun from) || !(toGun.getItem() instanceof IGun to)
                || from.hasAttachmentLock(fromGun) || to.hasAttachmentLock(toGun)) return 0;
        ItemStack source = fromGun.copy(), destination = toGun.copy();
        int moved = 0;
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue;
            ItemStack item = from.getAttachment(player.registryAccess(), source, type);
            if (item.getCount() != 1 || !compatible(to, destination, item, type)
                    || !DefaultAssets.isEmptyAttachmentId(to.getAttachmentId(destination, type))) continue;
            ItemStack nextSource = source.copy(), nextDestination = destination.copy();
            from.unloadAttachment(player.registryAccess(), nextSource, type);
            to.installAttachment(player.registryAccess(), nextDestination, item);
            if (!from.getAttachment(player.registryAccess(), nextSource, type).isEmpty()
                    || !DefaultAssets.isEmptyAttachmentId(from.getAttachmentId(nextSource, type))
                    || !ItemStack.matches(item, to.getAttachment(player.registryAccess(), nextDestination, type))
                    || !sameAmmunition(from, source, nextSource)
                    || !sameAmmunition(to, destination, nextDestination)
                    || !fitsLoadedMagazine(nextSource) || !fitsLoadedMagazine(nextDestination)) continue;
            source = nextSource;
            destination = nextDestination;
            moved++;
        }
        if (moved > 0) {
            copyComponents(fromGun, source);
            copyComponents(toGun, destination);
            // These references may be staged copies; only a real held stack may update the cache.
            refreshHeld(player, fromGun);
            refreshHeld(player, toGun);
        }
        return moved;
    }

    private static boolean compatible(IGun api, ItemStack gun, ItemStack attachment, AttachmentType type) {
        return type != AttachmentType.NONE && api.allowAttachmentType(gun, type)
                && DefaultAssets.isEmptyAttachmentId(api.getBuiltInAttachmentId(gun, type))
                && api.allowAttachment(gun, attachment);
    }

    static boolean magazineUpgrade(Player player, ItemStack gun, ItemStack old, ItemStack replacement) {
        if (!(old.getItem() instanceof IAttachment before) || !(replacement.getItem() instanceof IAttachment after)
                || before.getType(old) != AttachmentType.EXTENDED_MAG
                || after.getType(replacement) != AttachmentType.EXTENDED_MAG) return false;
        ResourceLocation oldId = before.getAttachmentId(old), newId = after.getAttachmentId(replacement);
        String family = magazineFamily(oldId);
        if (family == null || !family.equals(magazineFamily(newId)) || oldId.equals(newId)) return false;
        IGun api = (IGun) gun.getItem();
        var gunIndex = TimelessAPI.getCommonGunIndex(api.getGunId(gun)).orElse(null);
        if (gunIndex == null) return false;
        ItemStack planned = gun.copy();
        api.installAttachment(player.registryAccess(), planned, replacement.copyWithCount(1));
        int oldCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(gun, gunIndex.getGunData());
        int newCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(planned, gunIndex.getGunData());
        double oldRate = reloadRate(player, gun, oldId), newRate = reloadRate(player, gun, newId);
        // Higher native level alone is not enough: gun-specific capacities may be equal or smaller.
        // Quickdraw -> extended-quickdraw is NOT automatic: it trades 1.6x speed for 1.15x speed.
        return newCapacity >= oldCapacity && newRate >= oldRate
                && (newCapacity > oldCapacity || newRate > oldRate)
                && AttachmentDataUtils.getMagExtendLevel(planned, gunIndex.getGunData())
                        >= AttachmentDataUtils.getMagExtendLevel(gun, gunIndex.getGunData());
    }

    private static String magazineFamily(ResourceLocation id) {
        String path = id.getPath();
        if (id.getNamespace().equals("tacz")
                && path.matches("(light_|shotgun_|sniper_)?extended_mag_[123]"))
            return id.getNamespace() + ":" + path.substring(0, path.length() - 1);
        if (id.getNamespace().equals("tacz_pubg_workbench")
                && path.matches("(quickdraw|extended|extended_quickdraw)_mag_(rifle|smg|sniper)"))
            return id.getNamespace() + ":" + path.substring(path.lastIndexOf('_') + 1);
        return null; // Third-party naming is not evidence of an upgrade.
    }

    private static double reloadRate(Player player, ItemStack gun, ResourceLocation id) {
        return GunExperimentState.magazineEnabled(player.level())
                && PubgMagazineCompatibility.isExperimentEnabled(((IGun) gun.getItem()).getGunId(gun), id)
                ? MagazineReloadTiming.rateForId(id.toString()) : 1;
    }

    private static boolean sameAmmunition(IGun api, ItemStack before, ItemStack after) {
        return api.getCurrentAmmoCount(before) == api.getCurrentAmmoCount(after)
                && api.hasBulletInBarrel(before) == api.hasBulletInBarrel(after);
    }

    private static boolean fitsLoadedMagazine(ItemStack gun) {
        IGun api = (IGun) gun.getItem();
        return TimelessAPI.getCommonGunIndex(api.getGunId(gun))
                .map(index -> api.getCurrentAmmoCount(gun)
                        <= AttachmentDataUtils.getAmmoCountWithAttachment(gun, index.getGunData()))
                .orElse(false);
    }

    private static int returnSlot(Player player, ItemStack returning, ItemStack consumed) {
        var inv = player.getInventory();
        List<Integer> slots = new ArrayList<>(33);
        for (int i = 9; i < 36; i++) slots.add(i);
        for (int i = 3; i < 9; i++) if (InventoryConfig.current(player).hotbar().get(i).equals("free")) slots.add(i);
        for (int pass = 0; pass < 2; pass++) for (int slot : slots) {
            ItemStack target = inv.getItem(slot);
            int count = target.getCount() - (target == consumed ? 1 : 0);
            boolean empty = target.isEmpty() || count == 0;
            if (empty != (pass == 1)) continue;
            if (empty) return slot;
            if (ItemStack.isSameItemSameComponents(target, returning)
                    && (long) count + returning.getCount() <= Math.min(target.getMaxStackSize(), inv.getMaxStackSize(target)))
                return slot;
        }
        return -1;
    }

    private static boolean owned(Player player, ItemStack stack) {
        for (ItemStack carried : player.getInventory().items) if (carried == stack) return true;
        for (ItemStack carried : player.getInventory().offhand) if (carried == stack) return true;
        return player.containerMenu.getCarried() == stack || BackpackOwnership.isTemporaryInputStack(player, stack);
    }

    private static List<Integer> gunPriority(Player player) {
        List<Integer> slots = new ArrayList<>(3);
        int held = player.getInventory().selected;
        if (held >= 0 && held < 3 && player.getMainHandItem().getItem() instanceof IGun) slots.add(held);
        for (int slot = 0; slot < 3; slot++) if (!slots.contains(slot)) slots.add(slot);
        return slots;
    }

    public static boolean busy(Player player) {
        var operator = IGunOperator.fromLivingEntity(player);
        var holder = operator.getDataHolder();
        return player.isUsingItem()
                || player instanceof ServerPlayer serverPlayer
                    && com.example.examplemod.throwables.ThrowableService.cooking(serverPlayer)
                || holder.reloadTimestamp >= 0 || holder.reloadStateType.isReloading()
                || holder.isBolting || holder.isAiming || holder.chargeProgress > 0
                || operator.getSynDrawCoolDown() > 0 || operator.getSynShootCoolDown() > 0
                || operator.getSynMeleeCoolDown() > 0;
    }

    private static void copyComponents(ItemStack target, ItemStack planned) {
        for (var type : List.copyOf(target.getComponents().keySet())) if (!planned.has(type)) target.remove(type);
        target.applyComponents(planned.getComponents());
    }

    private static void changed(Player player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }

    private static void refreshHeld(Player player, ItemStack gun) {
        if (gun == player.getMainHandItem() && gun.getItem() instanceof IGun)
            AttachmentPropertyManager.postChangeEvent(player, gun);
    }
}
