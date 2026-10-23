package com.example.examplemod.inventory;

import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.zones.match.MatchService;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class InventoryConfig {
    private static InventorySettings clientSettings = InventorySettings.defaults();
    private static boolean clientActive;
    private static int clientEquipmentLimit = 1;
    private static long clientRevision = -1;
    private InventoryConfig() {}
    public static boolean active(Player player) {
        return player != null && !player.isSpectator() && (player instanceof ServerPlayer serverPlayer
                ? MatchService.participantRunning(serverPlayer.server, player.getUUID()) : clientActive);
    }
    public static InventorySettings current(Player player) {
        return player instanceof ServerPlayer serverPlayer
                ? LootConfigStore.current(serverPlayer.server.overworld()).gunSettings().inventory() : clientSettings;
    }
    public static void accept(long revision, boolean active, InventorySettings settings) {
        accept(revision, active, settings, 1);
    }
    public static void accept(long revision, boolean active, InventorySettings settings, int equipmentLimit) {
        if (revision <= clientRevision) return;
        clientRevision = revision; clientActive = active; clientSettings = settings;
        clientEquipmentLimit = Math.clamp(equipmentLimit, 0, 64);
    }
    public static int clientEquipmentLimit() { return clientEquipmentLimit; }
    public static void clearClient() {
        clientSettings = InventorySettings.defaults(); clientActive = false; clientRevision = -1;
        clientEquipmentLimit = 1;
    }
    public static String key(ItemStack stack) {
        if (stack.getItem() instanceof IGun gun) return "gun:" + gun.getGunId(stack);
        if (stack.getItem() instanceof IAmmo ammo) return "ammo:" + ammo.getAmmoId(stack);
        if (stack.getItem() instanceof IAttachment attachment) return "attachment:" + attachment.getAttachmentId(stack);
        return "item:" + BuiltInRegistries.ITEM.getKey(stack.getItem());
    }
    public static double unitCapacity(Player player, ItemStack stack, double fallback) {
        return current(player).capacityOverrides().getOrDefault(key(stack), fallback);
    }
    public static int ammoStackLimit(Player player, ItemStack stack) {
        if (!active(player) || !(stack.getItem() instanceof IAmmo ammo)) return stack.getItem().getMaxStackSize(stack);
        return current(player).ammoStackLimits().getOrDefault(ammo.getAmmoId(stack).toString(), InventorySettings.DEFAULT_AMMO_STACK);
    }
    public static void normalizeOutsideRound(Player player) {
        MatchAmmoStacks.normalizeOutsideRound(player);
    }
}
