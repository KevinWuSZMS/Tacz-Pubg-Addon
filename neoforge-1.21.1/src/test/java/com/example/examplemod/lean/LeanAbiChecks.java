package com.example.examplemod.lean;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
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
        method(MC + "client/renderer/GameRenderer", "renderLevel", "(Lnet/minecraft/client/DeltaTracker;)V");
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
