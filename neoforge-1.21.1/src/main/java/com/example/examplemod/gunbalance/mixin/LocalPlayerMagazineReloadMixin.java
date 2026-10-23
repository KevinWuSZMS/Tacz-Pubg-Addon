package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.client.reload.WorkbenchMagazineReloadClient;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gameplay.LocalPlayerReload;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture the actual held gun before its sound and animation begin, including a rate-one reset. */
@Mixin(value = LocalPlayerReload.class, remap = false)
public abstract class LocalPlayerMagazineReloadMixin {
    @Inject(method = "doReload(Lcom/tacz/guns/api/item/IGun;Lcom/tacz/guns/client/resource/GunDisplayInstance;Lcom/tacz/guns/resource/pojo/data/gun/GunData;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), remap = false)
    private void taczPubg$captureReloadAnimationRate(IGun gun, GunDisplayInstance display,
            GunData data, ItemStack gunStack, CallbackInfo callback) {
        WorkbenchMagazineReloadClient.beginReload(display, gunStack);
    }
}
