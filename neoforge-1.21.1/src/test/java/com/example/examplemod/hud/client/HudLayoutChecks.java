package com.example.examplemod.hud.client;

import java.nio.file.Files;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Real JSON/file roundtrips and changing viewport sizes, without Minecraft client initialization. */
public final class HudLayoutChecks {
    public static void main(String[] args) throws Exception {
        var fallback = new HudLayoutModel.Rect(15, 29, 182, 19);
        check(HudLayoutModel.resolve(null, 320, 240, fallback).equals(fallback), "Untouched defaults moved");
        for (int[] viewport : new int[][]{{320, 240}, {640, 360}, {854, 480}, {1280, 720}, {1920, 1080}}) {
            for (var normalized : new HudLayoutModel.Position[]{
                    new HudLayoutModel.Position(0, 0), new HudLayoutModel.Position(1, 1),
                    new HudLayoutModel.Position(.5, .5), new HudLayoutModel.Position(.237, .823)}) {
                var placed = HudLayoutModel.resolve(normalized, viewport[0], viewport[1], fallback);
                check(placed.x() >= 0 && placed.y() >= 0 && placed.x() + placed.width() <= viewport[0]
                        && placed.y() + placed.height() <= viewport[1], "Saved placement escaped a resized window");
                var roundtrip = HudLayoutModel.normalize(placed.x(), placed.y(), viewport[0], viewport[1],
                        placed.width(), placed.height());
                check(Math.abs(roundtrip.x() - normalized.x()) <= .5 / (viewport[0] - placed.width()) + 1e-9
                        && Math.abs(roundtrip.y() - normalized.y()) <= .5 / (viewport[1] - placed.height()) + 1e-9,
                        "Pixel rounding changed normalized position by more than half a pixel");
            }
        }
        var oversized = HudLayoutModel.resolve(new HudLayoutModel.Position(1, 1), 80, 40,
                new HudLayoutModel.Rect(-400, -400, 200, 200));
        check(oversized.x() == 0 && oversized.y() == 0, "Oversized component lost its visible origin");
        check(HudLayoutModel.normalize(-20, 1000, 320, 240, 100, 40).equals(new HudLayoutModel.Position(0, 1)),
                "Dragging outside the canvas did not clamp");
        menuGeometry();
        var map = Map.of("vitals", new HudLayoutModel.Position(.5, 1),
                "xaero_minimap", new HudLayoutModel.Position(.83, .09));
        var directory = Files.createTempDirectory("pubg-hud-checks-");
        var file = directory.resolve("tacz_pubg-hud.json");
        try {
            check(HudLayoutStore.read(file).isEmpty(), "Missing file did not preserve defaults");
            HudLayoutStore.write(file, map);
            check(HudLayoutStore.read(file).equals(map), "Real UTF-8 JSON roundtrip changed personal positions");
            HudLayoutStore.write(file, Map.of());
            check(HudLayoutStore.read(file).isEmpty(), "Reset/save kept a removed position");
            draftLifecycle(file, map);
            Files.writeString(file, "{\"version\":99,\"positions\":{}}");
            try { HudLayoutStore.read(file); throw new AssertionError("Unsupported format accepted"); }
            catch (java.io.IOException expected) {}
            Files.writeString(file, "{\"version\":1,\"positions\":{\"vitals\":{\"x\":\"NaN\",\"y\":0}}}");
            try { HudLayoutStore.read(file); throw new AssertionError("Non-finite coordinate accepted"); }
            catch (java.io.IOException expected) {}
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
            Files.deleteIfExists(directory);
        }
        HudLayoutMigrationChecks.main(new String[0]);
        if (!List.of(args).contains("--state-only")) clearPreviewAndExitPaths();
        System.out.println("HUD layout checks passed");
    }

    private static void menuGeometry() {
        for (int[] viewport : new int[][]{{220, 160}, {320, 240}, {640, 360}, {854, 480}, {1920, 1080}}) {
            var menu = HudLayoutModel.menu(viewport[0], viewport[1]);
            var panel = menu.panel();
            check(panel.x() >= 0 && panel.y() >= 0 && panel.x() + panel.width() <= viewport[0]
                    && panel.y() + panel.height() <= viewport[1], "ESC menu escaped the viewport");
            check(menu.buttons().size() == 4, "ESC menu must expose exactly four actions");
            int bottom = panel.y();
            for (var button : menu.buttons()) {
                check(button.x() >= panel.x() && button.y() >= bottom
                        && button.x() + button.width() <= panel.x() + panel.width()
                        && button.y() + button.height() <= panel.y() + panel.height(), "ESC actions overlap or escape panel");
                bottom = button.y() + button.height();
            }
            check(menu.messageY() >= bottom && menu.messageY() + 9 <= panel.y() + panel.height(),
                    "Save feedback has no visible line below the four actions");
        }
    }

    private static void draftLifecycle(java.nio.file.Path file, Map<String, HudLayoutModel.Position> initial) throws IOException {
        HudLayoutStore.write(file, initial);
        var input = new LinkedHashMap<>(initial);
        var draft = new HudLayoutDraft(input);
        input.clear();
        check(draft.original().equals(initial), "Entry snapshot aliased its caller's mutable map");
        draft.clear();
        check(draft.positions().isEmpty() && draft.original().equals(initial)
                && HudLayoutStore.read(file).equals(initial), "Restore initial positions saved or destroyed the entry snapshot");
        draft.put("vitals", new HudLayoutModel.Position(.13, .27));
        var edited = Map.copyOf(draft.positions());
        try { draft.positions().clear(); throw new AssertionError("Draft view permits external edits"); }
        catch (UnsupportedOperationException expected) {}
        // A real filesystem failure must leave both retry and discard choices intact.
        var blocked = file.resolve("cannot-create-under-file.json");
        try { draft.save(next -> HudLayoutStore.write(blocked, next)); throw new AssertionError("File failure was swallowed"); }
        catch (IOException expected) {}
        check(draft.positions().equals(edited) && draft.original().equals(initial)
                && HudLayoutStore.read(file).equals(initial), "Failed save lost the draft or changed the entry layout");
        var saved = draft.save(next -> HudLayoutStore.write(file, next));
        check(saved.equals(edited) && HudLayoutStore.read(file).equals(edited), "Retry did not persist the retained draft");
        draft.put("equipment", new HudLayoutModel.Position(.75, .25));
        check(saved.equals(edited), "Committed snapshot changed with later draft edits");
        var reset = new HudLayoutDraft(saved);
        reset.clear();
        reset.save(next -> HudLayoutStore.write(file, next));
        check(HudLayoutStore.read(file).isEmpty() && reset.original().equals(saved), "Explicit reset/save did not clear only personal placements");
    }

    private static void clearPreviewAndExitPaths() throws Exception {
        // Inspect the installed 1.21 Screen entry rather than assuming that renderWithTooltip is overridable.
        var screen = node("net/minecraft/client/gui/screens/Screen");
        check((method(screen, "renderWithTooltip").access & Opcodes.ACC_FINAL) != 0
                && calls(method(screen, "renderWithTooltip"), screen.name, "render"), "Native tooltip rendering entry changed");
        check(calls(method(screen, "render"), screen.name, "renderBackground")
                && calls(method(screen, "renderBackground"), screen.name, "renderBlurredBackground")
                && calls(method(screen, "renderBlurredBackground"), "net/minecraft/client/renderer/GameRenderer", "processBlurEffect"),
                "Actual default blur call chain changed; recheck editor overrides");
        var editor = node("com/example/examplemod/hud/client/HudLayoutEditorScreen");
        check(calls(method(editor, "movable"), "com/example/examplemod/hud/client/MinimapPlacementPolicy", "movable")
                && calls(method(editor, "movable"), "com/example/examplemod/hud/client/XaeroHudBridge", "available")
                && calls(method(editor, "render"), "com/example/examplemod/hud/client/MinimapPlacementPolicy", "nativeMapComponent"),
                "Editor movement or unavailable feedback ignores minimap child components");
        var nativeBridge = node("com/example/examplemod/hud/client/XaeroHudBridge$Native");
        check(calls(method(nativeBridge, "ready"), "xaero/hud/minimap/module/MinimapSession", "isActive"),
                "Inactive native sessions can save fallback placements before pending reset");
        for (String background : List.of("renderBackground", "renderBlurredBackground")) {
            long instructions = java.util.Arrays.stream(method(editor, background).instructions.toArray())
                    .filter(i -> i.getOpcode() >= 0).count();
            check(instructions == 1 && method(editor, background).instructions.getLast().getOpcode() == Opcodes.RETURN,
                    "Editor background can blur, darken or replace the world");
        }
        var init = method(editor, "init");
        var menuKeys = java.util.Arrays.stream(init.instructions.toArray()).filter(i -> i instanceof LdcInsnNode)
                .map(i -> ((LdcInsnNode) i).cst).filter(String.class::isInstance).map(String.class::cast)
                .filter(s -> s.startsWith("screen.tacz_pubg.hud_layout.") && !s.endsWith("load_error")).toList();
        check(menuKeys.equals(List.of("screen.tacz_pubg.hud_layout.save_exit", "screen.tacz_pubg.hud_layout.discard_exit",
                "screen.tacz_pubg.hud_layout.continue_editing", "screen.tacz_pubg.hud_layout.restore_initial")),
                "Editor regained persistent selectors or lost an explicit ESC action");
        check(java.util.Arrays.stream(init.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("menuOpen")),
                "Menu action widgets must be conditional on ESC menu state");
        for (String action : List.of("saveAndExit", "discardAndExit")) {
            var exit = method(editor, action);
            check(calls(exit, "net/minecraft/client/Minecraft", "setScreen")
                    && java.util.Arrays.stream(exit.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("parent"))
                    && !calls(exit, "net/minecraft/client/gui/screens/Screen", "onClose"), "Exit can skip the parent page's unsaved draft");
        }
        check(calls(method(editor, "onClose"), editor.name, "setMenu")
                && !calls(method(editor, "onClose"), "net/minecraft/client/Minecraft", "setScreen"), "ESC discarded the draft instead of opening its menu");
        var registration = node("com/example/examplemod/hud/client/HudLayoutRegistration");
        check(calls(method(registration, "previewOnly"), "net/neoforged/neoforge/client/event/RenderGuiEvent$Pre", "setCanceled"),
                "Editor samples can double up with the live game HUD");
    }
    private static ClassNode node(String name) throws IOException {
        try (var input = HudLayoutChecks.class.getResourceAsStream("/" + name + ".class")) {
            check(input != null, "Missing runtime class: " + name);
            var node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
        }
    }
    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing method " + type.name + "." + name));
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        return java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode m && m.owner.equals(owner) && m.name.equals(name));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
