package com.example.examplemod.inventory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Read the installed r7 and mapped Minecraft classfiles, without initializing Minecraft. */
public final class PubgInventoryInputAbiChecks {
    private static int checks;
    private static final String GAMEPLAY = "com/tacz/guns/client/gameplay/";
    private static final String MIXINS = "com/example/examplemod/inventory/mixin/";
    public static void main(String[] args) throws Exception {
        Path installed = Path.of("libs/tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar");
        check(Files.isRegularFile(installed), "installed TaCZ r7 is present");
        try (var jar = new JarFile(installed.toFile())) {
            var shoot = read(jar, GAMEPLAY + "LocalPlayerShoot");
            method(shoot, "shoot", "()Lcom/tacz/guns/api/entity/ShootResult;");
            method(shoot, "chargeShoot", "(Z)Z");
            var aim = read(jar, GAMEPLAY + "LocalPlayerAim");
            method(aim, "aim", "(Z)V");
            method(aim, "tickAimingProgress", "()V");
            var melee = read(jar, GAMEPLAY + "LocalPlayerMelee");
            method(melee, "melee", "()V");
            var data = read(jar, GAMEPLAY + "LocalPlayerDataHolder");
            for (String field : List.of("chargeProgress", "clientAimingProgress", "oldAimingProgress"))
                check(data.fields.stream().anyMatch(f -> f.name.equals(field) && f.desc.equals("F")), "r7 float state " + field);
            for (String field : List.of("isCharging", "clientIsAiming"))
                check(data.fields.stream().anyMatch(f -> f.name.equals(field) && f.desc.equals("Z")), "r7 boolean state " + field);
            method(read(jar, "com/tacz/guns/api/client/gameplay/IClientPlayerGunOperator"), "getDataHolder", "()L" + GAMEPLAY + "LocalPlayerDataHolder;");
            var refit = read(jar, "com/tacz/guns/client/gui/GunRefitScreen");
            method(refit, "<init>", "()V");
            method(refit, "init", "()V");
            method(refit, "render", "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V");
            method(refit, "onClose", "()V");
            mixin("PubgInventoryShootMixin", shoot);
            mixin("PubgInventoryAimMixin", aim);
            mixin("PubgInventoryMeleeMixin", melee);
            refitRenderMixin(read(jar, "com/tacz/guns/client/event/FirstPersonRenderGunEvent"));
        }
        var minecraft = read("net/minecraft/client/Minecraft");
        method(minecraft, "startAttack", "()Z");
        method(minecraft, "continueAttack", "(Z)V");
        method(minecraft, "startUseItem", "()V");
        mixin("PubgInventoryCombatMixin", minecraft);
        System.out.println("PubgInventoryInputAbiChecks: " + checks + " installed TaCZ r7 / Minecraft ABI checks passed; no game launched.");
    }
    private static void refitRenderMixin(ClassNode target) throws Exception {
        var nativeMethod = method(target, "applyFirstPersonGunTransform",
                "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;"
                        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/tacz/guns/client/model/BedrockGunModel;F)V");
        check((nativeMethod.access & Opcodes.ACC_STATIC) != 0, "refit transform is a static native render entry");
        var node = read(MIXINS + "PubgGunRefitRenderMixin");
        var mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        check(List.of(Type.getObjectType(target.name)).equals(value(mixin, "value")), "refit scale targets installed native renderer");
        var handler = method(node, "taczPubg$scaleRefitPreview", nativeMethod.desc.substring(0, nativeMethod.desc.length() - 2)
                + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        check((handler.access & Opcodes.ACC_STATIC) != 0, "refit scale handler is static");
        var injection = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        check(List.of(nativeMethod.name).equals(value(injection, "method")), "refit scale selector resolves to native transform");
        @SuppressWarnings("unchecked") var sites = (List<AnnotationNode>) value(injection, "at");
        check(sites.size() == 1 && "TAIL".equals(value(sites.getFirst(), "value")), "refit scale follows all native camera transforms");
        check(!Boolean.TRUE.equals(value(injection, "cancellable")), "refit scale cannot suppress native rendering");
    }
    private static void mixin(String name, ClassNode target) throws Exception {
        var node = read(MIXINS + name);
        var mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        check(List.of(Type.getObjectType(target.name)).equals(value(mixin, "value")), name + " uses the verified native class");
        for (var field : node.fields) {
            if (field.visibleAnnotations == null || field.visibleAnnotations.stream().noneMatch(a -> a.desc.endsWith("/Shadow;"))) continue;
            check(target.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)), name + " shadow field ABI: " + field.name);
        }
        for (var method : node.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var injection : method.visibleAnnotations) {
                boolean inject = injection.desc.endsWith("/Inject;"), variable = injection.desc.endsWith("/ModifyVariable;");
                if (!inject && !variable) continue;
                @SuppressWarnings("unchecked") var selectors = (List<String>) value(injection, "method");
                check(selectors != null && selectors.size() == 1, name + " has one explicit selector");
                var targets = target.methods.stream().filter(m -> m.name.equals(selectors.getFirst())).toList();
                check(targets.size() == 1, name + " selector resolves to one native method");
                var nativeMethod = targets.getFirst();
                check((nativeMethod.access & Opcodes.ACC_STATIC) == 0, name + " native entry is an instance method");
                if (inject) {
                    check(Boolean.TRUE.equals(value(injection, "cancellable")), name + " guard can cancel");
                    var params = Type.getArgumentTypes(method.desc);
                    var args = Type.getArgumentTypes(nativeMethod.desc);
                    check(params.length == args.length + 1, name + " handler has exact native arguments plus callback");
                    for (int i = 0; i < args.length; i++) check(params[i].equals(args[i]), name + " argument " + i + " matches native ABI");
                    String callback = Type.getReturnType(nativeMethod.desc).equals(Type.VOID_TYPE) ? "CallbackInfo" : "CallbackInfoReturnable";
                    check(params[params.length - 1].getInternalName().equals("org/spongepowered/asm/mixin/injection/callback/" + callback), name + " callback matches return type");
                } else {
                    check(method.desc.equals("(Z)Z") && nativeMethod.desc.equals("(Z)V"), name + " modifies the native aim boolean");
                    check(Boolean.TRUE.equals(value(injection, "argsOnly")), name + " modifies only method arguments");
                }
            }
        }
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        var matches = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
        check(matches.size() == 1, node.name + "." + name + desc);
        return matches.getFirst();
    }
    private static ClassNode read(String name) throws Exception {
        try (InputStream in = PubgInventoryInputAbiChecks.class.getClassLoader().getResourceAsStream(name + ".class")) {
            check(in != null, "class resource " + name);
            return read(in);
        }
    }
    private static ClassNode read(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class");
        check(entry != null, "installed r7 class " + name);
        try (InputStream in = jar.getInputStream(entry)) { return read(in); }
    }
    private static ClassNode read(InputStream in) throws Exception {
        var node = new ClassNode();
        new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations != null) for (var a : annotations) if (a.desc.equals(descriptor)) return a;
        throw new AssertionError("Missing annotation " + descriptor);
    }
    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }
    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
}
