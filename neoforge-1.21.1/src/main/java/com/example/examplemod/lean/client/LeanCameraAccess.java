package com.example.examplemod.lean.client;

/** Exposes the current native eye-height interpolation before Camera.setup runs (picking runs first). */
public interface LeanCameraAccess {
    float taczPubg$interpolatedEyeHeight(float partialTick);
}
