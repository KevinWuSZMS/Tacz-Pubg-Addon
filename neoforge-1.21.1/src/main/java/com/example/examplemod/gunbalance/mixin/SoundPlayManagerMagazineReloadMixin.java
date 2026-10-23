package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.client.reload.WorkbenchMagazineReloadClient;
import com.example.examplemod.gunbalance.reload.MagazineReloadTiming;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.sound.GunSoundInstance;
import com.tacz.guns.client.sound.SoundPlayManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Whole reload clips use the same fixed rate as the active reload animation context. */
@Mixin(value = SoundPlayManager.class, remap = false)
public abstract class SoundPlayManagerMagazineReloadMixin {
    @Redirect(method = "playReloadSound(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;Z)V",
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/client/sound/SoundPlayManager;playClientSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)Lcom/tacz/guns/client/sound/GunSoundInstance;"),
            remap = false)
    private static GunSoundInstance taczPubg$shortenWholeReloadClip(Entity entity, ResourceLocation name,
            float volume, float pitch, int distance, LivingEntity shooter, GunDisplayInstance display, boolean noAmmo) {
        double rate = WorkbenchMagazineReloadClient.rate(display);
        float adjustedPitch = MagazineReloadTiming.accelerated(rate) ? (float) (pitch * rate) : pitch;
        return WorkbenchMagazineReloadClient.rememberReloadSound(
                SoundPlayManager.playClientSound(entity, name, volume, adjustedPitch, distance), rate);
    }

    @Inject(method = "playAnimationSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)Lcom/tacz/guns/client/sound/GunSoundInstance;",
            at = @At("RETURN"), remap = false)
    private static void taczPubg$recordOnlyAcceleratedReloadSound(Entity entity, ResourceLocation name,
            float volume, float pitch, int distance, CallbackInfoReturnable<GunSoundInstance> callback) {
        WorkbenchMagazineReloadClient.rememberAnimationSound(callback.getReturnValue());
    }
}
