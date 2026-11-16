package com.example.examplemod.lean.client;
import org.joml.*;
import java.util.Random;

public final class LeanRenderChecks {
    static int checks;
    static void close(Vector3f actual, Vector3f expected, String label) {
        checks++;
        if (actual.distance(expected) > 0.001f) throw new AssertionError(label + ": " + actual + " != " + expected);
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
        System.out.println("PASS " + checks + " projection, aperture, and upper-body invariants");
    }
}
