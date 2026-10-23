package com.example.examplemod.zones.client.xaero.editor;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.client.MapOverlayClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional entry point: no Xaero class is linked until its supported version is available. */
public final class NativeMapEditor {
    private static Screen previewOwner;
    private static Supplier<MapDefinition> preview;
    private static String lastError = "";
    private NativeMapEditor() {}

    public static boolean open(Screen parent, MapDefinition draft, Consumer<MapDefinition> onAccept) {
        return open(parent, draft, onAccept, null);
    }

    public static boolean open(Screen parent, MapDefinition draft, Consumer<MapDefinition> onAccept, Runnable onDeleteMap) {
        var container = ModList.get().getModContainerById("xaeroworldmap");
        if (container.isEmpty()) { lastError = Texts.tr("screen.tacz_pubg.native_map_editor.install_xaero_world_map_open_native_map_editor"); return false; }
        if (!container.get().getModInfo().getVersion().toString().equals("1.47.0")) {
            lastError = Texts.tr("screen.tacz_pubg.native_map_editor.map_editor_supports_xaero_world_map_use_version"); return false;
        }
        try {
            lastError = "";
            return (boolean) Class.forName(NativeMapEditor.class.getPackageName() + ".NativeXaeroEditorScreen")
                    .getMethod("open", Screen.class, MapDefinition.class, Consumer.class, Runnable.class)
                    .invoke(null, parent, draft, onAccept, onDeleteMap);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : error;
            lastError = Texts.tr("screen.tacz_pubg.native_map_editor.xaero_native_map_editor_initialization_notice", cause.getMessage());
            ExampleMod.LOGGER.warn("Could not open optional Xaero native PUBG map editor", cause);
            return false;
        }
    }

    public static String lastError() { return lastError; }
    static void unavailable(String reason) { lastError = reason; }
    static void attach(Screen owner, Supplier<MapDefinition> supplier) { previewOwner = owner; preview = supplier; }
    static void detach(Screen owner) { if (previewOwner == owner) { previewOwner = null; preview = null; } }

    public static boolean isEditing() {
        return preview != null && Minecraft.getInstance().screen == previewOwner;
    }

    /** The native world-map element paints this exact draft; ordinary map screens use server state. */
    public static java.util.List<MapDefinition> allMaps() {
        if (!isEditing()) return MapOverlayClient.allMaps();
        var draft = preview.get();
        if (draft == null) return MapOverlayClient.allMaps();
        var result = new java.util.ArrayList<>(MapOverlayClient.allMaps());
        result.removeIf(map -> map.id() == draft.id());
        result.add(draft);
        return java.util.List.copyOf(result);
    }
    public static boolean editing(MapDefinition map) {
        return isEditing() && preview.get() != null && preview.get().id() == map.id();
    }
    public static MapDefinition currentMap() {
        if (isEditing()) return preview.get();
        return MapOverlayClient.current();
    }
}
