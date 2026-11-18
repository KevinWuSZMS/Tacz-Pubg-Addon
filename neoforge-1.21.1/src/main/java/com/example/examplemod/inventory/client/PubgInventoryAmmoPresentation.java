package com.example.examplemod.inventory.client;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Ammo type artwork/name only; WeaponHudData remains the authority for reserve count/infinity. */
public final class PubgInventoryAmmoPresentation {
    public record Ammo(ResourceLocation id, ItemStack icon, Component name) {
        public static final Ammo EMPTY = new Ammo(null, ItemStack.EMPTY, Component.empty());
        public Ammo {
            icon = icon.copy();
            name = name.copy();
        }
        public boolean present() { return id != null; }
    }

    private PubgInventoryAmmoPresentation() {}

    /**
     * Dummy, inventory-fed, and infinite ammunition still use the gun's configured ammo type.
     * A display stack is never read from, inserted into, or removed from the player's inventory.
     */
    public static Ammo read(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof IGun gun)) return Ammo.EMPTY;
        ResourceLocation gunId = gun.getGunId(stack);
        if (gunId == null) return Ammo.EMPTY;
        var index = TimelessAPI.getClientGunIndex(gunId).orElse(null);
        if (index == null || index.getGunData() == null) return Ammo.EMPTY;
        ResourceLocation ammoId = index.getGunData().getAmmoId();
        if (ammoId == null) return Ammo.EMPTY;
        var ammoIndex = TimelessAPI.getClientAmmoIndex(ammoId).orElse(null);
        // Missing packs keep their real identifier instead of inventing a caliber or rendering a missing model.
        if (ammoIndex == null || ammoIndex.getName() == null || ammoIndex.getName().isBlank())
            return new Ammo(ammoId, ItemStack.EMPTY, Component.literal(ammoId.toString()));
        ItemStack icon = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
        return new Ammo(ammoId, icon, icon.getHoverName());
    }
}
