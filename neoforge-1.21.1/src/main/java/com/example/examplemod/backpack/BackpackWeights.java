package com.example.examplemod.backpack;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.example.examplemod.armor.PubgArmorItem;
import me.xjqsh.lrtactical.api.item.IThrowable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import java.util.Locale;

public final class BackpackWeights {
    private BackpackWeights() {}

    public static int weight(ItemStack stack) { return weight(null, stack, 0); }
    public static int unit(ItemStack stack) { return stack.isEmpty() ? 0 : weight(stack.copyWithCount(1)); }
    public static int weight(Player player, ItemStack stack) { return weight(player, stack, 0); }
    public static int unit(Player player, ItemStack stack) { return stack.isEmpty() ? 0 : weight(player, stack.copyWithCount(1)); }

    private static int weight(Player player, ItemStack stack, int depth) {
        if (stack.isEmpty()) return 0;
        if (depth >= 8) return BackpackRules.SATURATED;
        int each = basic(stack);
        if (player != null) {
            double configured = com.example.examplemod.inventory.InventoryConfig.unitCapacity(player, stack, each / 1000.0);
            each = (int) Math.clamp(Math.round(configured * 1000), 0, BackpackRules.SATURATED);
        }
        // Storage containers carry their contents' weight. A backpack item itself stores no inventory.
        var container = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        for (ItemStack nested : container.nonEmptyItems()) each = BackpackRules.add(each, weight(player, nested, depth + 1));
        var bundle = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        for (ItemStack nested : bundle.items()) each = BackpackRules.add(each, weight(player, nested, depth + 1));
        return BackpackRules.add(0, (long) each * stack.getCount());
    }

    private static int basic(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        // pubg.gs ordinary PC item space, build 26563649 (2026-11-11).
        // Equipped armor/backpacks are excluded by BackpackService.used, not here.
        if (stack.getItem() instanceof BackpackItem) return 400_000;
        if (stack.getItem() instanceof PubgArmorItem armor)
            return armor.helmet() ? (30 + 10 * armor.tier()) * 1000 : (90 + 30 * armor.tier()) * 1000;
        if (stack.getItem() instanceof IAmmo ammo) return ammoWeight(ammo.getAmmoId(stack).getPath());
        if (stack.getItem() instanceof IAttachment attachment)
            return PubgAttachmentWeights.unit(attachment.getAttachmentId(stack).toString());
        // The match inventory policy permits only its two primary slots and one sidearm.
        // Explicit per-gun capacity overrides still apply above this default.
        if (stack.getItem() instanceof IGun || stack.getItem() instanceof CrossbowItem) return 0;
        // Vanilla arrows are this mod's crossbow ammunition equivalent.
        if (stack.getItem() instanceof ArrowItem) return 2_000;
        int itemWeight = switch (id) {
            case "tacz_pubg:bandage" -> 2_000;
            case "tacz_pubg:first_aid_kit" -> 10_000;
            case "tacz_pubg:med_kit", "tacz_pubg:medical_kit" -> 15_000;
            case "tacz_pubg:energy_drink" -> 4_000;
            case "tacz_pubg:painkiller" -> 6_000;
            case "tacz_pubg:adrenaline_syringe" -> 10_000;
            case "tacz_pubg:frag_grenade" -> 27_000;
            case "tacz_pubg:smoke_grenade" -> 14_000;
            case "tacz_pubg:stun_grenade" -> 12_000;
            case "tacz_pubg:molotov" -> 16_000;
            case "tacz_pubg:sticky_bomb" -> 20_000;
            case "tacz_pubg:c4" -> 35_000;
            case "tacz_pubg:blue_zone_grenade", "tacz_pubg:bz_grenade" -> 23_000;
            default -> -1;
        };
        if (itemWeight >= 0) return itemWeight;
        // Match our own items first: sticky/BZ reuse LR's RGN/M67 visual assets.
        if (stack.getItem() instanceof IThrowable throwable) return switch (throwable.getId(stack).toString()) {
            case "lrtactical:m67", "lrtactical:rgn" -> 27_000;
            case "lrtactical:flash_grenade" -> 12_000;
            case "lrtactical:smoke_grenade" -> 14_000;
            case "lrtactical:molotov" -> 16_000;
            case "lrtactical:c4" -> 35_000;
            default -> 1_000;
        };
        return 1_000; // Explicit Minecraft adaptation: ordinary items cost one unit each.
    }

    static int ammoWeight(String path) {
        String p = path.toLowerCase(Locale.ROOT).replace("_", "").replace(".", "").replace("-", "");
        if (p.contains("9mm") || p.contains("9x19")) return 300;
        if (p.contains("45acp")) return 400;
        if (p.equals("57mm") || p.equals("ammo57mm") || p.startsWith("57x28")) return 350;
        if (p.contains("556") || p.contains("545")) return 500;
        if (p.contains("762") || p.equals("308")) return 600;
        if (p.contains("12g") || p.contains("12x70")) return 1_250;
        if (p.contains("300") || p.contains("338") || p.contains("50bmg")) return 1_000;
        if (p.contains("bolt") || p.contains("arrow")) return 2_000;
        if (p.contains("rocket") || p.contains("rpg") || p.contains("40mm")) return 20_000;
        return 500;
    }
}
