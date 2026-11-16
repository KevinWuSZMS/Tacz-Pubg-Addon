package com.example.examplemod.lean.mixin.client;

import com.example.examplemod.lean.client.LeanCameraAccess;
import com.example.examplemod.lean.client.LeanCameraView;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
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
abstract class LeanCameraMixin implements LeanCameraAccess {
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow public abstract Vec3 getPosition();
    @Shadow protected abstract void setPosition(Vec3 position);
    @Override
    public float taczPubg$interpolatedEyeHeight(float partialTick) { return Mth.lerp(partialTick, eyeHeightOld, eyeHeight); }
    @Inject(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", shift = At.Shift.AFTER))
    private void taczPubg$leanEye(BlockGetter level, Entity entity, boolean detached, boolean reverse, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        setPosition(LeanCameraView.cameraEye(player, getPosition()));
        // No camera roll. Third-person zoom collision runs after the corrected pivot is installed.
    }
}
