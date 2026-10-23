package com.example.examplemod.zones.client.xaero;

import com.example.examplemod.ExampleMod;
import net.neoforged.fml.ModList;

/** Optional client bridge. No Xaero type appears in this class's linkage or signatures. */
public final class XaeroOverlayIntegration {
    private static final Bridge MINIMAP = new Bridge("xaerominimap", "26.6.0", "XaeroMinimapOverlay");
    private static final Bridge WORLD_MAP = new Bridge("xaeroworldmap", "1.47.0", "XaeroWorldMapOverlay");
    private static int ticks;
    private XaeroOverlayIntegration() {}

    /** Call from the client Post tick; registration waits for Xaero's own delayed initialization. */
    public static void tick() {
        if (ticks++ % 20 != 0) return;
        MINIMAP.install(); WORLD_MAP.install();
    }

    private static final class Bridge {
        private final String mod, version, adapter;
        private boolean disabled;
        private java.lang.reflect.Method install;
        Bridge(String mod, String version, String adapter) { this.mod = mod; this.version = version; this.adapter = adapter; }
        void install() {
            if (disabled) return;
            var loaded = ModList.get().getModContainerById(mod);
            if (loaded.isEmpty()) { disabled = true; return; }
            if (!loaded.get().getModInfo().getVersion().toString().equals(version)) {
                ExampleMod.LOGGER.warn("PUBG map overlay disabled for unsupported {} {} (adapter targets {})",
                        mod, loaded.get().getModInfo().getVersion(), version);
                disabled = true; return;
            }
            try {
                if (install == null) install = Class.forName(XaeroOverlayIntegration.class.getPackageName() + "." + adapter)
                        .getDeclaredMethod("install");
                install.invoke(null);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                Throwable cause = error instanceof java.lang.reflect.InvocationTargetException invocation
                        && invocation.getCause() != null ? invocation.getCause() : error;
                ExampleMod.LOGGER.warn("PUBG {} overlay could not register; disabling this optional adapter", mod, cause);
                disabled = true;
            }
        }
    }
}
