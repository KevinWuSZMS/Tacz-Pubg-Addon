package com.example.examplemod.throwables.client.mixin;

import com.example.examplemod.throwables.client.ThrowableClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve every direction change before vanilla combines mouse movement for the frame. */
@Mixin(value = MouseHandler.class, priority = 1500)
abstract class ThrowableWheelMouseMixin {
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private boolean ignoreFirstMove;

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void taczPubg$wheelMovement(long window, double x, double y, CallbackInfo ci) {
        if (!ignoreFirstMove && ThrowableClient.captureWheelMouseMovement(window, x - xpos, y - ypos)) {
            // Keep the native baseline current, but do not leak wheel movement into the camera
            // when the trigger releases and grabMouse() runs later in this same frame.
            xpos = x;
            ypos = y;
            ci.cancel();
        }
    }

    @Inject(method = "onPress", at = @At("HEAD"))
    private void taczPubg$wheelButton(long window, int button, int action, int modifiers, CallbackInfo ci) {
        // PhysicalMouseMixin (priority 2000) has already recorded this release.
        ThrowableClient.captureWheelMouseButton(window);
    }
}
