package com.example.examplemod.vitals.client;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.throwables.ThrowableKind;
import java.util.HashMap;
import java.util.Map;
import me.xjqsh.lrtactical.api.item.IThrowable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Shared throwable silhouettes; stable PUBG item identities take precedence over LR display aliases. */
public final class ThrowableHudIcons {
    private static final Map<ResourceLocation, ResourceLocation> PUBG = pubgIcons();
    private static final Map<ResourceLocation, ResourceLocation> LR = Map.of(
            ResourceLocation.parse("lrtactical:m67"), asset("frag_grenade"),
            ResourceLocation.parse("lrtactical:rgn"), asset("frag_grenade"),
            ResourceLocation.parse("lrtactical:flash_grenade"), asset("stun_grenade"),
            ResourceLocation.parse("lrtactical:smoke_grenade"), asset("smoke_grenade"),
            ResourceLocation.parse("lrtactical:molotov"), asset("molotov"),
            ResourceLocation.parse("lrtactical:c4"), asset("c4"));

    private ThrowableHudIcons() {}

    private static ResourceLocation asset(String name) {
        return ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID,
                "textures/gui/hud/kill_feed/throwables/" + name + ".png");
    }

    private static Map<ResourceLocation, ResourceLocation> pubgIcons() {
        var icons = new HashMap<ResourceLocation, ResourceLocation>();
        for (var kind : ThrowableKind.values())
            icons.put(ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, kind.id), asset(kind.id));
        return Map.copyOf(icons);
    }

    public static ResourceLocation texture(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof IThrowable throwable)) return null;
        return texture(BuiltInRegistries.ITEM.getKey(stack.getItem()), throwable.getId(stack));
    }

    /** Unknown identities deliberately return null so each caller can use the actual item icon. */
    public static ResourceLocation texture(ResourceLocation itemId, ResourceLocation assetId) {
        var stable = itemId == null ? null : PUBG.get(itemId);
        if (stable != null) return stable;
        // Native LR stacks share an item and distinguish their throwable through the asset ID.
        var nativeAsset = assetId == null ? null : LR.get(assetId);
        return nativeAsset != null ? nativeAsset : itemId == null ? null : LR.get(itemId);
    }
}
