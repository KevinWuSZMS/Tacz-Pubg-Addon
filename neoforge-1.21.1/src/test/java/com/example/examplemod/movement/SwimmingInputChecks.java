package com.example.examplemod.movement;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Hold controls and installed Minecraft hook targets, without starting a client or world. */
public final class SwimmingInputChecks {
    private static final String LOCAL = "net/minecraft/client/player/LocalPlayer";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static int checks;

    private SwimmingInputChecks() { }

    public static void main(String[] args) throws Exception {
        heldControls();
        nativeHooks();
        clientRegistration();
        System.out.println("SwimmingInputChecks: " + checks + " input and native hook checks passed; no game launched.");
    }

    private static void heldControls() {
        expect(SwimmingInput.resolve(true, false, false, false, 1), 0, false, false,
                "ordinary forward swimming does not require sprint or a vertical key");
        expect(SwimmingInput.resolve(true, true, false, false, 0), 1, false, true,
                "jump rises while stationary and takes priority over downward looking");
        expect(SwimmingInput.resolve(true, false, true, false, 0), -1, false, true,
                "crouch dives while stationary and takes priority over upward looking");
        for (int tick = 0; tick < 3; tick++) {
            expect(SwimmingInput.resolve(true, false, true, false, 1), -1, false, true,
                    "holding crouch keeps diving instead of toggling posture");
        }
        expect(SwimmingInput.resolve(true, false, false, false, 1), 0, false, false,
                "releasing crouch stops manual diving immediately");
        expect(SwimmingInput.resolve(true, true, true, false, 1), 0, false, true,
                "opposing vertical keys cancel each other while still suppressing look thrust");
        expect(SwimmingInput.resolve(true, true, true, true, 1), 0, true, true,
                "opposing vertical keys do not cancel independent fast swimming");
        expect(SwimmingInput.resolve(true, true, false, true, 1), 1, true, true,
                "rise and fast swimming can be held together");
        expect(SwimmingInput.resolve(true, false, true, true, 1), -1, true, true,
                "dive and fast swimming can be held together");
        expect(SwimmingInput.resolve(true, true, false, false, 1), 1, false, true,
                "releasing sprint keeps the independently held rise action");
        expect(SwimmingInput.resolve(true, false, false, true, -1), 0, false, false,
                "backward swimming does not gain the forward sprint boost");
        expect(SwimmingInput.resolve(true, false, false, true, 0), 0, false, false,
                "stationary or strafe-only swimming does not gain forward sprint");
        expect(SwimmingInput.resolve(true, false, false, true, 0.2F), 0, true, false,
                "positive forward swimming accepts sprint without the land 0.8 threshold");
        expect(SwimmingInput.resolve(true, false, false, true, Float.NaN), 0, false, false,
                "invalid forward input cannot start fast swimming");
        for (int held = 0; held < 8; held++) {
            expect(SwimmingInput.resolve(false, (held & 1) != 0, (held & 2) != 0, (held & 4) != 0, 1),
                    0, false, false, "menus, overlays and focus loss suppress all held swim actions");
        }
    }

    private static void nativeHooks() throws Exception {
        MethodNode step = method(LOCAL, "aiStep", "()V");
        int inputAt = call(step, "net/minecraft/client/player/Input", "tick", "(ZF)V", Opcodes.INVOKEVIRTUAL);
        int sinkAt = call(step, LOCAL, "sinkInFluid", "(Lnet/neoforged/neoforge/fluids/FluidType;)V", Opcodes.INVOKEVIRTUAL);
        int movementAt = call(step, "net/minecraft/client/player/AbstractClientPlayer", "aiStep", "()V", Opcodes.INVOKESPECIAL);
        check(inputAt < sinkAt && sinkAt < movementAt,
                "native input and one dive impulse occur before the exact pre-travel injection target");
        int finalSprintAt = -1;
        for (var instruction : step.instructions) {
            if (instruction instanceof MethodInsnNode invocation && invocation.owner.equals(LOCAL)
                    && invocation.name.equals("setSprinting") && invocation.desc.equals("(Z)V")) {
                finalSprintAt = step.instructions.indexOf(instruction);
            }
        }
        check(finalSprintAt >= 0 && finalSprintAt < movementAt,
                "fast-swim restoration runs after every vanilla sprint start/cancel decision");
        method(LOCAL, "canStartSprinting", "()Z");
        method("net/minecraft/client/player/KeyboardInput", "tick", "(ZF)V");
        field(LOCAL, "autoJumpTime", "I");
        field(PLAYER, "jumpTriggerTime", "I");

        MethodNode travel = method(PLAYER, "travel", "(Lnet/minecraft/world/phys/Vec3;)V");
        int swimGuardAt = call(travel, PLAYER, "isSwimming", "()Z", Opcodes.INVOKEVIRTUAL);
        int lookAt = call(travel, PLAYER, "getLookAngle", "()Lnet/minecraft/world/phys/Vec3;", Opcodes.INVOKEVIRTUAL);
        int firstWaterTravelAt = Integer.MAX_VALUE;
        int travelBranches = 0;
        for (var instruction : travel.instructions) {
            if (instruction instanceof MethodInsnNode invocation && invocation.owner.equals(LIVING)
                    && invocation.name.equals("travel") && invocation.desc.equals("(Lnet/minecraft/world/phys/Vec3;)V")) {
                check(invocation.getOpcode() == Opcodes.INVOKESPECIAL, "Player delegates physical movement to its superclass");
                firstWaterTravelAt = Math.min(firstWaterTravelAt, travel.instructions.indexOf(instruction));
                travelBranches++;
            }
        }
        check(swimGuardAt < lookAt && lookAt < firstWaterTravelAt && travelBranches == 2,
                "one swimming guard encloses look thrust before flight and ordinary physical movement branches");
        method(PLAYER, "updateSwimming", "()V");
        method(PLAYER, "updatePlayerPose", "()V");
    }

    private static void clientRegistration() throws Exception {
        try (InputStream input = resource("tacz_pubg.movement_client.mixins.json")) {
            var config = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var client = config.getAsJsonArray("client");
            for (String mixin : new String[]{"MovementKeyboardInputMixin", "MovementLocalPlayerMixin", "MovementSwimmingTravelMixin"}) {
                check(client.asList().stream().anyMatch(entry -> entry.getAsString().equals(mixin)),
                        mixin + " is registered for client distribution only");
            }
            check(!config.has("mixins"), "client swimming hooks are not installed on a dedicated server");
            check(config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1,
                    "a missing native swimming hook fails visibly instead of silently disabling controls");
        }
    }

    private static void expect(SwimmingInput actual, int vertical, boolean fast, boolean manual, String reason) {
        check(actual.vertical() == vertical && actual.fast() == fast && actual.manualVertical() == manual,
                reason + " (actual " + actual + ")");
    }

    private static ClassNode load(String name) throws Exception {
        try (InputStream input = resource(name + ".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static InputStream resource(String name) {
        InputStream input = SwimmingInputChecks.class.getClassLoader().getResourceAsStream(name);
        if (input == null) throw new AssertionError("Missing resource: " + name);
        return input;
    }

    private static MethodNode method(String owner, String name, String descriptor) throws Exception {
        var found = load(owner).methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        check(found.size() == 1, "installed target exists exactly once: " + owner + "." + name + descriptor);
        return found.getFirst();
    }

    private static void field(String owner, String name, String descriptor) throws Exception {
        check(load(owner).fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(descriptor)
                        && (f.access & Opcodes.ACC_STATIC) == 0).count() == 1,
                "installed input latch field: " + owner + "." + name);
    }

    private static int call(MethodNode method, String owner, String name, String descriptor, int opcode) {
        int count = 0, index = -1;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invocation && invocation.owner.equals(owner)
                    && invocation.name.equals(name) && invocation.desc.equals(descriptor)) {
                check(invocation.getOpcode() == opcode, method.name + " uses the expected invocation kind for " + name);
                count++;
                index = method.instructions.indexOf(instruction);
            }
        }
        check(count == 1, method.name + " contains one exact hook target " + owner + "." + name + " (actual " + count + ")");
        return index;
    }

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}
