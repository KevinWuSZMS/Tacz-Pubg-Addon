package com.example.examplemod.modelscale;

/** Common-safe synchronized client cache; never writes process-global server state. */
public final class ModelScaleState {
    private static ModelScaleSettings settings = ModelScaleSettings.initial();
    private static long revision = -1;
    private ModelScaleState() {}
    public static ModelScaleSettings settings() { return settings; }
    public static long revision() { return revision; }
    public static void accept(long next, ModelScaleSettings value) {
        if (next < 0 || next < revision) return;
        revision = next; settings = value;
    }
    public static void clear() { revision = -1; settings = ModelScaleSettings.initial(); }
}
