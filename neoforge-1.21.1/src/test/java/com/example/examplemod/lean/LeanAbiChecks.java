package com.example.examplemod.lean;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Reads actual installed MC/TaCZ bytecode, including synthetic lambda call sites, without booting a client. */
public final class LeanAbiChecks {
    private static int checks;
    private static final String TACZ = "com/tacz/guns/", MC = "net/minecraft/";
    public static void main(String[] args) throws Exception {
        MethodNode camera = method(MC + "client/Camera", "setup", "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V");
        calls(camera, MC + "client/Camera", "setPosition", "(DDD)V", 1);
        MethodNode pick = method(MC + "client/renderer/GameRenderer", "pick", "(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;");
        calls(pick, MC + "world/entity/Entity", "getEyePosition", "(F)Lnet/minecraft/world/phys/Vec3;", 1);
        calls(pick, MC + "world/entity/Entity", "pick", "(DFZ)Lnet/minecraft/world/phys/HitResult;", 1);
        calls(pick, MC + "world/entity/Entity", "getViewVector", "(F)Lnet/minecraft/world/phys/Vec3;", 1);
        cameraViewFrame(camera);
        sharedCollisionOrigin();
        method(MC + "world/entity/player/Player", "defineSynchedData", "(Lnet/minecraft/network/syncher/SynchedEntityData$Builder;)V");
        String shootArgs = "(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Supplier;Ljava/util/function/Supplier;Lnet/minecraft/world/entity/LivingEntity;)V";
        calls(method(TACZ + "entity/shooter/LivingEntityShoot", "shoot", "(Ljava/util/function/Supplier;Ljava/util/function/Supplier;JFZ)Lcom/tacz/guns/api/entity/ShootResult;"),
                TACZ + "api/item/gun/AbstractGunItem", "shoot", shootArgs, 1);
        MethodNode round = named(TACZ + "item/ModernKineticGunScriptAPI", "lambda$shootOnce$2");
        calls(round, TACZ + "api/item/gun/AbstractGunItem", "doBulletSpread", "(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/projectile/Projectile;IFFFF)V", 1);
        calls(named(TACZ + "item/ModernKineticGunScriptAPI", "shootOnce"), TACZ + "util/CycleTaskHelper", "addCycleTask", "(Ljava/util/function/BooleanSupplier;JI)V", 1);
        MethodNode local = named(TACZ + "client/gameplay/LocalPlayerShoot", "lambda$doShoot$2");
        calls(local, "net/neoforged/neoforge/network/PacketDistributor", "sendToServer", "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V", 1);
        calls(local, MC + "client/Minecraft", "submitAsync", "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", 1);
        int counters = 0, integers = 0, stacks = 0;
        for (Type type : Type.getArgumentTypes(local.desc)) {
            if (type.getDescriptor().equals("Ljava/util/concurrent/atomic/AtomicInteger;")) counters++;
            if (type.getDescriptor().equals("Lnet/minecraft/world/item/ItemStack;")) stacks++;
            if (type == Type.INT_TYPE) integers++;
        }
        check(counters == 1 && integers == 1 && stacks == 1, "argsOnly locals identify one counter, burst size and held gun");
        shotScheduling(local);
        drawSessionEvent();
        outboundInventoryHook();
        method(TACZ + "entity/EntityKineticBullet", "<init>", "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;ZLcom/tacz/guns/resource/pojo/data/gun/GunData;Lcom/tacz/guns/resource/pojo/data/gun/BulletData;)V");
        check(load(TACZ + "entity/EntityKineticBullet").fields.stream().anyMatch(f -> f.name.equals("startPos") && f.desc.equals("Lnet/minecraft/world/phys/Vec3;")), "native startPos field");
        MethodNode tracer = named(TACZ + "client/renderer/entity/EntityBulletRenderer", "lambda$renderTracerAmmo$1");
        calls(tracer, TACZ + "entity/EntityKineticBullet", "getFirstPersonRenderOffset", "()Lorg/joml/Vector3f;", 1);
        calls(tracer, "com/mojang/blaze3d/vertex/PoseStack", "translate", "(DDD)V", 2);
        for (String path : new String[]{"findEntityOnPath", "findEntitiesOnPath"})
            calls(named(TACZ + "util/EntityUtil", path), MC + "world/level/Level", "getEntities", "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", 1);
        method(TACZ + "util/EntityUtil", "getHitResult", "(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lcom/tacz/guns/entity/EntityKineticBullet$EntityResult;");
        method(TACZ + "client/event/FirstPersonRenderGunEvent", "applyFirstPersonGunTransform", "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/tacz/guns/client/model/BedrockGunModel;F)V");
        method(TACZ + "client/renderer/item/GunItemRendererWrapper", "renderFirstPersonInner", "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V");
        method(TACZ + "client/model/BedrockAttachmentModel", "getBedrockPartCenter", "(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;)Lorg/joml/Vector3f;");
        for (String name : new String[]{"renderBothAccelerated", "renderSightAccelerated", "renderScopeAccelerated"}) {
            MethodNode m = method(TACZ + "client/model/BedrockAttachmentModel", name, "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V");
            int count = 0;
            for (var insn : m.instructions) if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode type && type.desc.equals("com/mojang/blaze3d/vertex/PoseStack")) count++;
            check(count == 1, name + " captures exactly one deferred pose");
        }
        calls(method(MC + "client/renderer/entity/LivingEntityRenderer", "render", "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
                MC + "client/model/EntityModel", "setupAnim", "(Lnet/minecraft/world/entity/Entity;FFFFF)V", 1);
        System.out.println("LeanAbiChecks: " + checks + " actual MC/TaCZ bytecode checks passed");
    }
    private static void cameraViewFrame(MethodNode setup) throws Exception {
        String camera = MC + "client/Camera";
        ClassNode type = load(camera);
        for (String name : new String[]{"eyeHeight", "eyeHeightOld"}) {
            check(type.fields.stream().filter(field -> field.name.equals(name) && field.desc.equals("F")
                    && (field.access & Opcodes.ACC_STATIC) == 0).count() == 1,
                    "native camera exposes one instance float for shadowed " + name);
        }
        int eyeHeightAt = fieldIndex(setup, Opcodes.GETFIELD, camera, "eyeHeight", "F");
        int eyeHeightOldAt = fieldIndex(setup, Opcodes.GETFIELD, camera, "eyeHeightOld", "F");
        int positionAt = callIndex(setup, camera, "setPosition", "(DDD)V");
        int zoomAt = callIndex(setup, camera, "getMaxZoom", "(F)F");
        calls(setup, camera, "move", "(FFF)V", 2);
        int firstMoveAt = Integer.MAX_VALUE;
        for (var instruction : setup.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(camera)
                    && call.name.equals("move") && call.desc.equals("(FFF)V"))
                firstMoveAt = Math.min(firstMoveAt, setup.instructions.indexOf(instruction));
        }
        check(eyeHeightAt < positionAt && eyeHeightOldAt < positionAt,
                "native setup reads both smoothed eye heights before positioning the camera");
        check(positionAt < zoomAt && zoomAt < firstMoveAt,
                "shared lean eye is installed before native third-person collision and camera movement");
        String renderer = MC + "client/renderer/GameRenderer";
        MethodNode render = method(renderer, "renderLevel", "(Lnet/minecraft/client/DeltaTracker;)V");
        int pickAt = callIndex(render, renderer, "pick", "(F)V");
        int setupAt = callIndex(render, camera, "setup", "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V");
        check(pickAt < setupAt, "world frame picks before camera setup, requiring a precomputed shared eye");
    }
    private static void sharedCollisionOrigin() throws Exception {
        String lean = "com/example/examplemod/lean/", player = "Lnet/minecraft/world/entity/player/Player;";
        String pose = "Lcom/example/examplemod/lean/LeanGeometry$Pose;", vector = "Lnet/minecraft/world/phys/Vec3;";
        MethodNode begin = method(lean + "LeanService", "beginRender", "(" + player + "F)V");
        int sampleAt = callIndex(begin, MC + "world/entity/player/Player", "getPosition", "(F)" + vector);
        int storeAt = fieldIndex(begin, Opcodes.PUTFIELD, lean + "LeanState", "renderFeet", vector);
        check(sampleAt < storeAt, "world frame freezes the interpolated feet before display collision uses them");
        MethodNode camera = method(lean + "client/LeanCameraView", "begin", "(" + player + "Lnet/minecraft/client/Camera;F)V");
        calls(camera, lean + "LeanService", "renderFeet", "(" + player + "F)" + vector, 1);
        calls(camera, MC + "world/entity/player/Player", "position", "()" + vector, 0);
        MethodNode fraction = method(lean + "LeanService", "fraction", "(" + player + pose + vector + ")D");
        calls(fraction, lean + "LeanGeometry", "clamp", "(" + player + vector + pose + "D)D", 1);
        calls(fraction, lean + "LeanGeometry", "clamp", "(" + player + pose + "D)D", 1);
        MethodNode shotOffset = method(lean + "LeanService", "offsetForFraction", "(" + player + "FD)" + vector);
        calls(shotOffset, lean + "LeanGeometry", "clamp", "(" + player + pose + "D)D", 1);
        calls(shotOffset, lean + "LeanGeometry", "clamp", "(" + player + vector + pose + "D)D", 0);
    }
    private static void shotScheduling(MethodNode round) throws Exception {
        String owner = TACZ + "client/gameplay/LocalPlayerShoot";
        String arguments = "(Ljava/util/concurrent/atomic/AtomicInteger;Lcom/tacz/guns/resource/pojo/data/gun/GunData;Lcom/tacz/guns/api/item/IGun;Lnet/minecraft/world/item/ItemStack;IFLcom/tacz/guns/client/resource/GunDisplayInstance;)V";
        check(round.desc.equals(arguments), "scheduled round retains its counter, gun, burst limit and display arguments");
        MethodNode schedule = method(owner, "doShoot", "(Lcom/tacz/guns/client/resource/GunDisplayInstance;Lcom/tacz/guns/api/item/IGun;Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/resource/pojo/data/gun/GunData;JF)V");
        int scheduleAt = callIndex(schedule, "java/util/concurrent/ScheduledExecutorService", "scheduleAtFixedRate",
                "(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;");
        int factories = 0, factoryAt = -1;
        for (var instruction : schedule.instructions) {
            if (!(instruction instanceof InvokeDynamicInsnNode factory)) continue;
            for (Object argument : factory.bsmArgs) {
                if (argument instanceof Handle implementation && implementation.getOwner().equals(owner)
                        && implementation.getName().equals(round.name) && implementation.getDesc().equals(arguments)) {
                    factories++;
                    factoryAt = schedule.instructions.indexOf(factory);
                    check(Type.getReturnType(factory.desc).getDescriptor().equals("Ljava/lang/Runnable;"), "native round factory returns a runnable");
                    long stacks = java.util.Arrays.stream(Type.getArgumentTypes(factory.desc))
                            .filter(type -> type.getDescriptor().equals("Lnet/minecraft/world/item/ItemStack;")).count();
                    check(stacks == 1, "native scheduler captures one held stack before its delay");
                }
            }
        }
        check(factories == 1 && factoryAt < scheduleAt, "one native round runnable is created before the session-capture scheduling hook");
        int timestampAt = fieldIndex(round, Opcodes.PUTFIELD, TACZ + "client/gameplay/LocalPlayerDataHolder", "clientShootTimestamp", "J");
        int sendAt = callIndex(round, "net/neoforged/neoforge/network/PacketDistributor", "sendToServer",
                "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V");
        int submitAt = callIndex(round, MC + "client/Minecraft", "submitAsync", "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;");
        int incrementAt = callIndex(round, "java/util/concurrent/atomic/AtomicInteger", "getAndIncrement", "()I");
        check(timestampAt < sendAt && sendAt < submitAt && submitAt < incrementAt,
                "native order is timestamp, first packet, animation submission, then burst-counter increment");
    }
    private static void drawSessionEvent() throws Exception {
        String event = TACZ + "api/event/common/GunDrawEvent";
        MethodNode draw = method(TACZ + "client/gameplay/LocalPlayerDraw", "draw", "(Lnet/minecraft/world/item/ItemStack;)V");
        int sideAt = fieldIndex(draw, Opcodes.GETSTATIC, "net/neoforged/fml/LogicalSide", "CLIENT", "Lnet/neoforged/fml/LogicalSide;");
        int eventAt = callIndex(draw, event, "<init>", "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;Lnet/neoforged/fml/LogicalSide;)V");
        int postAt = callIndex(draw, "net/neoforged/bus/api/IEventBus", "post", "(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;");
        check(sideAt < eventAt && eventAt < postAt, "native draw posts its client-side session-change event");
        method(event, "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;");
        method(event, "getLogicalSide", "()Lnet/neoforged/fml/LogicalSide;");
        calls(method(TACZ + "client/event/InventoryEvent", "onPlayerChangeSelect", "(Ljava/lang/Boolean;)V"),
                TACZ + "api/item/IAnimationItem", "isSame", "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z", 1);
    }
    private static void outboundInventoryHook() throws Exception {
        String common = MC + "client/multiplayer/ClientCommonPacketListenerImpl";
        String descriptor = "(Lnet/minecraft/network/protocol/Packet;)V";
        MethodNode send = method(common, "send", descriptor);
        check((send.access & Opcodes.ACC_STATIC) == 0 && (send.access & Opcodes.ACC_PUBLIC) != 0,
                "outbound inventory hook targets the public instance send(Packet) method");
        calls(send, MC + "network/Connection", "send", descriptor, 1);
        ClassNode play = load(MC + "client/multiplayer/ClientPacketListener");
        check(common.equals(play.superName), "play connection inherits the common outbound listener");
        check(play.methods.stream().noneMatch(candidate -> candidate.name.equals("send") && candidate.desc.equals(descriptor)),
                "play connection does not override and bypass the common outbound hook");
    }
    private static int callIndex(MethodNode method, String owner, String name, String descriptor) {
        calls(method, owner, name, descriptor, 1);
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor))
                return method.instructions.indexOf(instruction);
        throw new AssertionError("Missing call already checked: " + name);
    }
    private static int fieldIndex(MethodNode method, int opcode, String owner, String name, String descriptor) {
        int count = 0, position = -1;
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == opcode && field.owner.equals(owner)
                    && field.name.equals(name) && field.desc.equals(descriptor)) {
                count++;
                position = method.instructions.indexOf(instruction);
            }
        }
        check(count == 1, method.name + " has one matching field access to " + owner + "." + name);
        return position;
    }
    private static ClassNode load(String name) throws Exception {
        if (name.startsWith(TACZ)) {
            Path mods = Files.isDirectory(Path.of("run/mods")) ? Path.of("run/mods") : Path.of("neoforge-1.21.1/run/mods");
            Path file;
            try (var files = Files.list(mods)) {
                file = files.filter(p -> p.getFileName().toString().endsWith("tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar")).findFirst().orElseThrow();
            }
            try (var jar = new JarFile(file.toFile()); InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
                ClassNode node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES); return node;
            }
        }
        try (InputStream input = LeanAbiChecks.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new AssertionError("Missing class: " + name);
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES); return node;
        }
    }
    private static MethodNode named(String owner, String name) throws Exception {
        var found = load(owner).methods.stream().filter(m -> m.name.equals(name)).toList();
        check(found.size() == 1, owner + "." + name + " must have one target"); return found.getFirst();
    }
    private static MethodNode method(String owner, String name, String descriptor) throws Exception {
        var found = load(owner).methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst();
        check(found.isPresent(), owner + "." + name + descriptor); return found.orElseThrow();
    }
    private static void calls(MethodNode method, String owner, String name, String descriptor, int expected) {
        int actual = 0;
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) actual++;
        check(actual == expected, method.name + " has " + expected + " call(s) to " + owner + "." + name + " (actual " + actual + ")");
    }
    private static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
}
