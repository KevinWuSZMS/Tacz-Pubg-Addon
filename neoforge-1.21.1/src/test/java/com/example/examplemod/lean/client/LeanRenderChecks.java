package com.example.examplemod.lean.client;
import com.example.examplemod.lean.LeanGeometry;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import java.util.Random;

public final class LeanRenderChecks {
    static int checks;
    static void close(Vector3f actual, Vector3f expected, String label) {
        checks++;
        if (!Float.isFinite(actual.x) || !Float.isFinite(actual.y) || !Float.isFinite(actual.z)
                || !Float.isFinite(expected.x) || !Float.isFinite(expected.y) || !Float.isFinite(expected.z)
                || actual.distance(expected) > 0.001f) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) {
        Random random = new Random(824125);
        for (float aspect : new float[] {1, 16f/9, 21f/9, 9f/16}) {
            for (float fov : new float[] {5, 15, 30, 60, 110}) {
                Matrix4f p = new Matrix4f().perspective((float) java.lang.Math.toRadians(fov), aspect, .05f, 1024);
                for (float radians : new float[] {-.384f, -.192f, 0, .192f, .384f}) {
                    Matrix4f v = new Matrix4f().rotateXYZ(.02f, -.03f, .01f).translate(.01f, .02f, -.1f);
                    Matrix4f screen = LeanRenderMath.screenRoll(radians, aspect, 0, 0);
                    Matrix4f lean = LeanRenderMath.modelTransform(p, v, screen);
                    Matrix4f pv = new Matrix4f(p).mul(v);
                    Matrix4f leanedPv = new Matrix4f(pv).mul(lean);
                    for (int i=0; i<40; i++) {
                        Vector3f point = new Vector3f(random.nextFloat()-.5f, random.nextFloat()-.5f, -.2f-random.nextFloat()*8);
                        close(leanedPv.transformProject(new Vector3f(point)),
                                screen.transformProject(pv.transformProject(new Vector3f(point))), "post-projection equivariance");
                    }
                    for (float z : new float[] {-.95f, 0, .7f, .99f}) {
                        Vector3f center = new Matrix4f(pv).invert().transformProject(new Vector3f(0, 0, z));
                        close(leanedPv.transformProject(center), new Vector3f(0, 0, z), "center ray invariance");
                    }
                    Matrix4f viewLean = new Matrix4f(p).invert().mul(screen).mul(p);
                    Matrix4f undo = LeanRenderMath.inverseViewTransform(p, screen);
                    Vector3f ocular = new Vector3f(.04f, -.03f, -.45f);
                    close(undo.transformProject(viewLean.transformProject(new Vector3f(ocular))), ocular, "scope aperture inverse");
                    // The circle generated from the corrected center must have the
                    // same screen projection as a once-rolled native mask vertex.
                    Vector3f corrected = undo.transformProject(viewLean.transformProject(new Vector3f(ocular)));
                    Vector3f nativeVertex = new Vector3f(ocular.x*1440+80, ocular.y*1440, -90);
                    Vector3f correctedVertex = new Vector3f(corrected.x*1440+80, corrected.y*1440, -90);
                    close(leanedPv.transformProject(correctedVertex),
                            screen.transformProject(pv.transformProject(nativeVertex)), "scope mask single rotation");
                }
            }
        }
        for (float pitch : new float[] {0, .5f, -(float)java.lang.Math.PI/2}) {
            for (float yaw : new float[] {0, .6f, 2.1f}) {
                Matrix4f body = new Matrix4f().rotateY(yaw).rotateX(pitch).scale(-.9375f, -.9375f, .9375f);
                Matrix4f inverse = new Matrix4f(body).invert();
                Vector3f worldDelta = new Vector3f(-.35f, -.1f, 0);
                Vector3f localDelta = inverse.transformDirection(new Vector3f(worldDelta)).mul(16);
                Vector3f axis = inverse.transformDirection(new Vector3f(0,0,1)).normalize();
                Quaternionf lean = new Quaternionf().rotationAxis(.384f, axis);
                Quaternionf nativeQ = new Quaternionf().rotationZYX(.05f, .2f, .15f);
                Quaternionf combined = new Quaternionf(lean).mul(nativeQ);
                Vector3f center = new Vector3f(0,-4,0);
                Vector3f origin = new Vector3f(0,4.2f,1);
                Vector3f before = new Vector3f(origin).add(nativeQ.transform(new Vector3f(center)));
                Vector3f afterOrigin = new Vector3f(before).add(localDelta).sub(combined.transform(new Vector3f(center)));
                Vector3f after = afterOrigin.add(combined.transform(new Vector3f(center)));
                close(body.transformDirection(after.sub(before).div(16)), worldDelta, "third-person center displacement");
                Vector3f euler = LeanRenderMath.modelPartEuler(combined);
                Quaternionf rebuilt = new Quaternionf().rotationZYX(euler.z,euler.y,euler.x);
                close(rebuilt.transform(new Vector3f(.3f,.7f,-.4f)), combined.transform(new Vector3f(.3f,.7f,-.4f)), "ModelPart Euler roundtrip");
            }
        }
        checkLeanedWorldAim();
        LeanShoulderChecks.run();
        System.out.println("PASS " + checks + " projection, aperture, and upper-body invariants");
    }

    private static void checkLeanedWorldAim() {
        for (int stance = 0; stance < 3; stance++) {
            double scale = .75 + stance * .3;
            double eyeHeight = (stance == 0 ? 1.62 : stance == 1 ? 1.27 : .4) * scale;
            for (int direction = 0; direction < 3; direction++) {
                double yaw = direction * 1.13, pitch = (direction - 1) * .65;
                var pose = new LeanGeometry.Pose(yaw, yaw - .43, eyeHeight, scale, stance == 2,
                        stance == 2 ? -java.lang.Math.PI / 4 : pitch);
                Vec3 aim = new Vec3(-java.lang.Math.sin(yaw) * java.lang.Math.cos(pitch),
                        -java.lang.Math.sin(pitch), java.lang.Math.cos(yaw) * java.lang.Math.cos(pitch));
                // Minecraft renders near the camera origin; subtract large world coordinates before using float matrices.
                Vec3 renderOrigin = new Vec3(1_000_000.25, 78 + eyeHeight, -999_999.875);
                for (double fraction : new double[]{-1, -.5, 0, .5, 1}) {
                    Vec3 worldEye = renderOrigin.add(LeanGeometry.offset(pose, fraction));
                    Vector3f eye = vector(worldEye.subtract(renderOrigin));
                    Matrix4f view = new Matrix4f().lookAt(eye, new Vector3f(eye).add(vector(aim)), new Vector3f(0, 1, 0));
                    close(view.transformDirection(vector(aim)), new Vector3f(0, 0, -1), "stance eye move preserves the original aim direction");
                    Vector3f worldUp = view.transformDirection(new Vector3f(0, 1, 0));
                    close(new Vector3f(worldUp.x, 0, 0), new Vector3f(), "leaned camera keeps world up free of roll");
                    for (float aspect : new float[]{1, 16f / 9, 21f / 9, 9f / 16}) {
                        for (float fov : new float[]{5, 15, 30, 60, 110}) {
                            Matrix4f projection = new Matrix4f().perspective((float) java.lang.Math.toRadians(fov), aspect, .05f, 1024);
                            Matrix4f pv = new Matrix4f(projection).mul(view);
                            Matrix4f screen = LeanRenderMath.screenRoll((float) (-LeanGeometry.ROLL_RADIANS * fraction), aspect, 0, 0);
                            Matrix4f leanedPv = new Matrix4f(pv).mul(LeanRenderMath.modelTransform(projection, view, screen));
                            for (double distance : new double[]{2, 30, 250}) {
                                Vector3f point = vector(worldEye.add(aim.scale(distance)).subtract(renderOrigin));
                                Vector3f projected = pv.transformProject(new Vector3f(point));
                                Vector3f center = new Vector3f(0, 0, projected.z);
                                close(projected, center, "actual stance eye projects its aim ray to the viewport center");
                                close(leanedPv.transformProject(point), center, "scope roll preserves the new eye's projected aim center at every FOV");
                            }
                        }
                    }
                }
            }
        }
    }

    private static Vector3f vector(Vec3 value) { return new Vector3f((float) value.x, (float) value.y, (float) value.z); }
}
