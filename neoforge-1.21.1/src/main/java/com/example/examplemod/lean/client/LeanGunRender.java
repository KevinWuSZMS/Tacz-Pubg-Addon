package com.example.examplemod.lean.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.examplemod.lean.LeanService;
import com.example.examplemod.lean.LeanGeometry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Applies lean after native ADS and keeps deferred scope masks in the same frame. */
public final class LeanGunRender {
    private static final float MAX_ROLL = (float) LeanGeometry.ROLL_RADIANS;
    // Render-thread-only weak keys also cover TaCZ's deferred accelerated scopes.
    private static final Map<PoseStack, Matrix4f> MASK_UNDO = new WeakHashMap<>();

    private LeanGunRender() {}

    public static void apply(LocalPlayer player, PoseStack pose, float partialTick) {
        clear(pose);
        float ads = IClientPlayerGunOperator.fromLocalPlayer(player).getClientAimingProgress(partialTick);
        float angle = -MAX_ROLL * (float) LeanService.fraction(player, partialTick) * Math.clamp(ads, 0, 1);
        if (Math.abs(angle) < 0.00001f) return;
        var window = Minecraft.getInstance().getWindow();
        float aspect = (float) window.getWidth() / Math.max(1, window.getHeight());
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix());
        // Zero is the actual projected aiming ray, independent of the active scope_view.
        // A pack's pre-existing off-center artwork is not inferred from its bone pivot.
        Matrix4f screen = LeanRenderMath.screenRoll(angle, aspect, 0, 0);
        Matrix4f transform = LeanRenderMath.modelTransform(projection, view, screen);
        pose.last().pose().mulLocal(transform);
        pose.last().normal().mulLocal(new Matrix3f(transform).invert().transpose());
        MASK_UNDO.put(pose, LeanRenderMath.inverseViewTransform(projection, screen));
    }

    public static void copy(PoseStack source, PoseStack target) {
        Matrix4f undo = MASK_UNDO.get(source);
        if (undo != null) MASK_UNDO.put(target, new Matrix4f(undo));
    }

    public static Vector3f nativeMaskCenter(PoseStack pose, Vector3f center) {
        Matrix4f undo = MASK_UNDO.get(pose);
        // TaCZ derives its aperture vertices from this center and transforms those
        // vertices again. Undo lean in that intermediate value to apply it once.
        return undo == null ? center : undo.transformProject(new Vector3f(center));
    }

    public static void clear(PoseStack pose) {
        MASK_UNDO.remove(pose);
    }
}
