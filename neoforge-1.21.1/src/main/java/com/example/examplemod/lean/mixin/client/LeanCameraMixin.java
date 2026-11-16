package com.example.examplemod.lean.mixin.client;

import com.example.examplemod.lean.LeanService;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class LeanCameraMixin {
    @Shadow protected abstract void setPosition(Vec3 position);
    @Inject(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", shift = At.Shift.AFTER))
    private void taczPubg$leanEye(BlockGetter level, Entity entity, boolean detached, boolean reverse, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        Vec3 offset = LeanService.offset(player, partialTick);
        if (offset.lengthSqr() > 1.0E-12) setPosition(player.getEyePosition(partialTick).add(offset));
        // No camera roll. Third-person zoom collision runs after the corrected pivot is installed.
    }
}
