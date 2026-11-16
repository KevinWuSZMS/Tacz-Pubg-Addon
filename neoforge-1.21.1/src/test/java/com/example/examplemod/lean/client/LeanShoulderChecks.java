package com.example.examplemod.lean.client;

import com.example.examplemod.lean.LeanGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Actual ModelPart mutation checked against independently composed world matrices. */
public final class LeanShoulderChecks {
    private static int checks;
    public static void run() {
        for (boolean slim : new boolean[]{false, true}) for (float fraction : new float[]{-1, -.5f, 0, .5f, 1}) {
            ModelPart head = part(0, 0, 0), body = part(0, 0, 0);
            ModelPart right = part(-5, slim ? 2.5f : 2, 0), left = part(5, slim ? 2.5f : 2, 0);
            float shoulderTop = right.y - 2;
            var frame = new LeanPlayerRender.Frame(new PoseStack());
            frame.transformUpperBody(head, body, right, left, root(0, 0, 1), LeanGeometry.rig(pose(0, 0, 0, 1), fraction));
            for (float z : new float[]{-2, 2}) {
                close(matrix(body).transformPosition(new Vector3f(-4, shoulderTop, z)),
                        matrix(right).transformPosition(new Vector3f(1, -2, z)), "right shoulder attached, slim=" + slim);
                close(matrix(body).transformPosition(new Vector3f(4, shoulderTop, z)),
                        matrix(left).transformPosition(new Vector3f(-1, -2, z)), "left shoulder attached, slim=" + slim);
            }
            close(matrix(head).transformPosition(new Vector3f()), matrix(body).transformPosition(new Vector3f()),
                    "head and chest share the neck attachment");
            frame.restore();
        }
        for (int stance = 0; stance < 3; stance++) for (double yaw : new double[]{0, .8, 1.57, 2.6}) {
            for (double fraction : new double[]{-1, -.6, -.05, 0, .05, .6, 1}) {
                float bodyYaw = .37f, scale = 1.3f;
                var pose = pose(stance, yaw, bodyYaw, scale);
                ModelPart body = part(0, stance == 1 ? 3.2f : 0, 0);
                body.xRot = stance == 1 ? .5f : 0;
                ModelPart head = part(0, stance == 1 ? 4.2f : 0, 0);
                ModelPart right = part(-5, stance == 1 ? 5.2f : 2, 0), left = part(5, right.y, 0);
                Matrix4f root = root(stance, bodyYaw, scale);
                Matrix4f nativeBody = new Matrix4f(root).mul(matrix(body));
                Matrix4f nativeHead = new Matrix4f(root).mul(matrix(head));
                Vector3f waist = nativeBody.transformPosition(new Vector3f(0, 12, 0));
                Vector3f legTop = root.transformPosition(new Vector3f(0, stance == 1 ? 12.2f : 12, stance == 1 ? 4 : 0));
                Vector3f towardLegs = root.transformDirection(new Vector3f(0, 1, 0)).normalize();
                var rig = LeanGeometry.rig(pose, fraction);
                close(waist, rig.pivot().toVector3f(), "shared waist matches native model in every stance");
                Vector3f nativeEye = new Vector3f(0, (float) pose.eyeHeight(), 0);
                Vector3f eyeInHead = new Matrix4f(nativeHead).invert().transformPosition(new Vector3f(nativeEye));
                var frame = new LeanPlayerRender.Frame(new PoseStack());
                frame.transformUpperBody(head, body, right, left, root, rig);
                Matrix4f leanedBody = new Matrix4f(root).mul(matrix(body));
                for (int x : new int[]{-4, 4}) for (int z : new int[]{-2, 2}) {
                    Vector3f corner = leanedBody.transformPosition(new Vector3f(x, 12, z));
                    check(corner.sub(legTop).dot(towardLegs) >= -.000002f, "no raised waist corner opens an air wedge at the actual leg top");
                }
                Vector3f modelEye = new Matrix4f(root).mul(matrix(head)).transformPosition(eyeInHead);
                close(modelEye, nativeEye.add(LeanGeometry.offset(pose, fraction).toVector3f()),
                        "rendered head and camera/shot eye share the same transform");
                frame.restore();
            }
        }
        checkAnimatedWaist();
        Random random = new Random(170847);
        for (int trial = 0; trial < 160; trial++) {
            ModelPart body = part(random.nextFloat(), random.nextFloat() * 5, random.nextFloat());
            ModelPart head = part(0, 0, 0), right = part(-5, 2, 0), left = part(5, 2.5f, 0);
            ModelPart[] parts = {head, body, right, left};
            Matrix4f[] before = new Matrix4f[4];
            float[][] original = new float[4][];
            for (int i = 0; i < parts.length; i++) {
                ModelPart part = parts[i];
                part.xRot = (random.nextFloat() - .5f) * 3;
                part.yRot = (random.nextFloat() - .5f) * 2;
                part.zRot = (random.nextFloat() - .5f) * .4f;
                part.xScale = .8f + random.nextFloat() * .4f;
                part.yScale = .8f + random.nextFloat() * .4f;
                part.zScale = .8f + random.nextFloat() * .4f;
                before[i] = matrix(part);
                original[i] = values(part);
            }
            int stance = trial % 3;
            float bodyYaw = random.nextFloat() * 6, scale = .5f + random.nextFloat();
            var rig = LeanGeometry.rig(pose(stance, random.nextDouble() * 6, bodyYaw, scale), random.nextDouble() * 2 - 1);
            Matrix4f root = root(stance, bodyYaw, scale);
            Matrix4f worldTransform = new Matrix4f().translation(rig.pivot().toVector3f()).translate(rig.seat().toVector3f())
                    .rotate(new Quaternionf().rotationAxis((float) rig.angle(), rig.axis().toVector3f()))
                    .translate(rig.pivot().scale(-1).toVector3f());
            var frame = new LeanPlayerRender.Frame(new PoseStack());
            frame.transformUpperBody(head, body, right, left, root, rig);
            for (int i = 0; i < parts.length; i++) {
                Matrix4f expected = new Matrix4f(worldTransform).mul(root).mul(before[i]);
                Matrix4f actual = new Matrix4f(root).mul(matrix(parts[i]));
                for (int vertex = 0; vertex < 8; vertex++) {
                    Vector3f point = new Vector3f((vertex & 1) == 0 ? -3 : 3, (vertex & 2) == 0 ? -2 : 12, (vertex & 4) == 0 ? -2 : 2);
                    close(actual.transformPosition(new Vector3f(point)), expected.transformPosition(new Vector3f(point)),
                            "animated upper parts inherit one waist transform");
                }
            }
            frame.restore();
            for (int i = 0; i < parts.length; i++) {
                float[] restored = values(parts[i]);
                for (int f = 0; f < restored.length; f++)
                    check(Float.floatToIntBits(restored[f]) == Float.floatToIntBits(original[i][f]),
                            "restore every native pose/scale field exactly");
            }
        }
        System.out.println("Lean joint checks: " + checks + " actual ModelPart waist/shoulder/eye/restoration checks passed");
    }
    private static void checkAnimatedWaist() {
        for (int stance = 0; stance < 3; stance++) for (float yaw : new float[]{0, .7f, 1.5f, 2.5f}) {
            for (float bodyYaw : new float[]{-.3f, 0, .3f}) for (float twist : new float[]{-.2f, 0, .2f}) {
                for (float fraction : new float[]{-1, -.5f, -.05f, .05f, .5f, 1}) {
                    var nativeRoot = root(stance, bodyYaw, 1);
                    var pose = pose(stance, yaw, bodyYaw, 1);
                    ModelPart body = part(0, stance == 1 ? 3.2f : 0, 0);
                    body.xRot = stance == 1 ? .5f : 0;
                    body.yRot = twist;
                    // The actual native leg-top plane also includes crouch's shifted leg roots.
                    Vector3f legTop = nativeRoot.transformPosition(new Vector3f(0, stance == 1 ? 12.2f : 12, stance == 1 ? 4 : 0));
                    Vector3f intoLegs = nativeRoot.transformDirection(new Vector3f(0, 1, 0)).normalize();
                    var frame = new LeanPlayerRender.Frame(new PoseStack());
                    frame.transformUpperBody(part(0, 0, 0), body, part(-5, 2, 0), part(5, 2, 0), nativeRoot,
                            LeanGeometry.rig(pose, fraction));
                    Matrix4f leanedBody = new Matrix4f(nativeRoot).mul(matrix(body));
                    for (int x : new int[]{-4, 4}) for (int z : new int[]{-2, 2}) {
                        Vector3f corner = leanedBody.transformPosition(new Vector3f(x, 12, z));
                        check(corner.sub(legTop).dot(intoLegs) >= -.000002f,
                                "animated waist stays on leg side: stance=" + stance + " twist=" + twist + " yaw=" + yaw);
                    }
                    frame.restore();
                }
            }
        }
    }
    private static LeanGeometry.Pose pose(int stance, double yaw, double bodyYaw, double scale) {
        return new LeanGeometry.Pose(yaw, bodyYaw, (stance == 0 ? 1.62 : stance == 1 ? 1.27 : .4) * scale,
                scale, stance == 2, stance == 2 ? -Math.PI / 4 : 0);
    }
    /** Native renderer root, including crouch dispatcher offset, in model pixels. */
    private static Matrix4f root(int stance, float bodyYaw, float scale) {
        Matrix4f root = new Matrix4f().scale(scale).translate(0, stance == 1 ? -.125f : 0, 0)
                .rotateY((float) Math.PI - bodyYaw);
        if (stance == 2) root.rotateX(-(float) Math.PI / 2).translate(0, -1, .3f);
        return root.scale(-.9375f, -.9375f, .9375f).translate(0, -1.501f, 0).scale(1f / 16);
    }
    private static ModelPart part(float x, float y, float z) {
        var part = new ModelPart(List.of(), Map.of()); part.x = x; part.y = y; part.z = z; return part;
    }
    private static Matrix4f matrix(ModelPart part) {
        return new Matrix4f().translation(part.x, part.y, part.z)
                .rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot)).scale(part.xScale, part.yScale, part.zScale);
    }
    private static float[] values(ModelPart p) { return new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale}; }
    private static void close(Vector3f actual, Vector3f expected, String name) {
        check(actual.distance(expected) <= .00002f, name + ": " + actual + " != " + expected);
    }
    private static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
}
