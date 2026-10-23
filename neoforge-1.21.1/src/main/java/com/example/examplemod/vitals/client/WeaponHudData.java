package com.example.examplemod.vitals.client;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Read-only counterpart of TaCZ r7 GunHudOverlay's count rules, including open bolts and ammo boxes. */
public final class WeaponHudData {
    public record Gun(boolean present, int loaded, int capacity, int reserve, boolean infinite, FireMode mode, ResourceLocation texture) {
        public static final Gun EMPTY = new Gun(false, 0, 0, 0, false, FireMode.UNKNOWN, null);
        public String reserveText() { return infinite ? "∞" : Integer.toString(reserve); }
    }
    private WeaponHudData() {}
    public static int loaded(int magazine, int reserve, boolean inventoryAmmo, boolean chambered, boolean openBolt) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, inventoryAmmo ? reserve : magazine) + (chambered && !openBolt ? 1 : 0));
    }
    public static Gun read(LocalPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof IGun gun)) return Gun.EMPTY;
        var index = TimelessAPI.getClientGunIndex(gun.getGunId(stack)).orElse(null);
        if (index == null) return Gun.EMPTY;
        var data = index.getGunData();
        if (data == null || data.getReloadData() == null) return Gun.EMPTY;
        int reserve = 0;
        boolean infinite = !IGunOperator.fromLivingEntity(player).needCheckAmmo() || data.getReloadData().isInfinite();
        if (gun.useDummyAmmo(stack)) reserve = gun.getDummyAmmoAmount(stack);
        else for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack held = player.getInventory().getItem(i);
            if (held.getItem() instanceof IAmmo ammo && ammo.isAmmoOfGun(stack, held)) reserve = (int) Math.min(Integer.MAX_VALUE, (long) reserve + held.getCount());
            if (held.getItem() instanceof IAmmoBox box && box.isAmmoBoxOfGun(stack, held)) {
                if (box.isAllTypeCreative(held) || box.isCreative(held)) infinite = true;
                else reserve = (int) Math.min(Integer.MAX_VALUE, (long) reserve + box.getAmmoCount(held));
            }
        }
        if (infinite && gun.useInventoryAmmo(stack)) reserve = 9999;
        int loaded = loaded(gun.getCurrentAmmoCount(stack), reserve, gun.useInventoryAmmo(stack), gun.hasBulletInBarrel(stack), data.getBolt() == Bolt.OPEN_BOLT);
        return new Gun(true, loaded, AttachmentDataUtils.getAmmoCountWithAttachment(stack, data), reserve, infinite,
                gun.getFireMode(stack), TimelessAPI.getGunDisplay(stack).map(display -> display.getHUDTexture()).orElse(null));
    }
    public static ResourceLocation fireModeTexture(FireMode mode) {
        return ResourceLocation.fromNamespaceAndPath("tacz", "textures/hud/fire_mode_" + switch (mode) {
            case AUTO -> "auto"; case BURST -> "burst"; default -> "semi";
        } + ".png");
    }
}
