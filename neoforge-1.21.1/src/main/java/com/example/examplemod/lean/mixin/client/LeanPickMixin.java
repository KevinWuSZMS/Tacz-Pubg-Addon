package com.example.examplemod.lean.mixin.client;

import com.example.examplemod.lean.LeanService;
import com.example.examplemod.lean.client.LeanCameraView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class LeanPickMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void taczPubg$beginLeanFrame(DeltaTracker tracker, CallbackInfo ci) {
        var minecraft = Minecraft.getInstance();
        Entity cameraEntity = minecraft.getCameraEntity() == null ? minecraft.player : minecraft.getCameraEntity();
        if (cameraEntity instanceof Player player) {
            float partialTick = player.level().tickRateManager().isEntityFrozen(player) ? 1
                    : tracker.getGameTimeDeltaPartialTick(true);
            LeanService.beginRender(player, partialTick);
            LeanCameraView.begin(player, minecraft.gameRenderer.getMainCamera(), partialTick);
        } else LeanCameraView.clear();
    }
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void taczPubg$endLeanFrame(DeltaTracker tracker, CallbackInfo ci) {
        if (Minecraft.getInstance().getCameraEntity() instanceof Player player) LeanService.endRender(player);
        LeanCameraView.end();
    }
    @Redirect(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 taczPubg$pickEye(Entity entity, float partialTick) {
        return entity instanceof Player player ? LeanCameraView.pickEye(player, partialTick) : entity.getEyePosition(partialTick);
    }
    @Redirect(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 taczPubg$pickDirection(Entity entity, float partialTick) {
        return entity.getViewVector(LeanCameraView.partialTick(entity, partialTick));
    }
    @Redirect(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
    private HitResult taczPubg$pickBlock(Entity entity, double distance, float partialTick, boolean fluids) {
        if (!(entity instanceof Player player)) return entity.pick(distance, partialTick, fluids);
        partialTick = LeanCameraView.partialTick(entity, partialTick);
        Vec3 eye = LeanCameraView.pickEye(player, partialTick);
        return entity.level().clip(new ClipContext(eye, eye.add(entity.getViewVector(partialTick).scale(distance)),
                ClipContext.Block.OUTLINE, fluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, entity));
    }
}
