package com.example.examplemod.zones.heavy;

import com.google.gson.Gson;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

/** Native metadata, per-shot terrain inheritance and installed TaCZ constructor ABI. */
public final class HeavyWeaponChecks {
    private static int checks;
    private static final String BULLET = "com/tacz/guns/entity/EntityKineticBullet";
    private static final String CONSTRUCTOR = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;ZLcom/tacz/guns/resource/pojo/data/gun/GunData;Lcom/tacz/guns/resource/pojo/data/gun/BulletData;)V";
    private HeavyWeaponChecks() {}

    public static void main(String[] args) throws Exception {
        classification(); metadata(); terrain(); installedApi();
        System.out.println("Heavy weapon checks passed: " + checks);
    }

    private static void classification() {
        for (String type : List.of("rpg", "rocket", "rocket_launcher", "grenade", "grenade_launcher", "grenadelauncher", "gl", "heavy", "heavy_weapon", "launcher", "RPG"))
            require(HeavyWeaponCatalog.isHeavyType(type), "Heavy native type " + type);
        for (String type : List.of("rifle", "pistol", "smg", "mg", "sniper", "sg", "", "unknown"))
            require(!HeavyWeaponCatalog.isHeavyType(type), "Ordinary weapon type " + type);
        require(!HeavyWeaponCatalog.isHeavyType(null), "Absent type");
        for (String ammo : List.of("custom:40mm", "custom:35mm", "custom:35x32mm", "future:he_grenade"))
            require(HeavyWeaponCatalog.isGrenadeType("rpg", "future:launcher", ammo), "Future grenade ammo classification " + ammo);
        require(HeavyWeaponCatalog.isGrenadeType("grenadelauncher", "future:unknown", "future:round"), "Explicit future grenade type");
        for (String ammo : List.of("tacz:rpg_rocket", "cib:80", "suffuse:120mm"))
            require(!HeavyWeaponCatalog.isGrenadeType("rpg", "future:rocket", ammo), "Rocket class retains independent terrain " + ammo);
    }

    private static void metadata() {
        BulletData rocket = new Gson().fromJson("""
                {"damage":15,"extra_damage":{"damage_adjust":[{"distance":5,"damage":20}]},
                 "explosion":{"explode":true,"damage":120,"radius":3,"destroy_block":true}}
                """, BulletData.class);
        HeavyWeaponInfo info = HeavyWeaponCatalog.fromNative("tacz:rpg_rocket", rocket);
        require(info.ammoId().equals("tacz:rpg_rocket"), "Native ammo ID");
        require(info.directDamage() == 20, "Native close-distance adjustment takes precedence over bullet.damage");
        require(info.explosionDamage() == 120 && info.damageRadius() == 3 && info.nativeDestroyBlocks(), "Native explosion fields");
        BulletData grenade = new Gson().fromJson("""
                {"damage":10,"explosion":{"explode":true,"damage":50,"radius":6,"destroy_block":false}}
                """, BulletData.class);
        info = HeavyWeaponCatalog.fromNative("tacz:40mm", grenade);
        require(info.directDamage() == 10 && info.explosionDamage() == 50 && info.damageRadius() == 6
                && !info.nativeDestroyBlocks(), "Native grenade values remain individually visible");
        BulletData nonexplosive = new Gson().fromJson("{" + "\"damage\":7" + "}", BulletData.class);
        info = HeavyWeaponCatalog.fromNative("custom:round", nonexplosive);
        require(info.directDamage() == 7 && info.explosionDamage() == 0 && info.damageRadius() == 0
                && !info.nativeDestroyBlocks(), "Heavy assets without enabled explosion retain zero explosion fields");
        require(rocket.getDamageAmount() == 15 && rocket.getExplosionData().getDamage() == 120,
                "Reading metadata preserves original gun data");
    }

    private static void terrain() {
        for (boolean nativeValue : new boolean[]{false, true}) for (boolean global : new boolean[]{false, true}) {
            require(HeavyWeaponTerrainPolicy.resolve(nativeValue, null, global) == nativeValue, "Absent override inherits exact native shot state");
            require(!HeavyWeaponTerrainPolicy.resolve(nativeValue, false, global), "Per-gun disabled state keeps terrain intact");
            require(HeavyWeaponTerrainPolicy.resolve(nativeValue, true, global) == global, "Per-gun enabled state follows server-wide permission");
        }
        boolean firstShot = HeavyWeaponTerrainPolicy.resolve(true, false, true);
        boolean secondShot = HeavyWeaponTerrainPolicy.resolve(false, true, true);
        require(!firstShot && secondShot, "Each fired shot keeps the decision made for that shot");
    }

    private static void installedApi() throws Exception {
        Path root = Files.isDirectory(Path.of("neoforge-1.21.1/run/mods")) ? Path.of("") : Path.of("..");
        Path jar;
        try (var jars = Files.list(root.resolve("neoforge-1.21.1/run/mods"))) {
            jar = jars.filter(path -> path.getFileName().toString().startsWith("tacz-neoforge-")
                    && path.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        ClassNode target = new ClassNode();
        try (ZipFile zip = new ZipFile(jar.toFile()); InputStream stream = zip.getInputStream(zip.getEntry(BULLET + ".class"))) {
            new ClassReader(stream).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        require(target.methods.stream().anyMatch(method -> (method.name + method.desc).equals(CONSTRUCTOR)), "Installed full native constructor");
        require(target.fields.stream().anyMatch(field -> field.name.equals("explosionDestroyBlock") && field.desc.equals("Z")), "Native per-instance terrain flag");
        ClassNode mixin = new ClassNode();
        try (InputStream stream = HeavyWeaponChecks.class.getResourceAsStream("/com/example/examplemod/gunbalance/mixin/EntityKineticBulletHeavyTerrainMixin.class")) {
            require(stream != null, "Heavy terrain mixin is compiled");
            new ClassReader(stream).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var handler = mixin.methods.stream().filter(method -> method.name.equals("taczPubg$freezePlayerHeavyTerrain")).findFirst().orElseThrow();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
        if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
        AnnotationNode inject = annotations.stream().filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        require(List.of(CONSTRUCTOR).equals(value(inject, "method")), "Only the native player launch constructor is targeted");
        Object at = value(inject, "at");
        AnnotationNode point = at instanceof List<?> list ? (AnnotationNode) list.getFirst() : (AnnotationNode) at;
        require("TAIL".equals(value(point, "value")), "Terrain decision follows native cache/script evaluation");
        int writes = 0;
        for (var instruction : handler.instructions) if (instruction instanceof FieldInsnNode field
                && (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)) {
            writes++;
            require((field.name.equals("explosionDestroyBlock") || field.name.startsWith("taczPubg$")) && field.getOpcode() == Opcodes.PUTFIELD,
                    "Only native terrain flag and unique frozen per-shot metadata change");
        }
        require(writes == 3, "One terrain flag, one grenade flag and one immutable profile assignment");
        try (InputStream stream = HeavyWeaponChecks.class.getResourceAsStream("/tacz_pubg.gunbalance.mixins.json")) {
            require(stream != null && new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("EntityKineticBulletHeavyTerrainMixin"), "Heavy terrain mixin is registered");
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; annotation.values != null && index < annotation.values.size(); index += 2)
            if (annotation.values.get(index).equals(key)) return annotation.values.get(index + 1);
        return null;
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
