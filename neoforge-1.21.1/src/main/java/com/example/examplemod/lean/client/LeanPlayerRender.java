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
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Upper-body lean in world space, including vanilla crouch/prone root transforms. */
public final class LeanPlayerRender {
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
            // The dispatcher applies crouch's render offset before our entry matrix.
            // Restore it here so model vertices, legal eyes and hit geometry use feet
            // as the same world-space origin. ModelPart coordinates are pixels.
            Matrix4f modelToWorld = new Matrix4f().translation(0, player.isCrouching() ? -.125f * player.getScale() : 0, 0)
                    .mul(new Matrix4f(base).invert().mul(pose.last().pose())).scale(1f / 16);
            transformUpperBody(model.head, model.body, model.rightArm, model.leftArm,
                    modelToWorld, LeanGeometry.rig(LeanService.renderPose(player, partialTick), fraction));
            copy(model.hat, model.head);
            copy(model.jacket, model.body);
            copy(model.rightSleeve, model.rightArm);
            copy(model.leftSleeve, model.leftArm);
        }

        /** One waist transform preserves the native neck and shoulder joints, including slim arms. */
        void transformUpperBody(ModelPart head, ModelPart body, ModelPart rightArm, ModelPart leftArm,
                Matrix4f modelToWorld, LeanGeometry.Rig rig) {
            Matrix4f inverse = new Matrix4f(modelToWorld).invert();
            Vector3f pivot = inverse.transformPosition(rig.pivot().toVector3f());
            Vector3f translation = inverse.transformDirection(rig.seat().toVector3f());
            Vector3f axis = inverse.transformDirection(rig.axis().toVector3f()).normalize();
            Quaternionf roll = new Quaternionf().rotationAxis((float) rig.angle(), axis);
            transformAround(head, pivot, translation, roll);
            transformAround(body, pivot, translation, roll);
            transformAround(rightArm, pivot, translation, roll);
            transformAround(leftArm, pivot, translation, roll);
        }

        private void transformAround(ModelPart part, Vector3f pivot, Vector3f translation, Quaternionf roll) {
            saved.add(new SavedPart(part));
            Quaternionf nativeRotation = new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
            Quaternionf leanedRotation = new Quaternionf(roll).mul(nativeRotation);
            Vector3f position = roll.transform(new Vector3f(part.x, part.y, part.z).sub(pivot))
                    .add(pivot).add(translation);
            part.x = position.x;
            part.y = position.y;
            part.z = position.z;
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
