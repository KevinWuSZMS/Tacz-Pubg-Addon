package com.example.examplemod.lean.client;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix3f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/** Projection-space operations independent of Minecraft and gun-pack geometry. */
public final class LeanRenderMath {
    private LeanRenderMath() {}

    /**
     * Rotates screen pixels about a normalized-device-coordinate pivot. The aspect
     * correction keeps the angle independent of the viewport and both FOVs.
     */
    public static Matrix4f screenRoll(float radians, float aspect, float pivotX, float pivotY) {
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        return new Matrix4f()
                .m00(c).m10(-s / aspect)
                .m01(s * aspect).m11(c)
                .m30(pivotX - c * pivotX + s * pivotY / aspect)
                .m31(pivotY - s * aspect * pivotX - c * pivotY);
    }

    /** P * V * result * H == screenRoll * P * V * H, including native ADS motion. */
    public static Matrix4f modelTransform(Matrix4fc projection, Matrix4fc modelView, Matrix4fc screenRoll) {
        Matrix4f viewProjection = new Matrix4f(projection).mul(modelView);
        return new Matrix4f(viewProjection).invert().mul(screenRoll).mul(viewProjection);
    }

    /** Converts a leaned view-space point back to the native mask's input space. */
    public static Matrix4f inverseViewTransform(Matrix4fc projection, Matrix4fc screenRoll) {
        return new Matrix4f(projection).invert().mul(new Matrix4f(screenRoll).invert()).mul(projection);
    }

    /** Matches ModelPart.rotationZYX; avoids the X denominator bug in JOML 1.10.5. */
    public static Vector3f modelPartEuler(Quaternionfc rotation) {
        Matrix3f matrix = new Matrix3f().rotation(rotation);
        float cosY = (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01());
        float y = (float) Math.atan2(-matrix.m02(), cosY);
        if (cosY < 0.000001f) {
            return new Vector3f(0, y, (float) Math.atan2(-matrix.m10(), matrix.m11()));
        }
        return new Vector3f((float) Math.atan2(matrix.m12(), matrix.m22()), y,
                (float) Math.atan2(matrix.m01(), matrix.m00()));
    }
}
