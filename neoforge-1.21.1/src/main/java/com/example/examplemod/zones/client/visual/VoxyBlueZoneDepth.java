package com.example.examplemod.zones.client.visual;

import com.example.examplemod.ExampleMod;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Borrows Voxy's completed depth for one frame; never changes its textures, buffers or renderer. */
public final class VoxyBlueZoneDepth {
    private static final boolean ENABLED = !Boolean.getBoolean("tacz_pubg.disableVoxyBlueZoneDepth");
    private static final String NORMAL = "me.cortex.voxy.client.core.NormalRenderPipeline";
    private static final String IRIS = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline";
    private static boolean requested;
    private static boolean incompatible;
    private static boolean reported;
    private static Access access;
    private static Frame frame;

    private VoxyBlueZoneDepth() {}

    static void beginFrame(boolean hasMap) {
        frame = null;
        requested = ENABLED && !incompatible && hasMap && BlueZoneShaders.lodShader() != null;
    }

    /** Called only by the optional, non-cancelling runPipeline TAIL observer. No OpenGL calls. */
    public static void capture(Object pipeline, Object viewport) {
        if (!requested) return;
        // Pipelines share their depth textures across viewports. A later shadow/VR
        // pass invalidates the earlier pointer even when that later pass is rejected.
        frame = null;
        if (viewport == null) return;
        String name = pipeline.getClass().getName();
        if (!NORMAL.equals(name) && !IRIS.equals(name)) return;
        try {
            if (access == null || access.pipelineType != pipeline.getClass()) access = new Access(pipeline, viewport);
            if (access.shadowActive()) return;
            Object framebuffer = access.depthFramebuffer.get(pipeline);
            Object texture = access.getDepthTex.invoke(framebuffer);
            if (texture == null) return;
            int textureId = access.textureId.getInt(texture);
            int width = access.width.getInt(viewport), height = access.height.getInt(viewport);
            Matrix4f transform = new Matrix4f((Matrix4fc) access.mvp.get(viewport));
            Vec3 camera = new Vec3(access.cameraX.getDouble(viewport), access.cameraY.getDouble(viewport),
                    access.cameraZ.getDouble(viewport));
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || textureId <= 0 || width <= 0 || height <= 0 || !transform.isFinite()
                    || !Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)) return;
            // Keep values, not the mutable Voxy viewport. The texture remains owned entirely by Voxy.
            frame = new Frame(level, textureId, width, height, transform, camera);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            incompatible = true;
            requested = false;
            frame = null;
            ExampleMod.LOGGER.warn("Voxy blue-zone depth unavailable; retaining the original blue-zone renderer", exception);
        }
    }

    static Frame take(ClientLevel level, Vec3 camera) {
        Frame result = frame;
        frame = null;
        requested = false;
        if (result == null || result.level != level || result.camera.distanceToSqr(camera) > 1.0e-6) return null;
        if (!GL11.glIsTexture(result.textureId)) return null;
        if (!reported) {
            reported = true;
            ExampleMod.LOGGER.info("Blue-zone curtain is using this frame's Voxy LOD depth (read-only)");
        }
        return result;
    }

    record Frame(ClientLevel level, int textureId, int width, int height, Matrix4f transform, Vec3 camera) {}

    /** Public Voxy fields only; no private access, hard dependency, or eager Voxy class loading. */
    private static final class Access {
        final Class<?> pipelineType;
        final Field depthFramebuffer, textureId, width, height, mvp, cameraX, cameraY, cameraZ;
        final Method getDepthTex, isShadow;

        Access(Object pipeline, Object viewport) throws ReflectiveOperationException {
            pipelineType = pipeline.getClass();
            depthFramebuffer = pipelineType.getField(IRIS.equals(pipelineType.getName()) ? "fbTranslucent" : "fb");
            getDepthTex = depthFramebuffer.getType().getMethod("getDepthTex");
            textureId = getDepthTex.getReturnType().getField("id");
            Class<?> viewType = viewport.getClass();
            width = viewType.getField("width");
            height = viewType.getField("height");
            mvp = viewType.getField("MVP");
            cameraX = viewType.getField("cameraX");
            cameraY = viewType.getField("cameraY");
            cameraZ = viewType.getField("cameraZ");
            isShadow = Class.forName("me.cortex.voxy.client.core.util.IrisUtil", false,
                    pipelineType.getClassLoader()).getMethod("irisShadowActive");
        }

        boolean shadowActive() throws ReflectiveOperationException {
            return (boolean) isShadow.invoke(null);
        }
    }

    /** ShaderInstance borrows texture unit zero. Restore its actual binding and sampler as well. */
    static final class TextureBinding implements AutoCloseable {
        private final int activeTexture, texture, sampler;

        TextureBinding() {
            activeTexture = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            sampler = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0);
            // A foreign shadow-comparison sampler is inappropriate for raw depth texelFetch.
            GL33.glBindSampler(0, 0);
            GlStateManager._activeTexture(activeTexture);
        }

        @Override
        public void close() {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(texture);
            GL33.glBindSampler(0, sampler);
            GlStateManager._activeTexture(activeTexture);
        }
    }
}
