package com.example.examplemod.zones.bombing;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.ZoneSettings;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Deterministic scheduler/geometry/damage/lifecycle checks and the installed TaCZ injection ABI. */
public final class BombingZoneChecks {
    private static int checks;
    private static final String BULLET = "com/tacz/guns/entity/EntityKineticBullet";
    private BombingZoneChecks() {}

    public static void main(String[] args) throws Exception {
        rates(); geometry(); damage(); lifecycle(); falling(); catalogClassification(); installedApi();
        System.out.println("Bombing zone checks passed: " + checks);
    }

    private static void rates() {
        for (int rate = 0; rate <= MapZoneModel.MAX_MISSILES_PER_MINUTE; rate++) {
            BombingPolicy.Rate schedule = new BombingPolicy.Rate(0);
            int total = 0;
            for (int tick = 0; tick < BombingPolicy.TICKS_PER_MINUTE; tick++) total += schedule.advance(rate);
            require(total == rate, "Exact per-minute rate " + rate);
        }
        BombingPolicy.Rate schedule = new BombingPolicy.Rate(0);
        for (int tick = 0; tick < 19; tick++) require(schedule.advance(60) == 0, "60/min waits until tick 20");
        require(schedule.advance(60) == 1, "60/min fires on tick 20");
        for (int tick = 0; tick < 15; tick++) schedule.advance(60);
        schedule.reset();
        for (int tick = 0; tick < 19; tick++) require(schedule.advance(60) == 0, "Pause discards fractional backlog");
        require(schedule.advance(60) == 1, "Resume uses a fresh rate window");
        schedule = new BombingPolicy.Rate(0);
        int dropped = schedule.advance(6_000);
        require(dropped == 5, "Maximum authored rate yields five opportunities per tick");
        require(schedule.advance(0) == 0, "Skipped opportunities are not queued");
    }

    private static void geometry() {
        var centered = map(-100, -100, 100, 100, new MapZoneModel.Circle(0, 0, 20));
        Random random = new Random(148_729);
        double meanX = 0, meanZ = 0, meanRadiusSquared = 0;
        for (int index = 0; index < 10_000; index++) {
            var point = BombingPolicy.sample(centered, centered.safeZone(), random);
            require(point != null && centered.contains(point.x(), point.z())
                    && centered.safeZone().contains(point.x(), point.z()), "Sample remains in exact circle and map");
            meanX += point.x(); meanZ += point.z(); meanRadiusSquared += point.x() * point.x() + point.z() * point.z();
        }
        require(Math.abs(meanX / 10_000) < .5 && Math.abs(meanZ / 10_000) < .5, "Circle sampling has balanced center");
        require(Math.abs(meanRadiusSquared / 10_000 - 200) < 5, "Uniform area rather than uniform radius");
        var corner = map(0, 0, 30, 30, new MapZoneModel.Circle(0, 0, 40));
        for (int index = 0; index < 1_000; index++) {
            var point = BombingPolicy.sample(corner, corner.safeZone(), random);
            require(point != null && corner.contains(point.x(), point.z()) && corner.safeZone().contains(point.x(), point.z()),
                    "Circle clipped to map corner");
        }
        require(BombingPolicy.sample(centered, new MapZoneModel.Circle(300, 300, 10), random) == null,
                "Disjoint zone has no strike target");
    }

    private static void damage() {
        for (double edge : new double[]{0, .25, .6, 1}) {
            var properties = new ZoneSettings.BombingProperties("tacz:rpg_rocket", 100, 10, edge, false);
            for (int step = 0; step <= 100; step++) {
                double distance = step / 10.0;
                require(close(BombingPolicy.damage(100, 10, edge, distance, 1), properties.damageAt(distance)),
                        "Runtime follows configured falloff at " + distance);
                require(close(BombingPolicy.damage(100, 10, edge, distance, .4), properties.damageAt(distance) * .4),
                        "Native cover exposure scales configured HP");
            }
            require(close(BombingPolicy.damage(100, 10, edge, 0, 1), 100), "Center damage");
            require(close(BombingPolicy.damage(100, 10, edge, 10, 1), 100 * edge), "Boundary retention");
            require(BombingPolicy.damage(100, 10, edge, Math.nextUp(10.0), 1) == 0, "Outside radius has zero damage");
        }
        require(BombingPolicy.damage(100, 10, 0, 1, 0) == 0, "Solid cover has zero exposure damage");
        require(BombingPolicy.damage(100, 10, 0, Double.NaN, 1) == 0, "Invalid distance is rejected");
        require(BombingPolicy.damage(100, 10, 0, 0, 2) == 100, "Exposure is bounded");
    }

    private static void lifecycle() {
        for (String stage : List.of("waiting", "shrinking", "final")) {
            require(BombingPolicy.active(false, false, stage), "Live stage " + stage);
            require(!BombingPolicy.active(true, false, stage), "Finished session is inert");
            require(!BombingPolicy.active(false, true, stage), "Paused session is inert");
        }
        for (String stage : List.of("preparing", "paused", "stopped", "finished", "", "unknown"))
            require(!BombingPolicy.active(false, false, stage), "Only explicit battle stages are live");
        require(!BombingPolicy.expired(100, 100 + BombingPolicy.MAX_LIFE_TICKS - 1, 0), "Loaded but non-ticking projectile keeps bounded remaining lifetime");
        require(BombingPolicy.expired(100, 100 + BombingPolicy.MAX_LIFE_TICKS, 0), "Wall-clock tick cap removes stalled projectiles");
        require(BombingPolicy.expired(100, 101, BombingPolicy.MAX_LIFE_TICKS), "Native entity age cap");
        require(BombingPolicy.expired(100, 99, 0), "Clock anomaly retires projectile");
        require(BombingPolicy.MAX_PER_SESSION_TICK <= BombingPolicy.MAX_PER_TICK
                && BombingPolicy.MAX_IN_FLIGHT_SESSION <= BombingPolicy.MAX_IN_FLIGHT_TOTAL, "Per-session limits fit server limits");
    }

    private static void falling() {
        require(BombingPolicy.ceiling(Integer.MAX_VALUE, -64, 320) == 319, "Default uses highest buildable Y");
        require(BombingPolicy.ceiling(500, -64, 320) == 319, "Authored height clamps to current world ceiling");
        require(BombingPolicy.ceiling(100, -64, 320) == 100, "Custom authored ceiling");
        require(BombingPolicy.highestAir(319, -64, y -> true) == 319, "Highest air is the selected ceiling when empty");
        require(BombingPolicy.highestAir(319, -64, y -> y < 317) == 316, "Finds highest genuine air below a solid ceiling");
        require(BombingPolicy.highestAir(-65, -64, y -> true) == BombingPolicy.NO_CONTACT, "Below-world ceiling has no spawn candidate");
        require(BombingPolicy.highestAir(10, -64, y -> false) == BombingPolicy.NO_CONTACT, "Fully solid column has no air spawn");
        require(BombingPolicy.verticalVelocity(1) == 0, "Spawn starts with zero velocity");
        require(close(BombingPolicy.verticalVelocity(2), -.06), "First gravity step");
        require(close(BombingPolicy.verticalVelocity(101), -6), "Gravity has zero drag");
        require(BombingPolicy.firstContactY(100.5, 94.5, -64, 320, y -> y != 98 && y != 96) == 98,
                "Continuous sweep finds the first crossed block, including thin vegetation or liquid voxels");
        require(BombingPolicy.firstContactY(100.5, 94.5, -64, 320, y -> y != 96) == 96,
                "Listed pass-through block is skipped before the next solid contact");
        require(BombingPolicy.firstContactY(100.5, 100, -64, 320, y -> y != 99) == 99,
                "Exact contact with a voxel top surface detonates on that step");
        require(BombingPolicy.firstContactY(-2.5, -3, -64, 320, y -> y != -4) == -4,
                "Negative-Y top-face boundary");
        require(BombingPolicy.firstContactY(100.5, 100.5, -64, 320, y -> true) == BombingPolicy.NO_CONTACT,
                "Initial stationary air step remains airborne");
        require(BombingPolicy.firstContactY(100.5, 90.5, -64, 320, y -> true) == BombingPolicy.NO_CONTACT,
                "All-air or explicitly pass-through sweep");
        double position = 2031.5;
        int contactAge = 0;
        for (int age = 1; age < BombingPolicy.MAX_LIFE_TICKS; age++) {
            double next = position + BombingPolicy.verticalVelocity(age);
            if (BombingPolicy.firstContactY(position, next, -2032, 2032, y -> y != -2032) == -2032) {
                contactAge = age; break;
            }
            position = next;
        }
        require(contactAge > 0 && contactAge < BombingPolicy.MAX_LIFE_TICKS,
                "Lifetime covers a full maximum-height world from zero initial speed");
    }

    private static void catalogClassification() {
        for (String[] rocket : new String[][]{{"rpg", "tacz:rpg7", "tacz:rpg_rocket"},
                {"rpg", "cib:dzj08", "cib:80"}, {"rpg", "suffuse:pf98a", "suffuse:120mm"},
                {"rocket_launcher", "custom:launcher", "custom:rocket"}})
            require(BombingAmmoCatalog.isRocketLauncher(rocket[0], rocket[1], rocket[2]), "Actual rocket launcher metadata");
        for (String[] grenade : new String[][]{{"rpg", "tacz:m320", "tacz:40mm"},
                {"rpg", "cib:qlu11", "cib:35mm"}, {"rpg", "suffuse:qlu11", "suffuse:35x32mm"},
                {"rpg", "custom:m203", "custom:other"}, {"rifle", "custom:rifle", "custom:rocket"},
                {"rpg", "custom:launcher", "custom:grenade_round"}})
            require(!BombingAmmoCatalog.isRocketLauncher(grenade[0], grenade[1], grenade[2]), "Grenade/ordinary gun exclusion");
    }

    private static void installedApi() throws Exception {
        Path root = Files.isDirectory(Path.of("neoforge-1.21.1/run/mods")) ? Path.of("") : Path.of("..");
        Path jar;
        try (var jars = Files.list(root.resolve("neoforge-1.21.1/run/mods"))) {
            jar = jars.filter(path -> path.getFileName().toString().startsWith("tacz-neoforge-")
                    && path.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        ClassNode nativeBullet = new ClassNode();
        ClassNode renderer = new ClassNode();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            try (InputStream stream = zip.getInputStream(zip.getEntry(BULLET + ".class"))) {
                new ClassReader(stream).accept(nativeBullet, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            try (InputStream stream = zip.getInputStream(zip.getEntry("com/tacz/guns/client/renderer/entity/EntityBulletRenderer.class"))) {
                new ClassReader(stream).accept(renderer, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        Map<String, String> shadows = Map.ofEntries(Map.entry("ammoId", "Lnet/minecraft/resources/ResourceLocation;"),
                Map.entry("gunId", "Lnet/minecraft/resources/ResourceLocation;"), Map.entry("life", "I"),
                Map.entry("gravity", "F"), Map.entry("friction", "F"), Map.entry("startPos", "Lnet/minecraft/world/phys/Vec3;"),
                Map.entry("explosion", "Z"), Map.entry("explosionDamage", "F"), Map.entry("explosionRadius", "F"),
                Map.entry("explosionDelayCount", "I"), Map.entry("pierce", "I"));
        shadows.forEach((name, desc) -> require(nativeBullet.fields.stream().anyMatch(field -> field.name.equals(name)
                && field.desc.equals(desc)), "Installed TaCZ shadow field " + name));
        Set<String> targets = Set.of("tick()V", "onBulletTick()V", "onHitEntity(Lcom/tacz/guns/util/TacHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V",
                "onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V");
        for (String target : targets) require(nativeBullet.methods.stream().anyMatch(method -> (method.name + method.desc).equals(target)),
                "Installed TaCZ injection target " + target);
        require(nativeBullet.methods.stream().anyMatch(method -> method.name.equals("<init>")
                && method.desc.equals("(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V")), "Ownerless constructor ABI");
        boolean displayGate = false;
        for (var method : renderer.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("getGunDisplay")) displayGate = true;
        require(displayGate, "Installed renderer requires the corresponding real gun display");
        try (InputStream stream = BombingZoneChecks.class.getResourceAsStream("/tacz_pubg.gunbalance.mixins.json")) {
            require(stream != null && new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("EntityKineticBulletBombingMixin"), "Bombing mixin is registered");
        }
    }

    private static MapZoneModel.MapDefinition map(double minX, double minZ, double maxX, double maxZ, MapZoneModel.Circle circle) {
        return new MapZoneModel.MapDefinition(1, "Bombing fixture", ResourceLocation.parse("minecraft:overworld"),
                minX, minZ, maxX, maxZ, circle, 1, List.of());
    }
    private static boolean close(double a, double b) { return Math.abs(a - b) < 1e-9; }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
