package com.example.examplemod.gunbalance.ballistics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Pure age-gate checks plus exact installed r7 bytecode/mixin ABI checks; never loads Minecraft classes. */
public final class BulletRangeChecks {
    private static final String OWNER = "com/tacz/guns/entity/EntityKineticBullet";
    private static int checks;

    public static void main(String[] args) throws Exception {
        require(defer(100, 0, 50, 0, .01F, false), "Expired age may extend before 512");
        require(!defer(512, 0, 50, 0, .01F, false), "At 512 native expired age may clean up");
        require(!defer(0, -513, 50, 0, .01F, false), "Use X/Z horizontal displacement");
        require(defer(300, 300, 50, 50, .01F, false), "Diagonal horizontal distance is not per-axis threshold");
        require(!defer(400, 400, 50, 0, .01F, false), "Diagonal crossing returns original age cleanup");
        require(!defer(100, 0, 0, 0, 0, false), "Vertical-only or stationary bullet gets native cleanup");
        require(!defer(Double.NaN, 0, 50, 0, 0, false), "Nonfinite location gets native cleanup");
        require(!defer(0, 0, Double.POSITIVE_INFINITY, 0, 0, false), "Nonfinite motion gets native cleanup");
        require(!defer(0, 0, 50, 0, Float.NaN, false), "Nonfinite drag gets native cleanup");
        require(!defer(0, 0, 50, 0, 10F, false), "Nonphysical source friction never grants permanent age extension");
        require(!defer(0, 0, 50, 0, -1F, false), "Negative drag does not grant extension");
        require(!defer(500, 0, .1, 0, .01F, false), "Finite drag endpoint below threshold cannot be fixed with time");
        require(defer(500, 0, .3, 0, .01F, false), "Finite drag endpoint beyond threshold can extend");
        require(!defer(500, 0, -.1, 0, .01F, false), "Inward segment with both endpoints inside is unreachable");
        require(defer(500, 0, -20, 0, .01F, false), "A reflected bullet can pass the origin and reach opposite radius");
        require(!defer(100, 0, 1, 0, .4F, false), "Native water damping cannot promise 512");
        require(defer(100, 0, 1, 0, 0, true), "Quadratic drag stays in existing integrator and uses progress");
        require(defer(100, 0, 0.000001, 0, Float.MIN_VALUE, false), "Sub-ULP positive friction rounds to native vacuum");
        var slow = new BulletRangePolicy.Progress();
        for (int i = 0; i < 100; i++) require(BulletRangePolicy.postponeAgeCleanup(100 + i * 0.0000001, 0,
                0.0000001, 0, 0, false, slow), "Real slow but representable native progress cannot be classified as stalled");

        var stalled = new BulletRangePolicy.Progress();
        require(BulletRangePolicy.postponeAgeCleanup(100, 0, 1, 0, 0, true, stalled), "First age observation starts guard");
        for (int i = 1; i < BulletRangePolicy.MAX_STALLED_AGE_TICKS; i++)
            require(BulletRangePolicy.postponeAgeCleanup(100, 0, 1, 0, 0, true, stalled), "Short stationary interference can recover");
        require(!BulletRangePolicy.postponeAgeCleanup(100, 0, 1, 0, 0, true, stalled), "No-progress projectile cannot persist forever");
        var moving = new BulletRangePolicy.Progress();
        for (int i = 0; i < 200; i++)
            require(BulletRangePolicy.postponeAgeCleanup(100 + i, 0, 1, 0, 0, true, moving), "Actual motion resets stall protection");
        var resumes = new BulletRangePolicy.Progress();
        for (int i = 0; i < 10; i++) BulletRangePolicy.postponeAgeCleanup(100, 0, 1, 0, 0, true, resumes);
        require(BulletRangePolicy.postponeAgeCleanup(101, 0, 1, 0, 0, true, resumes), "Resumed flight resets stalled counter");
        for (int i = 1; i < BulletRangePolicy.MAX_STALLED_AGE_TICKS; i++)
            require(BulletRangePolicy.postponeAgeCleanup(101, 0, 1, 0, 0, true, resumes), "Reset grants a new bounded stall window");

        // Independently sample the entire future native segment. Convex radial distance cannot
        // exceed both endpoints; compare the policy's finite-endpoint decision to this oracle.
        Random random = new Random(512);
        for (int i = 0; i < 10000; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = random.nextDouble() * 511.9;
            double dx = Math.cos(angle) * radius, dz = Math.sin(angle) * radius;
            double vx = random.nextDouble() * 80 - 40, vz = random.nextDouble() * 80 - 40;
            float friction = (float) (.005 + random.nextDouble() * .3);
            double lost = 1 - (double) (1F - friction);
            double endX = dx + vx / lost, endZ = dz + vz / lost;
            boolean endpointCanReach = Math.hypot(endX, endZ) > 512;
            require(defer(dx, dz, vx, vz, friction, false) == endpointCanReach, "Native future endpoint classification");
            if (!endpointCanReach) for (int part = 1; part <= 10; part++) {
                double q = part / 10.0;
                require(Math.hypot(dx * (1 - q) + endX * q, dz * (1 - q) + endZ * q) <= 512.0000001,
                        "Unreachable native segment never secretly crosses the threshold");
            }
        }
        classfileChecks();
        System.out.println("Bullet horizontal-range age policy and precise native cleanup ABI checks passed: " + checks);
    }

    private static boolean defer(double dx, double dz, double vx, double vz, float friction, boolean quadratic) {
        return BulletRangePolicy.postponeAgeCleanup(dx, dz, vx, vz, friction, quadratic, new BulletRangePolicy.Progress());
    }

    private static void classfileChecks() throws Exception {
        Path jar;
        try (var jars = Files.list(Path.of("libs"))) {
            jar = jars.filter(path -> path.getFileName().toString().startsWith("tacz-")
                    && path.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        ClassNode target = new ClassNode();
        try (ZipFile archive = new ZipFile(jar.toFile()); var stream = archive.getInputStream(archive.getEntry(OWNER + ".class"))) {
            new ClassReader(stream).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        require(target.fields.stream().anyMatch(f -> f.name.equals("startPos") && f.desc.equals("Lnet/minecraft/world/phys/Vec3;")), "Native launch-origin shadow ABI");
        require(target.fields.stream().anyMatch(f -> f.name.equals("friction") && f.desc.equals("F")), "Native drag shadow ABI");
        MethodNode tick = target.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).findFirst().orElseThrow();
        require(countDiscard(tick) == 1, "Installed tick contains exactly one age-only discard call");
        require(target.methods.stream().filter(m -> m.name.equals("onBulletTick")).mapToInt(BulletRangeChecks::countDiscard).sum() == 2,
                "Fuse and depleted-pierce cleanup sites remain outside redirected method");
        require(target.methods.stream().filter(m -> m.name.equals("onHitBlock")).mapToInt(BulletRangeChecks::countDiscard).sum() == 2,
                "Block-hit and explosion cleanup sites remain outside redirected method");
        MethodNode damage = target.methods.stream().filter(m -> m.name.equals("getDamage")).findFirst().orElseThrow();
        require(countDiscard(damage) == 0, "Damage distance does not kill a projectile");

        ClassNode mixin = new ClassNode();
        try (var stream = BulletRangeChecks.class.getResourceAsStream("/com/example/examplemod/gunbalance/mixin/EntityKineticBulletRangeMixin.class")) {
            if (stream == null) throw new AssertionError("New range mixin was not compiled");
            new ClassReader(stream).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("taczPubg$postponeOnlyNativeAgeCleanup")).findFirst().orElseThrow();
        require(handler.desc.equals("(L" + OWNER + ";)V"), "Redirect handler receiver/return ABI");
        List<AnnotationNode> annotations = new ArrayList<>();
        if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
        if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
        AnnotationNode redirect = annotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")).findFirst().orElseThrow();
        require(List.of("tick()V").equals(value(redirect, "method")), "Only tick method is redirected");
        require(Integer.valueOf(1).equals(value(redirect, "require")), "Missing/changed native call fails visibly");
        require(Boolean.FALSE.equals(value(redirect, "remap")), "Native r7 method is not remapped");
        AnnotationNode at = (AnnotationNode) value(redirect, "at");
        require("INVOKE".equals(value(at, "value")) && ("L" + OWNER + ";discard()V").equals(value(at, "target")), "Exact native age discard invocation selected");
        for (var method : mixin.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) require(!List.of("setDeltaMovement", "setPos", "shoot", "shootFromRotation", "setNoGravity").contains(call.name),
                    "Range extension cannot rewrite flight/position/gravity");
        }
    }

    private static int countDiscard(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(OWNER) && call.name.equals("discard") && call.desc.equals("()V")) count++;
        return count;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }
    private static void require(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
