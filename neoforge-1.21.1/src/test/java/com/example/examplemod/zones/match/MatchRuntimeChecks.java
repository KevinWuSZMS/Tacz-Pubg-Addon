package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Match activation, pulse arbitration, immutable snapshots and geometry, entirely in memory. */
public final class MatchRuntimeChecks {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static int checks;

    private MatchRuntimeChecks() {}

    public static void main(String[] args) throws Exception {
        blueZoneSoundAbi();
        explicitLifecycle();
        boundariesAndEligibility();
        sharedPulseAndOverlaps();
        snapshotOwnership();
        nextCircleAnnouncement();
        randomizedIndependentOracle();
        System.out.println("Match runtime checks passed: " + checks);
    }

    /** Verify both real sound paths without constructing entities or bootstrapping Minecraft. */
    private static void blueZoneSoundAbi() throws Exception {
        String living = "net/minecraft/world/entity/LivingEntity";
        String source = "Lnet/minecraft/world/damagesource/DamageSource;";
        String sound = "Lnet/minecraft/sounds/SoundEvent;";
        ClassNode nativeEntity = bytecode(living);
        for (String entry : List.of("playHurtSound", "handleDamageEvent")) {
            var method = nativeEntity.methods.stream().filter(m -> m.name.equals(entry)
                    && m.desc.equals("(" + source + ")V")).findFirst().orElseThrow();
            long calls = java.util.Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                    && call.owner.equals(living) && call.name.equals("getHurtSound")
                    && call.desc.equals("(" + source + ")" + sound)).count();
            check(calls == 1, "real server/client sound entry contains exactly the redirected native getter: " + entry);
        }
        ClassNode mixin = bytecode("com/example/examplemod/vitals/mixin/BlueZoneHurtSoundMixin");
        var handler = mixin.methods.stream().filter(m -> m.name.equals("taczPubg$blueZoneHurtSound")).findFirst().orElseThrow();
        AnnotationNode redirect = handler.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")).findFirst().orElseThrow();
        check(annotationValue(redirect, "method").equals(List.of("playHurtSound", "handleDamageEvent")),
                "one common mixin covers both authoritative and packet-driven hurt sound entries");
        var at = (AnnotationNode) annotationValue(redirect, "at");
        check(annotationValue(at, "target").equals("L" + living + ";getHurtSound(" + source + ")" + sound),
                "the redirect descriptor matches the actual native getter calls");
        var instructions = java.util.Arrays.asList(handler.instructions.toArray());
        check(instructions.stream().anyMatch(i -> i instanceof FieldInsnNode field
                        && field.owner.equals("com/example/examplemod/zones/match/MatchService") && field.name.equals("BLUE_ZONE"))
                && instructions.stream().anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("getHurtSound")),
                "blue-zone filtering preserves the normal native sound lookup for other damage sources");
        try (var input = MatchRuntimeChecks.class.getResourceAsStream("/tacz_pubg.vitals.mixins.json")) {
            check(input != null && new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("\"BlueZoneHurtSoundMixin\""), "the common sound mixin is registered in shipped resources");
        }
        for (var entry : java.util.Map.of("com/example/examplemod/zones/match/MatchService", "onServerTick",
                "com/example/examplemod/throwables/PubgThrowableEntity", "tickField").entrySet()) {
            var method = bytecode(entry.getKey()).methods.stream().filter(m -> m.name.equals(entry.getValue())).findFirst().orElseThrow();
            var code = java.util.Arrays.asList(method.instructions.toArray());
            check(code.stream().anyMatch(i -> i instanceof FieldInsnNode field && field.name.equals("BLUE_ZONE"))
                    && code.stream().anyMatch(i -> i instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/world/damagesource/DamageSource") && call.name.equals("<init>")),
                    "ordinary circle and blue-zone grenade damage share the filtered damage type: " + entry.getKey());
        }
    }
    private static Object annotationValue(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        throw new AssertionError("Missing mixin annotation value: " + key);
    }
    private static ClassNode bytecode(String owner) throws Exception {
        try (var input = MatchRuntimeChecks.class.getResourceAsStream("/" + owner + ".class")) {
            if (input == null) throw new AssertionError("Missing bytecode: " + owner);
            var result = new ClassNode(); new ClassReader(input).accept(result, ClassReader.SKIP_DEBUG); return result;
        }
    }

    private static void nextCircleAnnouncement() {
        Circle current = new Circle(0, 0, 100), next = new Circle(10, 10, 50);
        var session = java.util.UUID.randomUUID();
        for (String stage : List.of("waiting", "shrinking", "paused")) {
            var status = new MatchStatus(1, session, stage, 1, 3, 20, 2, 2, 2, "", current, next);
            check(MatchCirclePreview.target(status, 1).equals(next), "next circle announced during " + stage);
            check(MatchCirclePreview.target(status, 2) == null, "another map never inherits the target circle");
        }
        for (String stage : List.of("preparing", "final", "finished", "stopped")) {
            var status = new MatchStatus(1, session, stage, 1, 3, 20, 2, 2, 2, "", current, next);
            check(MatchCirclePreview.target(status, 1) == null, "no next-circle preview during " + stage);
        }
        check(MatchCirclePreview.target(new MatchStatus(1, session, "waiting", 0, 3, 20, 2, 2, 2, "", current, next), 1) == null,
                "preparation has no next-circle announcement even if a target is present");
        check(MatchCirclePreview.target(new MatchStatus(1, session, "shrinking", 1, 3, 0, 2, 2, 2, "", next, next), 1) == null,
                "completed shrink does not leave a duplicate dashed circle");
    }

    private static void explicitLifecycle() {
        MatchRuntimeState state = new MatchRuntimeState();
        check(state.revision() == 0 && state.sessions().isEmpty(), "new world/session has no running match");
        check(state.damageAt(OVERWORLD, 8, 0, true) == 0, "authoring alone never causes damage");
        rejects(() -> state.start(map(0, 1, OVERWORLD)), "unsaved map cannot run");
        rejects(() -> state.start(new MapDefinition(1, "No safety", OVERWORLD, -10, -10, 10, 10, null, 1, List.of())),
                "match cannot start without an explicit safe circle");
        rejects(() -> state.start(new MapDefinition(1, "Invalid", OVERWORLD, -10, -10, 10, 10,
                new Circle(0, 0, -1), 1, List.of())), "invalid authoring data cannot start");
        check(state.revision() == 0 && state.sessions().isEmpty(), "invalid starts do not change state");

        MatchSession first = state.start(map(1, 1, OVERWORLD));
        check(first.startedTick() == 0 && state.revision() == 1 && state.session(1).equals(first), "start commits one session and revision");
        check(state.damageAt(OVERWORLD, 8, 0, true) == 1, "explicit start activates only saved map geometry");
        rejects(() -> state.start(map(1, 9, OVERWORLD)), "second start cannot silently replace a running snapshot");
        check(state.revision() == 1 && state.session(1).map().blueDamagePerSecond() == 1, "rejected restart preserves actual damage");
        rejects(() -> state.end(2), "ending a missing match explains the issue");
        check(state.revision() == 1, "failed end does not alter revision");
        check(state.end(1).sessionId().equals(first.sessionId()), "end returns the session actually ended");
        check(state.revision() == 2 && state.damageAt(OVERWORLD, 8, 0, true) == 0, "end immediately stops future damage");
        for (int i = 0; i < 40; i++) check(!state.advanceTick(), "stopped sessions never emit damage pulses");
        MatchSession second = state.start(map(1, 3, OVERWORLD));
        check(!second.sessionId().equals(first.sessionId()) && second.startedTick() == 40, "new start receives a fresh UUID and start tick");
        check(state.revision() == 3 && state.damageAt(OVERWORLD, 8, 0, true) == 3, "restart uses newly saved values");
        MatchRuntimeState reopened = new MatchRuntimeState();
        check(reopened.sessions().isEmpty() && reopened.damageAt(OVERWORLD, 8, 0, true) == 0,
                "runtime state is deliberately discarded rather than resuming on server restart");
    }

    private static void boundariesAndEligibility() {
        MatchRuntimeState state = new MatchRuntimeState();
        state.start(map(1, 1.5, OVERWORLD));
        check(state.damageAt(OVERWORLD, 0, 0, true) == 0, "safe-circle center is safe");
        check(state.damageAt(OVERWORLD, 3, 4, true) == 0, "exact safe circumference remains safe");
        check(state.damageAt(OVERWORLD, 3, 4.0000001, true) == 1.5, "immediately outside circle is blue");
        check(state.damageAt(OVERWORLD, -10, -10, true) == 1.5, "negative-coordinate rectangle corner belongs to the map");
        check(state.damageAt(OVERWORLD, 10, 10, true) == 1.5, "positive rectangle corner belongs to the map");
        check(state.damageAt(OVERWORLD, 10.0000001, 0, true) == 0, "outside map is never damaged");
        check(state.damageAt(NETHER, 8, 0, true) == 0, "another dimension is unaffected");
        check(state.damageAt(OVERWORLD, 8, 0, false) == 0, "ineligible players receive no damage");
        check(state.damageAt(null, 8, 0, true) == 0, "missing dimension cannot match");
        for (double coordinate : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            check(state.damageAt(OVERWORLD, coordinate, 0, true) == 0, "nonfinite X cannot match");
            check(state.damageAt(OVERWORLD, 0, coordinate, true) == 0, "nonfinite Z cannot match");
        }
        MatchRuntimeState clipped = new MatchRuntimeState();
        clipped.start(new MapDefinition(2, "Clipped circle", OVERWORLD, -10, -10, 10, 10,
                new Circle(10, 0, 20), 2, List.of()));
        check(clipped.damageAt(OVERWORLD, -10, 0, true) == 0, "out-of-map portion of safe circle is clipped without moving the circle");
        check(clipped.damageAt(OVERWORLD, -10, 10, true) == 2, "valid blue corner remains outside the clipped safe circle");
        check(clipped.damageAt(OVERWORLD, -10, 10.1, true) == 0, "circle clipping never expands map bounds");
        MatchRuntimeState disabled = new MatchRuntimeState();
        disabled.start(map(3, 0, OVERWORLD));
        check(disabled.damageAt(OVERWORLD, 8, 0, true) == 0, "zero configured HP/s remains an explicit zero-damage match");
    }

    private static void sharedPulseAndOverlaps() {
        MatchRuntimeState state = new MatchRuntimeState();
        state.start(map(1, 1, OVERWORLD));
        for (int i = 1; i <= 7; i++) check(!state.advanceTick(), "first pulse waits for server-tick schedule");
        state.start(map(2, 4, OVERWORLD));
        state.start(map(3, 10, NETHER));
        check(state.damageAt(OVERWORLD, 8, 0, true) == 4, "overlapping running maps use maximum instead of additive damage");
        check(state.damageAt(NETHER, 8, 0, true) == 10, "stronger map in another dimension does not affect current dimension");
        int pulses = 0;
        for (int i = 8; i <= 2007; i++) if (state.advanceTick()) pulses++;
        check(pulses == 100, "differently timed match starts still emit exactly one shared pulse per 20 ticks");
        state.end(2);
        check(state.damageAt(OVERWORLD, 8, 0, true) == 1, "ending stronger overlap restores the weaker active damage");
        state.end(1);
        check(state.damageAt(OVERWORLD, 8, 0, true) == 0, "stopping one dimension does not leave residual damage");
        check(state.damageAt(NETHER, 8, 0, true) == 10, "another running dimension continues independently");
        state.end(3);
        for (int i = 0; i < 100; i++) check(!state.advanceTick(), "ending all matches stops every damage pulse");
    }

    private static void snapshotOwnership() {
        MatchRuntimeState state = new MatchRuntimeState();
        List<com.example.examplemod.zones.MapZoneModel.BombingZone> mutableZones = new ArrayList<>();
        MapDefinition saved = new MapDefinition(1, "Snapshot", OVERWORLD, -10, -10, 10, 10,
                new Circle(0, 0, 5), 2, mutableZones);
        state.start(saved);
        mutableZones.add(new com.example.examplemod.zones.MapZoneModel.BombingZone(1, new Circle(0, 0, 1), 60, 10, 4));
        check(state.session(1).map().bombingZones().isEmpty(), "mutable authoring source cannot alter running snapshot contents");
        MapDefinition editedDraft = new MapDefinition(1, "Changed draft", OVERWORLD, -10, -10, 10, 10,
                new Circle(0, 0, 9), 99, List.of());
        check(editedDraft.safeZone().contains(8, 0) && state.damageAt(OVERWORLD, 8, 0, true) == 2,
                "editing a draft does not move active safe zone or silently change damage");
        rejects(() -> state.sessions().clear(), "callers cannot clear internal running map list");
        rejects(() -> state.session(1).map().bombingZones().clear(), "callers cannot modify immutable snapshot list");
        state.start(map(2, 1, OVERWORLD));
        check(state.sessions().getFirst().map().id() == 1 && state.sessions().getLast().map().id() == 2,
                "active map enumeration uses stable map-ID order");
    }

    private static void randomizedIndependentOracle() {
        Random random = new Random(602618);
        MatchRuntimeState state = new MatchRuntimeState();
        List<MapDefinition> active = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            double x = random.nextDouble() * 160 - 80, z = random.nextDouble() * 160 - 80;
            double w = 10 + random.nextDouble() * 60, h = 10 + random.nextDouble() * 60;
            MapDefinition map = new MapDefinition(i, "Map " + i, i % 3 == 0 ? NETHER : OVERWORLD,
                    x, z, x + w, z + h, new Circle(x + w * random.nextDouble(), z + h * random.nextDouble(),
                    1 + random.nextDouble() * 30), random.nextDouble() * 20, List.of());
            active.add(map);
            state.start(map);
        }
        for (int i = 0; i < 6000; i++) {
            double x = random.nextDouble() * 300 - 150, z = random.nextDouble() * 300 - 150;
            ResourceLocation dimension = i % 4 == 0 ? NETHER : OVERWORLD;
            double expected = 0;
            for (MapDefinition map : active) {
                double dx = x - map.safeZone().centerX(), dz = z - map.safeZone().centerZ();
                if (dimension.equals(map.dimension()) && x >= map.minX() && x <= map.maxX()
                        && z >= map.minZ() && z <= map.maxZ()
                        && Math.hypot(dx, dz) > map.safeZone().radius()) {
                    expected = Math.max(expected, map.blueDamagePerSecond());
                }
            }
            check(Math.abs(state.damageAt(dimension, x, z, true) - expected) < 1e-10,
                    "independent radial-distance oracle matches overlapping damage selection");
        }
    }

    private static MapDefinition map(long id, double damage, ResourceLocation dimension) {
        return new MapDefinition(id, "Map " + id, dimension, -10, -10, 10, 10, new Circle(0, 0, 5), damage, List.of());
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
