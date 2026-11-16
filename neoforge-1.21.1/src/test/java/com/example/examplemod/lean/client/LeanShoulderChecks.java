package com.example.examplemod.lean.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Exercises production ModelPart mutation without starting a renderer or a game. */
public final class LeanShoulderChecks {
    private static int checks;
    public static void run() {
        for (boolean slim : new boolean[]{false, true}) for (float fraction : new float[]{-1, -.5f, 0, .5f, 1}) {
            ModelPart body = part(0, 0, 0), right = part(-5, slim ? 2.5f : 2, 0), left = part(5, slim ? 2.5f : 2, 0);
            float shoulderTop = right.y - 2;
            var frame = new LeanPlayerRender.Frame(new PoseStack());
            frame.transformTorso(body, right, left, new Vector3f(-6 * fraction, 1, .3f),
                    new Quaternionf().rotationZ(.384f * fraction));
            for (float z : new float[]{-2, 2}) {
                close(matrix(body).transformPosition(new Vector3f(-4, shoulderTop, z)),
                        matrix(right).transformPosition(new Vector3f(1, -2, z)), "right shoulder stays attached, slim=" + slim);
                close(matrix(body).transformPosition(new Vector3f(4, shoulderTop, z)),
                        matrix(left).transformPosition(new Vector3f(-1, -2, z)), "left shoulder stays attached, slim=" + slim);
            }
            frame.restore();
        }
        Random random = new Random(170847);
        for (int trial = 0; trial < 160; trial++) {
            ModelPart body = part(random.nextFloat(), random.nextFloat() * 5, random.nextFloat());
            ModelPart right = part(-5, 2, 0), left = part(5, 2.5f, 0);
            ModelPart[] parts = {body, right, left};
            Matrix4f[] before = new Matrix4f[3];
            float[][] original = new float[3][];
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
            // The local roll axis changes with the standing/crouching/prone renderer root.
            Matrix4f root = new Matrix4f().rotateY(random.nextFloat() * 6).rotateX(trial % 3 == 0 ? -(float) Math.PI / 2 : 0)
                    .scale(-.9375f, -.9375f, .9375f);
            Matrix4f inverse = new Matrix4f(root).invert();
            Vector3f axis = inverse.transformDirection(new Vector3f(0, 0, 1)).normalize();
            Quaternionf roll = new Quaternionf().rotationAxis((random.nextFloat() - .5f) * .768f, axis);
            Vector3f delta = inverse.transformDirection(new Vector3f(-.35f, -.1f, 0)).mul(16);
            Vector3f pivot = before[0].transformPosition(new Vector3f(0, 6, 0));
            Matrix4f parent = new Matrix4f().translation(pivot).translate(new Vector3f(delta).mul(.5f))
                    .rotate(roll).translate(new Vector3f(pivot).negate());
            var frame = new LeanPlayerRender.Frame(new PoseStack());
            frame.transformTorso(body, right, left, delta, roll);
            for (int i = 0; i < parts.length; i++) {
                Matrix4f expected = new Matrix4f(parent).mul(before[i]);
                Matrix4f actual = matrix(parts[i]);
                for (int vertex = 0; vertex < 8; vertex++) {
                    Vector3f point = new Vector3f((vertex & 1) == 0 ? -3 : 3, (vertex & 2) == 0 ? -2 : 10, (vertex & 4) == 0 ? -2 : 2);
                    close(actual.transformPosition(new Vector3f(point)), expected.transformPosition(new Vector3f(point)),
                            "animated arm and torso inherit the same parent matrix");
                }
            }
            close(matrix(body).transformPosition(new Vector3f(0, 6, 0)), new Vector3f(pivot).add(new Vector3f(delta).mul(.5f)),
                    "torso keeps its previous center displacement");
            frame.restore();
            for (int i = 0; i < parts.length; i++) {
                float[] restored = values(parts[i]);
                for (int f = 0; f < restored.length; f++) {
                    checks++;
                    if (Float.floatToIntBits(restored[f]) != Float.floatToIntBits(original[i][f]))
                        throw new AssertionError("render restoration must preserve every native pose/scale field");
                }
            }
        }
        System.out.println("Lean shoulder checks: " + checks + " actual ModelPart attachment/animation/restoration checks passed");
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
        checks++; if (actual.distance(expected) > .00002f) throw new AssertionError(name + ": " + actual + " != " + expected);
    }
}
