package com.example.examplemod.lean.client;

import java.util.ArrayList;
import java.util.List;

import com.example.examplemod.lean.LeanService;
import com.example.examplemod.lean.LeanGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Upper-body lean in world space, including vanilla crouch/prone root transforms. */
public final class LeanPlayerRender {
    private static final float MAX_ROLL = (float) LeanGeometry.ROLL_RADIANS;

    private LeanPlayerRender() {}

    public static final class Frame {
        private final Matrix4f base;
        private final List<SavedPart> saved = new ArrayList<>(8);

        public Frame(PoseStack pose) {
            base = new Matrix4f(pose.last().pose());
        }

        public void apply(Player player, PlayerModel<?> model, PoseStack pose, float partialTick) {
            float fraction = (float) LeanService.fraction(player, partialTick);
            if (Math.abs(fraction) < 0.00001f) return;
            // Removing the entry matrix leaves the actual body-yaw, scale and prone
            // transforms. Converting through its inverse also handles looking sideways.
            Matrix4f inverseBody = new Matrix4f(base).invert().mul(pose.last().pose()).invert();
            Vec3 offset = LeanGeometry.offset(player, fraction);
            Vector3f delta = inverseBody.transformDirection(
                    new Vector3f((float) offset.x, (float) offset.y, (float) offset.z)).mul(16);
            float yaw = (float) Math.toRadians(player.getYRot());
            Vector3f forward = inverseBody.transformDirection(
                    new Vector3f(-(float) Math.sin(yaw), 0, (float) Math.cos(yaw))).normalize();
            Quaternionf roll = new Quaternionf().rotationAxis(MAX_ROLL * fraction, forward);
            float armCenterX = model.rightArm.getInitialPose().y == 2.5f ? 0.5f : 1f;
            transform(model.head, new Vector3f(0, -4, 0), delta, 1, roll);
            transform(model.body, new Vector3f(0, 6, 0), delta, 0.5f, roll);
            transform(model.rightArm, new Vector3f(-armCenterX, 4, 0), delta, 0.7f, roll);
            transform(model.leftArm, new Vector3f(armCenterX, 4, 0), delta, 0.7f, roll);
            copy(model.hat, model.head);
            copy(model.jacket, model.body);
            copy(model.rightSleeve, model.rightArm);
            copy(model.leftSleeve, model.leftArm);
        }

        private void transform(ModelPart part, Vector3f center, Vector3f delta, float weight, Quaternionf roll) {
            saved.add(new SavedPart(part));
            center.mul(part.xScale, part.yScale, part.zScale);
            Quaternionf nativeRotation = new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
            Vector3f nativeCenter = nativeRotation.transform(new Vector3f(center));
            Quaternionf leanedRotation = new Quaternionf(roll).mul(nativeRotation);
            Vector3f leanedCenter = leanedRotation.transform(new Vector3f(center));
            // Rotate each solid about its actual center, then apply the same center
            // displacement as the gameplay hit geometry. Part origins are not centers.
            part.x += nativeCenter.x - leanedCenter.x + delta.x * weight;
            part.y += nativeCenter.y - leanedCenter.y + delta.y * weight;
            part.z += nativeCenter.z - leanedCenter.z + delta.z * weight;
            Vector3f euler = LeanRenderMath.modelPartEuler(leanedRotation);
            part.xRot = euler.x;
            part.yRot = euler.y;
            part.zRot = euler.z;
        }

        private void copy(ModelPart target, ModelPart source) {
            saved.add(new SavedPart(target));
            target.copyFrom(source);
        }

        public void restore() {
            for (SavedPart part : saved) part.restore();
            saved.clear();
        }
    }

    private record SavedPart(ModelPart part, PartPose pose, float scaleX, float scaleY, float scaleZ) {
        SavedPart(ModelPart part) {
            this(part, part.storePose(), part.xScale, part.yScale, part.zScale);
        }

        void restore() {
            part.loadPose(pose);
            part.xScale = scaleX;
            part.yScale = scaleY;
            part.zScale = scaleZ;
        }
    }
}
