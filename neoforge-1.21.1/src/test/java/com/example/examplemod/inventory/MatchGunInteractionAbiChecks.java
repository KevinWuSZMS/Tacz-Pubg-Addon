package com.example.examplemod.inventory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Verifies actual installed TaCZ r7 and MC call sites without bootstrapping game registries. */
public final class MatchGunInteractionAbiChecks {
    private static final String INTERACT = "com/tacz/guns/client/input/InteractKey";
    private static final String CLICK = "com/tacz/guns/client/event/ClientPreventGunClick";
    private static final String OVERLAY = "com/tacz/guns/client/gui/overlay/InteractKeyTextOverlay";
    private static final String KEY = "net/minecraft/client/KeyMapping";
    private static final String KEYBOARD = "net/minecraft/client/KeyboardHandler";
    private static final String MIXINS = "com/example/examplemod/inventory/mixin/";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String EVENT = "Lnet/neoforged/neoforge/client/event/InputEvent$";
    private static int checks;

    public static void main(String[] args) throws Exception {
        ClassNode keyboard = type(KEYBOARD);
        target(keyboard, "keyPress", "(JIIII)V", false);
        Path directory = Files.isDirectory(Path.of("run", "mods")) ? Path.of("run", "mods")
                : Path.of("neoforge-1.21.1", "run", "mods");
        Path installed = directory.resolve("tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar");
        check(Files.isRegularFile(installed), "actual installed r7 jar exists");
        try (var jar = new JarFile(installed.toFile())) {
            ClassNode interact = type(jar, INTERACT), click = type(jar, CLICK), overlay = type(jar, OVERLAY);
            target(interact, "onInteractKeyPress", "(" + EVENT + "Key;)V", true);
            target(interact, "onInteractMousePress", "(" + EVENT + "MouseButton$Post;)V", true);
            MethodNode gate = target(click, "onClickInput", "(" + EVENT + "InteractionKeyMappingTriggered;)V", true);
            site(gate, Opcodes.INVOKEVIRTUAL, KEY, "isDown", "()Z", 1);
            MethodNode blockText = target(overlay, "renderBlockText",
                    "(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/phys/BlockHitResult;"
                            + "Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/Minecraft;)V", true);
            MethodNode entityText = target(overlay, "renderEntityText",
                    "(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/phys/EntityHitResult;"
                            + "Lnet/minecraft/client/Minecraft;)V", true);
            fieldSite(blockText, Opcodes.GETSTATIC, INTERACT, "INTERACT_KEY", "L" + KEY + ";", 1);
            fieldSite(entityText, Opcodes.GETSTATIC, INTERACT, "INTERACT_KEY", "L" + KEY + ";", 1);
            MethodNode controller = target(interact, "onInteractControllerPress", "(Z)Z", true);
            check((controller.access & Opcodes.ACC_PUBLIC) != 0, "controller dispatcher is public");
            site(controller, Opcodes.INVOKESTATIC, INTERACT, "interactBlock",
                    "(Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/client/player/LocalPlayer;"
                            + "Lnet/minecraft/client/Minecraft;)V", 1);
            site(controller, Opcodes.INVOKESTATIC, INTERACT, "interactEntity",
                    "(Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/client/Minecraft;)V", 1);
            for (String helper : List.of("interactBlock", "interactEntity")) {
                MethodNode nativeHelper = method(interact, helper);
                site(nativeHelper, Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Minecraft", "startUseItem", "()V", 1);
                check(nativeHelper.instructions.iterator().hasNext(), "native interaction helper has code");
            }
        }
        // NeoForge must offer MouseButton.Pre before shared mappings are queued.
        MethodNode mouse = method(type("net/minecraft/client/MouseHandler"), "onPress");
        check(indexOfCall(mouse, "net/neoforged/neoforge/client/ClientHooks", "onMouseButtonPre")
                < indexOfCall(mouse, KEY, "set"), "mouse Pre interception precedes vanilla key state");
        check(indexOfCall(mouse, "net/neoforged/neoforge/client/ClientHooks", "onMouseButtonPre")
                < indexOfCall(mouse, KEY, "click"), "mouse Pre interception precedes click queue");
        if (args.length == 0 || !args[0].equals("--native-only")) ownMixins();
        System.out.println("Match gun interaction ABI checks: " + checks
                + " passed (installed TaCZ r7 / MC 1.21.1; no game initialization)");
    }

    private static void ownMixins() throws Exception {
        ClassNode keyboard = mixin("MatchGunKeyboardMixin", KEYBOARD);
        check(Integer.valueOf(1100).equals(value(annotation(keyboard.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;"), "priority")), "pickup HEAD precedes throwable quick bindings");
        inject(keyboard, "taczPubg$unifiedGunInteract", "(JIIII" + CALLBACK + ")V", false, "keyPress");
        ClassNode interact = mixin("MatchGunInteractKeyMixin", INTERACT);
        inject(interact, "taczPubg$pickupReplacesKeyboard", "(" + EVENT + "Key;" + CALLBACK + ")V",
                true, "onInteractKeyPress");
        inject(interact, "taczPubg$pickupReplacesMouse", "(" + EVENT + "MouseButton$Post;" + CALLBACK + ")V",
                true, "onInteractMousePress");
        ClassNode click = mixin("MatchGunClickMixin", CLICK);
        redirect(click, "taczPubg$pickupIsInteractModifier", "(L" + KEY + ";)Z",
                List.of("onClickInput"), "INVOKE", "L" + KEY + ";isDown()Z");
        ClassNode text = mixin("MatchGunInteractTextMixin", OVERLAY);
        MethodNode labels = redirect(text, "taczPubg$showPickupBinding", "()L" + KEY + ";",
                List.of("renderBlockText", "renderEntityText"), "FIELD", "L" + INTERACT + ";INTERACT_KEY:L" + KEY + ";");
        var redirect = annotation(labels.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        check(Integer.valueOf(Opcodes.GETSTATIC).equals(value((AnnotationNode) value(redirect, "at"), "opcode")),
                "hint redirect uses GETSTATIC field opcode");
        ClassNode alias = type("com/example/examplemod/inventory/client/MatchGunInteractionClient");
        for (MethodNode method : alias.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call)
                check(!(call.owner.equals(KEY) && call.name.equals("setKey")), "alias never changes saved key binding");
            if (instruction instanceof FieldInsnNode field)
                check(!(field.getOpcode() == Opcodes.PUTSTATIC && field.owner.equals(INTERACT)),
                        "alias never replaces TaCZ key field");
        }
    }

    private static ClassNode mixin(String name, String owner) throws Exception {
        ClassNode node = type(MIXINS + name);
        AnnotationNode annotation = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        check(List.of(Type.getObjectType(owner)).equals(value(annotation, "value")), name + " target class matches");
        return node;
    }

    private static void inject(ClassNode node, String name, String descriptor, boolean isStatic, String target) {
        MethodNode method = target(node, name, descriptor, isStatic);
        AnnotationNode inject = annotation(method.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        check(List.of(target).equals(value(inject, "method")), name + " selector matches verified native method");
        check(Boolean.TRUE.equals(value(inject, "cancellable")), name + " can cancel original listener");
        @SuppressWarnings("unchecked") var at = (List<AnnotationNode>) value(inject, "at");
        check(at.size() == 1 && "HEAD".equals(value(at.getFirst(), "value")), name + " injects at method HEAD");
    }

    private static MethodNode redirect(ClassNode node, String name, String descriptor, List<String> methods,
            String kind, String target) {
        MethodNode method = target(node, name, descriptor, true);
        AnnotationNode redirect = annotation(method.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        check(methods.equals(value(redirect, "method")), name + " selectors match verified native methods");
        AnnotationNode at = (AnnotationNode) value(redirect, "at");
        check(kind.equals(value(at, "value")), name + " injection type matches");
        check(target.equals(value(at, "target")), name + " exact owner/name/descriptor matches");
        check(Boolean.FALSE.equals(value(redirect, "remap")), name + " uses installed Mojmap bytecode");
        return method;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations != null) for (var annotation : annotations) if (annotation.desc.equals(descriptor)) return annotation;
        throw new AssertionError("Missing annotation " + descriptor);
    }
    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        return null;
    }
    private static ClassNode type(String owner) throws Exception {
        try (InputStream input = MatchGunInteractionAbiChecks.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            check(input != null, "class resource exists: " + owner);
            return read(input);
        }
    }
    private static ClassNode type(JarFile jar, String owner) throws Exception {
        try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) { return read(input); }
    }
    private static ClassNode read(InputStream input) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        return node;
    }
    private static MethodNode method(ClassNode node, String name) {
        var matches = node.methods.stream().filter(method -> method.name.equals(name)).toList();
        check(matches.size() == 1, "unique method " + node.name + "." + name);
        return matches.getFirst();
    }
    private static MethodNode target(ClassNode node, String name, String descriptor, boolean isStatic) {
        MethodNode method = method(node, name);
        check(method.desc.equals(descriptor), "descriptor " + node.name + "." + name);
        check(((method.access & Opcodes.ACC_STATIC) != 0) == isStatic, "static/instance " + node.name + "." + name);
        System.out.println("TARGET 1 " + node.name + "." + name + descriptor);
        return method;
    }
    private static void site(MethodNode method, int opcode, String owner, String name, String descriptor, int count) {
        int actual = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == opcode && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) actual++;
        check(actual == count, method.name + " invocation count for " + owner + "." + name + descriptor);
        System.out.println("INVOKE " + actual + " " + method.name + " -> " + owner + "." + name + descriptor);
    }
    private static void fieldSite(MethodNode method, int opcode, String owner, String name, String descriptor, int count) {
        int actual = 0;
        for (var instruction : method.instructions) if (instruction instanceof FieldInsnNode field
                && field.getOpcode() == opcode && field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(descriptor)) actual++;
        check(actual == count, method.name + " field access count for " + owner + "." + name + ":" + descriptor);
        System.out.println("FIELD " + actual + " " + method.name + " -> " + owner + "." + name + ":" + descriptor);
    }
    private static int indexOfCall(MethodNode method, String owner, String name) {
        int index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return index;
            index++;
        }
        throw new AssertionError("Missing " + method.name + " call " + owner + "." + name);
    }
    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
}
