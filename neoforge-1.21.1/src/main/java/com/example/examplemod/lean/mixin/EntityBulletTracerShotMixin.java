package com.example.examplemod.lean.mixin;

import com.tacz.guns.client.renderer.entity.EntityBulletRenderer;
import com.tacz.guns.entity.EntityKineticBullet;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Draw the synchronized projectile trajectory; looking or leaning after firing cannot move it. */
@Mixin(value = EntityBulletRenderer.class, remap = false)
public abstract class EntityBulletTracerShotMixin {
    @Redirect(method = "lambda$renderTracerAmmo$1", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/entity/EntityKineticBullet;getFirstPersonRenderOffset()Lorg/joml/Vector3f;"), remap = false)
    private Vector3f taczPubg$usePhysicalTrajectory(EntityKineticBullet bullet) {
        return new Vector3f();
    }

    @ModifyArg(method = "lambda$renderTracerAmmo$1", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 1), index = 1, remap = false)
    private double taczPubg$removeThirdPersonDrop(double visualDrop) { return 0; }
}
