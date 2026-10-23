package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.client.GunExperimentClient;
import com.example.examplemod.gunbalance.client.PersistentRecoilClient;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.client.event.CameraSetupEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses the sampled r7 recoil rather than a second roll or the authored worst-case bounds. */
@Mixin(value = CameraSetupEvent.class, remap = false)
public abstract class CameraPersistentRecoilMixin {
    @Shadow private static PolynomialSplineFunction pitchSplineFunction;
    @Shadow private static PolynomialSplineFunction yawSplineFunction;
    @Shadow private static double xRotO;
    @Shadow private static double yRotO;

    @Inject(method = "initialCameraRecoil", at = @At("HEAD"), remap = false)
    private static void taczPubg$discardPreviousCurve(GunFireEvent event, CallbackInfo callback) {
        if (GunExperimentClient.balanceEnabled() && event.getLogicalSide().isClient()
                && event.getShooter() == Minecraft.getInstance().player) {
            // An early return (missing data/cache) must not recycle the preceding shot's samples.
            pitchSplineFunction = yawSplineFunction = null;
            xRotO = yRotO = 0;
        }
    }

    @Inject(method = "initialCameraRecoil", at = @At("TAIL"), remap = false)
    private static void taczPubg$captureSampledRecoil(GunFireEvent event, CallbackInfo callback) {
        if (!GunExperimentClient.balanceEnabled() || !event.getLogicalSide().isClient()
                || event.getShooter() != Minecraft.getInstance().player) return;
        PersistentRecoilClient.capture(event, pitchSplineFunction, yawSplineFunction);
        // No recoverable curve survives a scheme switch or a server echo of this shot.
        pitchSplineFunction = yawSplineFunction = null;
        xRotO = yRotO = 0;
    }

    @Inject(method = "applyCameraRecoil", at = @At("HEAD"), cancellable = true, remap = false)
    private static void taczPubg$applyRetainedRecoil(ViewportEvent.ComputeCameraAngles event, CallbackInfo callback) {
        if (PersistentRecoilClient.applyCamera(event)) {
            pitchSplineFunction = yawSplineFunction = null;
            xRotO = yRotO = 0;
            callback.cancel();
        }
    }
}
