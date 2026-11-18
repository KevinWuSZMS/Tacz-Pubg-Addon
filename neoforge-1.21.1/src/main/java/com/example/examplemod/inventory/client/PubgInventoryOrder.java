package com.example.examplemod.inventory.client;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** Screenshot-confirmed medicine order; remaining groups are an explicit draft baseline. */
public final class PubgInventoryOrder {
    private PubgInventoryOrder() {}
    public static int medicine(String path) {
        return switch (path) {
            case "med_kit" -> 0; case "first_aid_kit" -> 1; case "bandage" -> 2;
            case "adrenaline_syringe" -> 3; case "painkiller" -> 4; case "energy_drink" -> 5;
            default -> -1;
        };
    }
    public static int rank(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        int medicine = id.getNamespace().equals("tacz_pubg") ? medicine(id.getPath()) : -1;
        if (medicine >= 0) return medicine;
        if (stack.getItem() instanceof IAttachment attachment) {
            String name = attachment.getAttachmentId(stack).getPath();
            return switch (attachment.getType(stack)) {
                case SCOPE -> 100 + optic(name);
                case GRIP -> 200; case MUZZLE -> 210; case EXTENDED_MAG -> 220;
                case STOCK -> 230; case LASER -> 240; default -> 250;
            };
        }
        if (stack.getItem() instanceof com.example.examplemod.throwables.PubgThrowableItem
                || stack.getItem() instanceof me.xjqsh.lrtactical.api.item.IThrowable) return 300;
        if (stack.getItem() instanceof ArmorItem
                || stack.getItem() instanceof com.example.examplemod.backpack.BackpackItem) return 400;
        if (stack.getItem() instanceof IAmmo) return 500;
        if (stack.getItem() instanceof IGun) return 600;
        if (stack.getItem() instanceof BlockItem) return 800;
        return 700;
    }
    private static int optic(String path) {
        // Gun packs use their own IDs; unknown optics keep a stable fallback position.
        if (path.matches(".*(15x|x15|15_x|scope_15).*")) return 0;
        if (path.matches(".*(8x|x8|8_x|scope_8).*")) return 1;
        if (path.matches(".*(6x|x6|6_x|scope_6).*")) return 2;
        if (path.matches(".*(4x|x4|4_x|scope_4).*")) return 3;
        if (path.matches(".*(3x|x3|3_x|scope_3).*")) return 4;
        if (path.matches(".*(2x|x2|2_x|scope_2).*")) return 5;
        if (path.contains("holo")) return 6;
        if (path.contains("red") || path.contains("dot")) return 7;
        return 8;
    }
}
