package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.PubgMagazineCompatibility;
import com.example.examplemod.gunbalance.PubgMagazineRules;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An inactive or incompatible workbench item stays owned, but supplies no magazine level. */
@Mixin(value = AttachmentDataUtils.class, remap = false)
public abstract class AttachmentMagazineExperimentMixin {
    @Inject(method = "getMagExtendLevel(Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/resource/pojo/data/gun/GunData;)I",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void taczPubg$gateWorkbenchMagazineLevel(ItemStack gunStack, GunData data,
            CallbackInfoReturnable<Integer> callback) {
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null) return;
        ResourceLocation attachmentId = gun.getAttachmentId(gunStack, AttachmentType.EXTENDED_MAG);
        if (attachmentId != null && PubgMagazineRules.MAGAZINE_IDS.contains(attachmentId.toString())
                && !PubgMagazineCompatibility.isExperimentEnabled(gun.getGunId(gunStack), attachmentId)) {
            callback.setReturnValue(0);
        }
    }
}
