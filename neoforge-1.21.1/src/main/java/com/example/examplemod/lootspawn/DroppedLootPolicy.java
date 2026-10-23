package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Shared scope, orientation and collision-surface rules for real TaCZ drops. */
public final class DroppedLootPolicy {
    public static final int NEVER_PICKUP = 32767;
    public static final String RESPAWN = "respawn";
    public static final String DROP = "drop";
    private static final double SURFACE_EPSILON = 1e-4;

    private DroppedLootPolicy() {}

    public static boolean isConvertible(ItemStack stack) {
        return !stack.isEmpty() && !(stack.getItem() instanceof BlockItem)
                && (stack.getItem() instanceof IGun || stack.getItem() instanceof IAmmo
                || stack.getItem() instanceof IAttachment);
    }

    public static boolean isConvertible(ItemStack stack, Level level) {
        return isConvertible(stack) || !stack.isEmpty() && DroppedLootCatalog.contains(stack, level);
    }

    /** Same world yaw as the player's view, plus one bounded random deviation. */
    public static float tossYaw(float viewYaw, double unitRandom) {
        if (!Float.isFinite(viewYaw) || !Double.isFinite(unitRandom) || unitRandom < 0 || unitRandom > 1)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.dropped_loot_policy.invalid_throw_angle_input"));
        return stableYaw(viewYaw + (float) (unitRandom * 30 - 15));
    }

    public static float yaw(ItemEntity entity) {
        return stableYaw(entity.getYRot());
    }

    public static float stableYaw(float yaw) {
        if (!Float.isFinite(yaw)) return 0;
        return (yaw % 360 + 360) % 360;
    }

    public static boolean legalQuantity(int quantity) {
        return quantity > 0 && quantity <= LootPileQuantities.MAX_QUANTITY;
    }

    public static boolean legalDelay(int pickupDelay) {
        return pickupDelay >= 0 && pickupDelay < NEVER_PICKUP;
    }

    /** Engine collision boxes are world-space; do not round a half slab up a block. */
    public static boolean supports(AABB itemBox, AABB supportBox) {
        return Math.abs(itemBox.minY - supportBox.maxY) <= SURFACE_EPSILON
                && itemBox.maxX > supportBox.minX + 1e-7 && itemBox.minX < supportBox.maxX - 1e-7
                && itemBox.maxZ > supportBox.minZ + 1e-7 && itemBox.minZ < supportBox.maxZ - 1e-7;
    }

    public static String restoredOrigin(boolean present, String origin) {
        if (!present) return RESPAWN;
        if (RESPAWN.equals(origin) || DROP.equals(origin)) return origin;
        throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.dropped_loot_policy.unknown_ground_loot_source", origin));
    }
}
