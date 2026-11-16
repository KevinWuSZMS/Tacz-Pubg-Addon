package com.example.examplemod.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Reads mapped Minecraft and our mixin bytecode without loading game classes or applying mixins. */
public final class CombatAbiChecks {
    private static final String ROOT = "com/example/examplemod/combat/mixin/";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private static final String CONNECTION = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String MODE = "net/minecraft/server/level/ServerPlayerGameMode";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String KEYBOARD = "net/minecraft/client/KeyboardHandler";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String SERVER_PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    private static final Map<String, ClassNode> CLASSES = new HashMap<>();
    private static int checks;

    private record Target(String owner, String method, String descriptor) {
        String key() { return owner + "." + method; }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> owners = Map.of(
                "CombatConnectionMixin", CONNECTION, "CombatGameModeMixin", MODE,
                "CombatPlayerPoseMixin", PLAYER, "CombatJumpMixin", LIVING,
                "CombatKeyboardMixin", KEYBOARD, "CombatMinecraftMixin", MINECRAFT,
                "CombatLocalHealthMixin", LOCAL_PLAYER);
        Map<String, Target> expected = new LinkedHashMap<>();
        for (String packet : List.of("ContainerClick", "Interact", "PlayerAction", "SetCarriedItem"))
            add(expected, CONNECTION, "handle" + packet,
                    "(Lnet/minecraft/network/protocol/game/Serverbound" + packet + "Packet;)V");
        String use = SERVER_PLAYER + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;"
                + "Lnet/minecraft/world/InteractionHand;";
        add(expected, MODE, "useItem", "(" + use + ")Lnet/minecraft/world/InteractionResult;");
        add(expected, MODE, "useItemOn", "(" + use + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;");
        add(expected, PLAYER, "updatePlayerPose", "()V");
        add(expected, LIVING, "jumpFromGround", "()V");
        add(expected, KEYBOARD, "keyPress", "(JIIII)V");
        add(expected, MINECRAFT, "startAttack", "()Z");
        add(expected, MINECRAFT, "startUseItem", "()V");
        add(expected, LOCAL_PLAYER, "hurtTo", "(F)V");

        JsonObject config;
        try (var input = resource("tacz_pubg.combat.mixins.json");
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        }
        check(config.get("required").getAsBoolean(), "combat mixins fail visibly when incompatible");
        check(config.get("package").getAsString().replace('.', '/').equals(ROOT.substring(0, ROOT.length() - 1)),
                "manifest names the compiled combat package");
        check(config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() >= 1,
                "injection failure cannot silently disable restrictions");
        Set<String> seenMixins = new HashSet<>(), seenTargets = new HashSet<>();
        for (String side : List.of("mixins", "client")) for (var entry : config.getAsJsonArray(side)) {
            String name = entry.getAsString();
            check(seenMixins.add(name), "manifest does not duplicate " + name);
            if (name.equals("CombatCarpetDeathMixin")) {
                check(side.equals("mixins"), "Carpet death compatibility is available on the server");
                verifyCarpetDeath();
                continue;
            }
            check(owners.containsKey(name), "manifest mixin is explicitly covered: " + name);
            String owner = owners.get(name);
            check(owner.startsWith("net/minecraft/client/") == side.equals("client"),
                    name + " is assigned to the correct physical side");
            ClassNode mixin = type(ROOT + name), nativeType = type(owner);
            AnnotationNode annotation = annotation(mixin, MIXIN);
            check(List.of(Type.getObjectType(owner)).equals(value(annotation, "value")), name + " targets the verified native class");
            if (name.equals("CombatKeyboardMixin"))
                check(Integer.valueOf(1400).equals(value(annotation, "priority")), "rescue keyboard gate precedes medicine and loot input");
            verifyShadows(mixin, nativeType);
            int injectors = 0;
            for (MethodNode handler : mixin.methods) {
                AnnotationNode inject = find(handler.visibleAnnotations, handler.invisibleAnnotations, INJECT);
                if (inject == null) continue;
                injectors++;
                @SuppressWarnings("unchecked") var selectors = (List<String>) value(inject, "method");
                check(selectors != null && !selectors.isEmpty(), name + "." + handler.name + " selects a target");
                check(Boolean.TRUE.equals(value(inject, "cancellable")), handler.name + " can cancel its target");
                @SuppressWarnings("unchecked") var points = (List<AnnotationNode>) value(inject, "at");
                check(points != null && points.size() == 1 && "HEAD".equals(value(points.getFirst(), "value")),
                        handler.name + " runs before original behavior");
                for (String selector : selectors) {
                    // The current bare names must be unambiguous in the actual mapped class.
                    check(!selector.contains("("), "explicitly review descriptor selector changes: " + selector);
                    Target target = expected.get(owner + "." + selector);
                    check(target != null, "new injection target requires an ABI contract: " + owner + "." + selector);
                    check(seenTargets.add(target.key()), "target is not accidentally injected twice: " + target.key());
                    MethodNode nativeMethod = unique(nativeType, selector);
                    verifyTarget(nativeType, nativeMethod, target.descriptor, false);
                    verifyHandler(mixin, handler, nativeMethod);
                    if (owner.equals(CONNECTION)) verifyThreadGate(handler);
                    System.out.println("TARGET " + target.owner + "." + target.method + target.descriptor);
                }
            }
            check(injectors > 0, name + " has at least one verified injection");
        }
        var allMixins = new HashSet<>(owners.keySet());
        allMixins.add("CombatCarpetDeathMixin");
        check(seenMixins.equals(allMixins), "all native and optional Carpet combat mixins are registered exactly once");
        check(seenTargets.equals(expected.keySet()), "all combat target signatures are exercised");

        // Pose is a Player/Entity API while the shared jump hook belongs to LivingEntity.
        inherited(PLAYER, "setForcedPose", "(Lnet/minecraft/world/entity/Pose;)V", false);
        inherited(PLAYER, "getForcedPose", "()Lnet/minecraft/world/entity/Pose;", false);
        inherited(ENTITY, "setPose", "(Lnet/minecraft/world/entity/Pose;)V", false);
        inherited(ENTITY, "getPose", "()Lnet/minecraft/world/entity/Pose;", false);
        inherited("net/minecraft/server/level/ServerPlayer", "die", "(Lnet/minecraft/world/damagesource/DamageSource;)V", false);
        inherited("net/minecraft/server/MinecraftServer", "isSameThread", "()Z", false);
        System.out.println("CombatAbiChecks: " + checks + " actual MC 1.21.1 / mixin bytecode checks passed; no game initialized.");
    }

    private static void add(Map<String, Target> expected, String owner, String method, String descriptor) {
        Target target = new Target(owner, method, descriptor);
        expected.put(target.key(), target);
    }

    private static void verifyCarpetDeath() throws Exception {
        String owner = "carpet/patches/EntityPlayerMPFake";
        String descriptor = "(Lnet/minecraft/world/damagesource/DamageSource;)V";
        ClassNode mixin = type(ROOT + "CombatCarpetDeathMixin"), carpet = type(owner);
        annotation(mixin, "Lorg/spongepowered/asm/mixin/Pseudo;");
        AnnotationNode target = annotation(mixin, MIXIN);
        check(List.of(owner.replace('/', '.')).equals(value(target, "targets")),
                "optional compatibility targets the installed Carpet fake-player class");
        check(Boolean.FALSE.equals(value(target, "remap")), "Carpet class names are not remapped as vanilla targets");
        MethodNode death = unique(carpet, "die");
        verifyTarget(carpet, death, descriptor, false);
        MethodNode handler = mixin.methods.stream()
                .filter(method -> find(method.visibleAnnotations, method.invisibleAnnotations, INJECT) != null)
                .findFirst().orElseThrow();
        verifyHandler(mixin, handler, death);
        AnnotationNode inject = find(handler.visibleAnnotations, handler.invisibleAnnotations, INJECT);
        check(List.of("die").equals(value(inject, "method")) && Boolean.TRUE.equals(value(inject, "cancellable")),
                "Carpet death can be stopped after the superclass chooses DBNO or final death");
        @SuppressWarnings("unchecked") var points = (List<AnnotationNode>) value(inject, "at");
        check(points != null && points.size() == 1, "Carpet compatibility has exactly one reviewed injection point");
        AnnotationNode point = points.getFirst();
        check("INVOKE".equals(value(point, "value"))
                && ("Lnet/minecraft/server/level/ServerPlayer;die" + descriptor).equals(value(point, "target")),
                "Carpet compatibility follows the actual superclass death call");
        Object shift = value(point, "shift");
        check(shift instanceof String[] values && values.length == 2 && values[1].equals("AFTER"),
                "The cancellation runs after NeoForge death events, preserving real death/drop processing");
        int index = 0, superclass = -1, heal = -1, disconnect = -1, calls = 0;
        for (var instruction : death.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/server/level/ServerPlayer")
                        && call.name.equals("die") && call.desc.equals(descriptor)) {
                    superclass = index;
                    calls++;
                    check(call.getOpcode() == Opcodes.INVOKESPECIAL, "The injection anchor is the superclass death invocation");
                }
                if (call.name.equals("setHealth") && heal < 0) heal = index;
                if (call.name.equals("kill") && disconnect < 0) disconnect = index;
            }
            index++;
        }
        check(calls == 1 && superclass >= 0 && heal > superclass && disconnect > heal,
                "Installed Carpet resets health and queues removal after exactly one superclass death call");
        boolean downed = false, ownedFinalDeath = false;
        for (var instruction : handler.instructions) if (instruction instanceof MethodInsnNode call) {
            downed |= call.owner.equals("com/example/examplemod/combat/CombatService") && call.name.equals("downed");
            ownedFinalDeath |= call.owner.equals("com/example/examplemod/zones/match/bot/MatchBotService")
                    && call.name.equals("retainAfterDeath");
        }
        check(downed && ownedFinalDeath, "The hook distinguishes downed players and retained owned final deaths");
        System.out.println("TARGET " + owner + ".die" + descriptor + " AFTER ServerPlayer.die");
    }

    private static void verifyTarget(ClassNode owner, MethodNode method, String descriptor, boolean isStatic) throws Exception {
        check(method.desc.equals(descriptor), "parameters/return type match " + owner.name + "." + method.name);
        check(((method.access & Opcodes.ACC_STATIC) != 0) == isStatic, "static/instance matches " + owner.name + "." + method.name);
        check((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 && method.instructions.size() > 0,
                "target has injectable bytecode: " + owner.name + "." + method.name);
        for (Type parameter : Type.getArgumentTypes(descriptor)) verifyReferencedType(parameter);
        verifyReferencedType(Type.getReturnType(descriptor));
    }

    private static void verifyHandler(ClassNode mixin, MethodNode handler, MethodNode target) throws Exception {
        Type result = Type.getReturnType(target.desc);
        String callback = result.getSort() == Type.VOID ? CI : CIR;
        var arguments = new ArrayList<Type>(Arrays.asList(Type.getArgumentTypes(target.desc)));
        arguments.add(Type.getType(callback));
        String expected = Type.getMethodDescriptor(Type.VOID_TYPE, arguments.toArray(Type[]::new));
        check(handler.desc.equals(expected), "handler captures correct arguments/callback: " + mixin.name + "." + handler.name);
        check(((handler.access ^ target.access) & Opcodes.ACC_STATIC) == 0,
                "handler and native method share instance/static semantics: " + handler.name);
        verifyReferencedType(Type.getType(callback));
        if (result.getSort() != Type.VOID) {
            String boxed = result.getSort() == Type.BOOLEAN ? "Ljava/lang/Boolean;" : result.getDescriptor();
            check(handler.signature != null && handler.signature.endsWith(CIR.substring(0, CIR.length() - 1) + "<" + boxed + ">;)V"),
                    "CallbackInfoReturnable generic matches actual return value: " + handler.name);
        }
    }

    private static void verifyShadows(ClassNode mixin, ClassNode target) throws Exception {
        for (FieldNode shadow : mixin.fields) if (find(shadow.visibleAnnotations, shadow.invisibleAnnotations, SHADOW) != null) {
            FieldNode actual = null;
            ClassNode owner = target;
            while (owner != null && actual == null) {
                for (FieldNode field : owner.fields) if (field.name.equals(shadow.name)) actual = field;
                if (actual == null) owner = owner.superName == null ? null : type(owner.superName);
            }
            check(actual != null, "native shadow exists: " + target.name + "." + shadow.name);
            check(actual.desc.equals(shadow.desc), "shadow descriptor matches: " + shadow.name);
            check(((actual.access ^ shadow.access) & Opcodes.ACC_STATIC) == 0, "shadow static/instance matches: " + shadow.name);
            verifyReferencedType(Type.getType(shadow.desc));
        }
    }

    private static void verifyThreadGate(MethodNode method) {
        int thread = -1, state = -1, index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("isSameThread") && call.desc.equals("()Z") && thread < 0) thread = index;
                if (call.owner.equals("com/example/examplemod/combat/CombatService") && state < 0) state = index;
            }
            index++;
        }
        check(thread >= 0 && state > thread, "packet HEAD gate reaches server thread before reading combat state: " + method.name);
    }

    private static void inherited(String owner, String name, String descriptor, boolean isStatic) throws Exception {
        ClassNode node = type(owner);
        while (node != null) {
            for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) {
                verifyTarget(node, method, descriptor, isStatic);
                return;
            }
            node = node.superName == null ? null : type(node.superName);
        }
        throw new AssertionError("Missing inherited native method " + owner + "." + name + descriptor);
    }

    private static void verifyReferencedType(Type type) throws Exception {
        if (type.getSort() == Type.ARRAY) verifyReferencedType(type.getElementType());
        else if (type.getSort() == Type.OBJECT) type(type.getInternalName());
    }

    private static MethodNode unique(ClassNode owner, String name) {
        var matches = owner.methods.stream().filter(method -> method.name.equals(name)).toList();
        check(matches.size() == 1, "bare target name is unique: " + owner.name + "." + name);
        return matches.getFirst();
    }

    private static AnnotationNode annotation(ClassNode node, String descriptor) {
        AnnotationNode result = find(node.visibleAnnotations, node.invisibleAnnotations, descriptor);
        check(result != null, "annotation exists: " + node.name + " " + descriptor);
        return result;
    }

    private static AnnotationNode find(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        if (visible != null) for (var annotation : visible) if (annotation.desc.equals(descriptor)) return annotation;
        if (invisible != null) for (var annotation : invisible) if (annotation.desc.equals(descriptor)) return annotation;
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }

    private static ClassNode type(String owner) throws Exception {
        ClassNode cached = CLASSES.get(owner);
        if (cached != null) return cached;
        try (InputStream input = resource(owner + ".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            check(node.name.equals(owner), "class resource has expected identity: " + owner);
            CLASSES.put(owner, node);
            return node;
        }
    }

    private static InputStream resource(String name) {
        InputStream input = CombatAbiChecks.class.getClassLoader().getResourceAsStream(name);
        check(input != null, "actual runtime class/resource exists: " + name);
        return input;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
