package com.example.examplemod.gunbalance.heavy;

import com.example.examplemod.zones.heavy.HeavyWeaponTerrainPolicy;
import com.google.gson.Gson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import net.minecraft.resources.ResourceLocation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Offline launch identity, native serialization, and installed TaCZ explosion-injection compatibility. */
public final class HeavyWeaponExplosionChecks {
    private static final String GUN_DATA = "com/tacz/guns/resource/pojo/data/gun/GunData";
    private static final String BULLET = "com/tacz/guns/entity/EntityKineticBullet";
    private static final String EXPLOSION = "com/tacz/guns/util/block/ProjectileExplosion";
    private static final String MIXIN = "com/example/examplemod/gunbalance/mixin/";
    private static final List<String> IDS = List.of("cib:dprkrpg", "cib:dzj08", "cib:qlu11", "classicr:mgl_40mm",
            "rainforest:at4", "rainforest:m72", "suffuse:aiyasinrpg", "suffuse:m203", "suffuse:m79",
            "suffuse:pf98a", "suffuse:qlu11", "tacz:m320", "tacz:rpg7");
    private static int checks;

    private HeavyWeaponExplosionChecks() {}

    public static void main(String[] args) throws Exception {
        frozenIdentity(); separateTargets(); terrainChoice(); damageCurves();
        try (ZipFile installed = new ZipFile(installedJar().toFile())) {
            gsonNativeDataIdentity(installed); explosionAbi(installed); projectileAbi(installed);
        }
        registered();
        System.out.println("Heavy weapon explosion checks passed: " + checks);
    }

    private static void frozenIdentity() {
        for (String id : IDS) {
            ResourceLocation gun = ResourceLocation.parse(id);
            var frozen = HeavyWeaponExplosionPolicy.freeze(gun, id);
            require(frozen != null && frozen.gunId().equals(gun), "Approved resource identity " + id);
            require(HeavyWeaponExplosionPolicy.freeze(gun, null) == null, "Initial resource has native behavior " + id);
            require(HeavyWeaponExplosionPolicy.freeze(gun, "tacz:mismatched") == null, "Foreign marker stays native " + id);
            // A saved selection, a queued/failed reload, or a later resource object never changes this reference.
            require(HeavyWeaponExplosionPolicy.freeze(gun, null) == null && frozen == HeavyWeaponProfiles.forGun(gun),
                    "Earlier experimental projectile retains its loaded snapshot " + id);
            require(HeavyWeaponExplosionPolicy.freeze(gun, id) == frozen,
                    "Residual experimental data after a failed reload carries matching mechanics " + id);
        }
        require(HeavyWeaponExplosionPolicy.freeze(null, "tacz:rpg7") == null, "Absent projectile gun identity");
        require(HeavyWeaponExplosionPolicy.freeze(ResourceLocation.parse("custom:launcher"), "custom:launcher") == null,
                "Unknown marker uses exact native explosion");
    }

    private static void separateTargets() {
        for (String id : IDS) {
            var shot = HeavyWeaponProfiles.forGun(ResourceLocation.parse(id));
            require(shot != null, "Approved runtime profile exists " + id);
            require(shot.damageRadius() >= 8 && shot.damageRadius() <= 12, "Approved entity radius " + id);
            require(shot.terrainPower() >= 3 && shot.terrainPower() <= 6.5, "Independent grenade/rocket terrain power " + id);
            require(HeavyWeaponExplosionPolicy.terrainPower((float) shot.damageRadius(), shot) == (float) shot.terrainPower(),
                    "Terrain ray energy is independent of entity radius " + id);
            for (float nativeImpulse : new float[]{0, .01F, .3F, .6F, 1F, 1.2F, 1000F, Float.POSITIVE_INFINITY}) {
                float result = HeavyWeaponExplosionPolicy.clampCenterImpulse(nativeImpulse, shot);
                require(result == Math.min(nativeImpulse, (float) shot.maximumCenterKnockbackImpulse()), "Center coefficient cap " + id);
                for (double distanceFalloff : new double[]{0, .1, .5, 1}) for (double resistance : new double[]{0, .25, 1}) {
                    double applied = result * distanceFalloff * (1 - resistance);
                    require(applied >= 0 && applied <= shot.maximumCenterKnockbackImpulse() + 1e-6,
                            "Normal distance and resistance operate on bounded coefficient " + id);
                }
            }
            float nativeCoefficient = (float) (shot.explosionDamage() * 2 * shot.damageRadius() / 500);
            require(HeavyWeaponExplosionPolicy.clampCenterImpulse(nativeCoefficient, shot)
                    <= (float) shot.maximumCenterKnockbackImpulse(), "Native B times doubled R coefficient " + id);
        }
        for (float nativeValue : new float[]{0, 2, 3, 6, 10, 99, Float.POSITIVE_INFINITY}) {
            require(HeavyWeaponExplosionPolicy.terrainPower(nativeValue, null) == nativeValue,
                    "Every unmarked terrain explosion retains original value");
            require(HeavyWeaponExplosionPolicy.clampCenterImpulse(nativeValue, null) == nativeValue,
                    "Every unmarked knockback retains original value");
        }
    }

    private static void terrainChoice() {
        for (boolean global : new boolean[]{false, true}) {
            boolean transformedNativeState = global;
            require(HeavyWeaponTerrainPolicy.resolve(transformedNativeState, null, global) == global,
                    "Experimental authored destroy=true respects global gate");
            require(!HeavyWeaponTerrainPolicy.resolve(transformedNativeState, false, global), "Explicit per-gun off has final control");
            require(HeavyWeaponTerrainPolicy.resolve(transformedNativeState, true, global) == global,
                    "Explicit per-gun on keeps TaCZ gate");
        }
    }

    private static void damageCurves() {
        for (String id : IDS) {
            var profile = HeavyWeaponProfiles.forGun(ResourceLocation.parse(id));
            for (double p : new double[]{1, 1.35, 1.6, 2}) for (double q : new double[]{1, 1.4, 1.65, 2}) {
                var curve = new com.example.examplemod.zones.heavy.HeavyWeaponDamageCurve(17, p, q);
                var frozen = HeavyWeaponProfiles.withCurve(profile, curve);
                require(frozen.damageRadius() == 17 && frozen.curveP() == p && frozen.curveQ() == q, "Per-gun override freezes all curve fields");
                require(HeavyWeaponExplosionPolicy.damageRadius(8, frozen) == 17, "Custom radius enters native visibility query");
                double previous = 1;
                for (int step = 0; step <= 100; step++) {
                    double r = curve.damageRadius() * step / 100;
                    double expected = curve.multiplierAt(r);
                    double actual = HeavyWeaponExplosionPolicy.damageMultiplier(1 - r / curve.damageRadius(), frozen);
                    require(Math.abs(expected - actual) < 1e-12 && actual <= previous + 1e-12 && actual >= 0, "Exact monotonic truncated-power geometry");
                    previous = actual;
                }
                require(curve.multiplierAt(0) == 1 && curve.multiplierAt(17) == 0 && curve.multiplierAt(18) == 0, "Exact center and cutoff");
                require(HeavyWeaponExplosionPolicy.damageAmount(0, 40, frozen) == 0, "Fully obstructed native sample remains zero");
                require(HeavyWeaponExplosionPolicy.damageAmount(20, 40, null) == 20, "Original balance preserves native damage argument");
                require(HeavyWeaponExplosionPolicy.damageAmount(40, 40, frozen) == 40, "Native Dmax retained before armor and damage events");
            }
        }
        var defaults = com.example.examplemod.zones.ZoneSettings.defaults();
        var custom = new com.example.examplemod.zones.ZoneSettings(defaults.bombing(), false, defaults.visualStyle(), java.util.Map.of(),
                java.util.Map.of("tacz:m320", new com.example.examplemod.zones.heavy.HeavyWeaponDamageCurve(12, 1.35, 1.65)));
        String json = new Gson().toJson(custom);
        require(com.example.examplemod.zones.ZoneSettings.parse(com.google.gson.JsonParser.parseString(json)).equals(custom), "Per-gun curve persists through strict authoring codec");
    }

    private static void gsonNativeDataIdentity(ZipFile installed) throws Exception {
        ClassNode data = nativeClass(installed, GUN_DATA);
        ClassNode mixin = compiledClass(MIXIN + "GunDataHeavyProfileMixin");
        require(mixin.interfaces.contains(HeavyWeaponGunData.class.getName().replace('.', '/')), "Gun data implements its marker interface");
        FieldNode field = mixin.fields.stream().filter(candidate -> candidate.desc.equals("Ljava/lang/String;")).findFirst().orElseThrow();
        require((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC)) == 0,
                "Gson reads the per-instance marker field");
        AnnotationNode name = annotations(field.visibleAnnotations, field.invisibleAnnotations).stream()
                .filter(annotation -> annotation.desc.equals("Lcom/google/gson/annotations/SerializedName;")).findFirst().orElseThrow();
        require("tacz_pubg_heavy_profile".equals(value(name, "value")), "Stable JSON identity survives unique-field renaming");
        require(data.fields.stream().noneMatch(existing -> existing.name.equals(field.name)), "Native gun data has no field collision");
        MethodNode getter = mixin.methods.stream().filter(method -> method.name.equals("taczPubg$heavyProfileId")).findFirst().orElseThrow();
        for (var instruction : getter.instructions) if (instruction instanceof FieldInsnNode access) access.owner = GUN_DATA;
        data.interfaces.add(HeavyWeaponGunData.class.getName().replace('.', '/'));
        data.fields.add(field); data.methods.add(getter);
        ClassWriter writer = new ClassWriter(0); data.accept(writer);
        byte[] transformed = writer.toByteArray();
        Class<?> type = new ClassLoader(HeavyWeaponExplosionChecks.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(GUN_DATA.replace('/', '.'))) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = defineClass(name, transformed, 0, transformed.length);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }.loadClass(GUN_DATA.replace('/', '.'));
        Gson gson = new Gson();
        for (String id : IDS) {
            Object loaded = gson.fromJson("{\"tacz_pubg_heavy_profile\":\"" + id + "\",\"bullet\":{\"damage\":10}}", type);
            require(loaded instanceof HeavyWeaponGunData tagged && id.equals(tagged.taczPubg$heavyProfileId()),
                    "Actual installed GunData Gson decodes marker " + id);
            require(id.equals(((HeavyWeaponGunData) gson.fromJson(gson.toJson(loaded), type)).taczPubg$heavyProfileId()),
                    "Native Gson round trip preserves launch identity " + id);
        }
        require(((HeavyWeaponGunData) gson.fromJson("{\"bullet\":{\"damage\":10}}", type)).taczPubg$heavyProfileId() == null,
                "Original data decodes with exact native identity");
    }

    private static void explosionAbi(ZipFile installed) throws Exception {
        ClassNode nativeExplosion = nativeClass(installed, EXPLOSION);
        require(nativeExplosion.fields.stream().anyMatch(field -> field.name.equals("exploder")
                && field.desc.equals("Lnet/minecraft/world/entity/Entity;") && (field.access & Opcodes.ACC_FINAL) != 0),
                "Native explosion keeps originating projectile");
        MethodNode explode = nativeExplosion.methods.stream().filter(method -> method.name.equals("explode") && method.desc.equals("()V"))
                .findFirst().orElseThrow();
        List<FieldInsnNode> radii = new ArrayList<>();
        List<VarInsnNode> coefficientStores = new ArrayList<>();
        int damageCalls = 0, eventCalls = 0;
        for (var instruction : explode.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(EXPLOSION) && field.name.equals("radius") && field.desc.equals("F")) radii.add(field);
            if (instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.FSTORE && variable.var == 31)
                coefficientStores.add(variable);
            if (instruction instanceof MethodInsnNode call && call.name.equals("hurt")) damageCalls++;
            if (instruction instanceof MethodInsnNode call && call.name.equals("onExplosionDetonate")) eventCalls++;
        }
        require(radii.size() == 2, "Exactly two radius reads separate terrain energy and entity radius");
        require(coefficientStores.size() == 1, "Native center multiplier is the unique float store in slot 31");
        AbstractInsnNode division = previousCode(coefficientStores.getFirst());
        require(division != null && division.getOpcode() == Opcodes.FDIV, "Center coefficient is stored after native division");
        require(damageCalls == 1 && eventCalls == 1, "One native hurt site and one detonation event site");
        ClassNode mixin = compiledClass(MIXIN + "ProjectileExplosionHeavyBalanceMixin");
        MethodNode terrain = mixin.methods.stream().filter(method -> method.name.equals("taczPubg$independentHeavyTerrainPower")).findFirst().orElseThrow();
        AnnotationNode redirect = annotation(terrain, "/Redirect;");
        AnnotationNode at = at(redirect);
        require("FIELD".equals(value(at, "value")) && Integer.valueOf(0).equals(value(at, "ordinal"))
                && ("L" + EXPLOSION + ";radius:F").equals(value(at, "target")), "Only first terrain radius read is redirected");
        MethodNode curve = mixin.methods.stream().filter(method -> method.name.equals("taczPubg$truncatedPowerDamage")).findFirst().orElseThrow();
        AnnotationNode argument = annotation(curve, "/ModifyArg;");
        require(Integer.valueOf(1).equals(value(argument, "index")) && "INVOKE".equals(value(at(argument), "value"))
                && ("Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z").equals(value(at(argument), "target")),
                "Curve changes only native hurt amount; native knockback falloff and events remain intact");
        MethodNode impulse = mixin.methods.stream().filter(method -> method.name.equals("taczPubg$capHeavyCenterImpulse")).findFirst().orElseThrow();
        AnnotationNode modify = annotation(impulse, "/ModifyVariable;");
        require(Integer.valueOf(31).equals(value(modify, "index")) && "STORE".equals(value(at(modify), "value")),
                "Center impulse changes the exact native coefficient before falloff and resistance multiplication");
        for (MethodNode method : mixin.methods) if (!method.name.equals("<init>")) for (var instruction : method.instructions)
            require(!(instruction instanceof MethodInsnNode call) || (!call.name.equals("hurt") && !call.name.equals("explode")
                    && !call.name.equals("onExplosionDetonate")), "Mixin preserves single native damage and event execution");
    }

    private static void projectileAbi(ZipFile installed) throws Exception {
        ClassNode bullet = nativeClass(installed, BULLET);
        for (String name : List.of("writeSpawnData", "readSpawnData")) {
            MethodNode spawn = bullet.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
            for (String property : List.of("explosionRadius", "explosionDamage")) require(java.util.stream.StreamSupport.stream(
                    spawn.instructions.spliterator(), false).anyMatch(instruction -> instruction instanceof FieldInsnNode field
                    && field.name.equals(property) && field.desc.equals("F")), "Native spawn sends/receives " + property + " via " + name);
        }
        ClassNode launch = compiledClass(MIXIN + "EntityKineticBulletHeavyTerrainMixin");
        require(launch.interfaces.contains(HeavyWeaponProjectile.class.getName().replace('.', '/')), "Projectile holds server-only frozen profile");
        MethodNode freeze = launch.methods.stream().filter(method -> method.name.equals("taczPubg$freezeApprovedHeavyProfile")).findFirst().orElseThrow();
        require(java.util.stream.StreamSupport.stream(freeze.instructions.spliterator(), false).anyMatch(instruction ->
                instruction instanceof MethodInsnNode call && call.name.equals("taczPubg$heavyProfileId")), "Constructor uses actual parsed GunData marker");
        for (var instruction : freeze.instructions) if (instruction instanceof MethodInsnNode call)
            require(!call.owner.endsWith("GunBalanceService") && !call.owner.endsWith("LootConfigStore"),
                    "In-flight identity follows loaded data through pending and failed resource changes");
    }

    private static void registered() throws Exception {
        try (InputStream stream = HeavyWeaponExplosionChecks.class.getResourceAsStream("/tacz_pubg.gunbalance.mixins.json")) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            require(json.contains("GunDataHeavyProfileMixin") && json.contains("ProjectileExplosionHeavyBalanceMixin"),
                    "Both data identity and isolated explosion hooks are registered");
        }
    }

    private static ClassNode nativeClass(ZipFile installed, String name) throws Exception {
        try (InputStream stream = installed.getInputStream(installed.getEntry(name + ".class"))) {
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
    private static ClassNode compiledClass(String name) throws Exception {
        try (InputStream stream = HeavyWeaponExplosionChecks.class.getResourceAsStream("/" + name + ".class")) {
            require(stream != null, "Compiled runtime class " + name);
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
    private static Path installedJar() throws Exception {
        try (var files = Files.list(Path.of("libs"))) {
            return files.filter(path -> path.getFileName().toString().startsWith("tacz-neoforge-")
                    && path.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
        }
    }
    private static AnnotationNode annotation(MethodNode method, String suffix) {
        return annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
                .filter(annotation -> annotation.desc.endsWith(suffix)).findFirst().orElseThrow();
    }
    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> result = new ArrayList<>();
        if (visible != null) result.addAll(visible); if (invisible != null) result.addAll(invisible); return result;
    }
    private static AnnotationNode at(AnnotationNode annotation) {
        Object value = value(annotation, "at"); return value instanceof List<?> list ? (AnnotationNode) list.getFirst() : (AnnotationNode) value;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; annotation.values != null && index < annotation.values.size(); index += 2)
            if (annotation.values.get(index).equals(key)) return annotation.values.get(index + 1);
        return null;
    }
    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
