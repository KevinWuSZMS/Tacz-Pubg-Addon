package com.example.examplemod.movement.client;

import com.example.examplemod.combat.CombatService;
import com.example.examplemod.combat.CombatPayloads;
import com.example.examplemod.keybindings.BindingInput;
import com.example.examplemod.keybindings.BindingProfile;
import com.example.examplemod.keybindings.BindingProfiles;
import com.example.examplemod.keybindings.mixin.KeyMappingAccess;
import com.example.examplemod.movement.MovementPayloads;
import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MatchSwimming;
import com.example.examplemod.movement.SwimmingInput;
import com.example.examplemod.movement.SwimmingMotion;
import com.example.examplemod.movement.MovementState;
import com.example.examplemod.movement.MovementState.Action;
import com.example.examplemod.movement.MovementState.Mode;
import com.mojang.blaze3d.platform.InputConstants;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import java.util.EnumMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Ground stance toggles and water/flight physical holds share the player's existing bindings. */
public final class MovementClient {
    private static final MovementState STATE = new MovementState();
    private static final EnumMap<Action, KeyMapping> KEYS = new EnumMap<>(Action.class);
    private static Mode sentMode = Mode.STAND;
    private static boolean sentActive, clearNativeCrawl, pendingStandFrame, wasFlying, wasRescuing, wasSwimming;
    private static Object playerIdentity;
    private static BindingProfile profile;
    static {
        createKey(Action.STAND, "stand"); createKey(Action.SPRINT, "match_sprint"); createKey(Action.CROUCH, "match_crouch");
        createKey(Action.PRONE, "match_prone"); createKey(Action.WALK, "match_walk");
    }
    private MovementClient() { }
    private static void createKey(Action action, String name) {
        KEYS.put(action, new KeyMapping("key.tacz_pubg." + name, KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.tacz_pubg"));
    }
    public static void register(IEventBus bus) {
        CombatPayloads.setClientRescueHandler(MovementClient::acceptRescueStance);
        bus.addListener((RegisterKeyMappingsEvent event) -> KEYS.values().forEach(event::register));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, MovementClient::key);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, MovementClient::mouse);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (ClientTickEvent.Pre event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> reset(false));
    }

    private static void key(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_REPEAT) return;
        for (var entry : KEYS.entrySet()) if (entry.getValue().matches(event.getKey(), event.getScanCode()))
            change(entry.getKey(), event.getAction());
    }
    private static void mouse(InputEvent.MouseButton.Post event) {
        for (var entry : KEYS.entrySet()) if (entry.getValue().matchesMouse(event.getButton()))
            change(entry.getKey(), event.getAction());
    }
    private static void change(Action action, int event) {
        adoptRescueStance();
        if (event == GLFW.GLFW_RELEASE) {
            STATE.release(action); sync();
            var player = Minecraft.getInstance().player;
            if (player != null && ownsGround() && STATE.mode() != Mode.SPRINT) player.setSprinting(false);
            return;
        }
        var mc = Minecraft.getInstance();
        if (event != GLFW.GLFW_PRESS || !groundContext() || mc.screen != null || !mc.isWindowActive() || mc.getOverlay() != null) return;
        var player = mc.player;
        enforceWaterMode();
        Mode requested = switch (action) {
            case STAND -> Mode.STAND;
            case SPRINT -> Mode.SPRINT;
            case CROUCH -> Mode.CROUCH;
            case PRONE -> Mode.PRONE;
            case WALK -> Mode.WALK;
        };
        if (MatchSwimming.legalGroundMode(player, requested) != requested) return;
        boolean lowered = player.isCrouching() || player.getPose() == Pose.CROUCHING
                || IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl();
        STATE.press(action, lowered);
        if (ownsGround()) clearNativeMovementToggles();
        if (action == Action.STAND && STATE.consumeJump()) {
            ((KeyMappingAccess) Minecraft.getInstance().options.keyShift).taczPubg$setRawDown(false);
            player.input.shiftKeyDown = false;
            clearNativeCrawl = true;
            pendingStandFrame = true;
        }
        sync();
        if (ownsGround() && STATE.mode() != Mode.SPRINT) player.setSprinting(false);
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.player != playerIdentity) {
            STATE.reset(); sentMode = Mode.STAND; sentActive = false; clearNativeCrawl = false; pendingStandFrame = false; wasRescuing = false; wasSwimming = false;
            playerIdentity = mc.player;
        }
        BindingProfile current = BindingProfiles.get().activeProfile();
        if (profile != current) { reset(true); profile = current; }
        adoptRescueStance();
        for (var entry : KEYS.entrySet()) {
            while (entry.getValue().consumeClick()) { }
            if (!BindingInput.physicallyHeld(entry.getValue())) STATE.release(entry.getKey());
        }
        if (mc.player == null) return;
        boolean flight = flying();
        if (flight != wasFlying) {
            // Flying's physical descent key must not leave the ordinary crouch toggle latched on landing.
            ((KeyMappingAccess) mc.options.keyShift).taczPubg$setRawDown(false);
            wasFlying = flight;
        }
        if (swimmingContext()) {
            reset(true);
            clearNativeMovementToggles();
            var gun = IClientPlayerGunOperator.fromLocalPlayer(mc.player);
            if (gun.isCrawl()) gun.crawl(false);
            wasSwimming = true;
            return;
        }
        if (wasSwimming) {
            wasSwimming = false;
            reset(true);
            clearNativeMovementToggles();
            mc.player.setSprinting(false);
            mc.player.input.shiftKeyDown = false;
            // Sprint is a hold in both environments; landing must not require releasing/repressing it.
            if (groundContext() && acceptsSwimmingInput() && BindingInput.physicallyHeld(KEYS.get(Action.SPRINT))
                    && MatchSwimming.legalGroundMode(mc.player, Mode.SPRINT) == Mode.SPRINT)
                STATE.press(Action.SPRINT);
        }
        if (!groundContext()) { reset(true); return; }
        enforceWaterMode();
        if (!mc.isWindowActive() || mc.screen != null) {
            STATE.release(Action.SPRINT); STATE.release(Action.WALK); STATE.release(Action.STAND);
        }
        if (clearNativeCrawl) {
            var gun = IClientPlayerGunOperator.fromLocalPlayer(mc.player);
            if (gun.isCrawl()) gun.crawl(false); else clearNativeCrawl = false;
        }
        sync();
        if (ownsGround() && STATE.mode() != Mode.SPRINT) mc.player.setSprinting(false);
    }

    public static boolean flying() {
        var p = Minecraft.getInstance().player;
        return p != null && (p.getAbilities().flying || p.isSpectator());
    }
    public static boolean groundContext() {
        var p = Minecraft.getInstance().player;
        return p != null && MovementService.eligible(p);
    }
    public static boolean swimmingContext() {
        var p = Minecraft.getInstance().player;
        return p != null && MatchSwimming.swimming(p);
    }
    public static boolean wadingContext() {
        var p = Minecraft.getInstance().player;
        return p != null && MatchSwimming.wading(p);
    }
    public static boolean waterContext() { return swimmingContext() || wadingContext(); }
    private static SwimmingInput swimmingInput() {
        var mc = Minecraft.getInstance();
        return SwimmingInput.resolve(acceptsSwimmingInput(), BindingInput.physicallyHeld(mc.options.keyJump),
                BindingInput.physicallyHeld(KEYS.get(Action.CROUCH)),
                BindingInput.physicallyHeld(KEYS.get(Action.SPRINT)), mc.player == null ? 0 : mc.player.input.forwardImpulse);
    }
    private static boolean acceptsSwimmingInput() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.screen == null && mc.getOverlay() == null && mc.isWindowActive();
    }
    private static void swimmingInput(Input input) {
        var movement = swimmingInput();
        input.jumping = movement.vertical() > 0;
        input.shiftKeyDown = movement.vertical() < 0;
        // KeyboardInput has already applied vanilla crouch slowdown. Swimming uses full directional input.
        input.forwardImpulse = !acceptsSwimmingInput() || input.up == input.down ? 0 : input.up ? 1 : -1;
        input.leftImpulse = !acceptsSwimmingInput() || input.left == input.right ? 0 : input.left ? 1 : -1;
        clearNativeMovementToggles();
    }
    private static void wadingJump(Input input) {
        var mc = Minecraft.getInstance();
        // No hopping through open water. Space still permits climbing onto a reachable bank.
        input.jumping = !consumesJump() && acceptsSwimmingInput()
                && BindingInput.physicallyHeld(mc.options.keyJump) && MatchSwimming.canClimbOut(mc.player);
    }
    /** Crossing a depth boundary releases only an illegal stance, never forces a slower one. */
    private static void enforceWaterMode() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        Mode previous = STATE.mode();
        if (!STATE.constrainMode(MatchSwimming.legalGroundMode(player, previous))) return;
        if (previous == Mode.PRONE) clearNativeCrawl = true;
        pendingStandFrame = false;
        player.input.shiftKeyDown = false;
        player.setSprinting(false);
        clearNativeMovementToggles();
    }
    private static void adoptRescueStance() {
        if (Minecraft.getInstance().player == null) { wasRescuing = false; return; }
        acceptRescueStance(CombatPayloads.latest().rescue());
    }
    private static void acceptRescueStance(CombatPayloads.Rescue state) {
        var player = Minecraft.getInstance().player;
        boolean rescue = player != null && state.active() && state.rescuer().equals(player.getUUID());
        if (rescue && !wasRescuing) {
            STATE.adoptRescueCrouch();
            clearNativeCrawl = true; pendingStandFrame = false;
            MovementService.predict(player, Mode.CROUCH, true);
            player.setSprinting(false); player.setPose(Pose.CROUCHING);
        }
        wasRescuing = rescue;
    }
    public static boolean ownsGround() {
        return groundContext() && (wadingContext() || BindingProfiles.get().activeProfile() == BindingProfile.MATCH
                || STATE.mode() != Mode.STAND || STATE.held(Action.SPRINT) || STATE.held(Action.WALK)
                || STATE.held(Action.CROUCH) || STATE.held(Action.PRONE)
                || STATE.consumeJump() || pendingStandFrame || clearNativeCrawl);
    }
    public static Mode mode() { return STATE.mode(); }
    public static boolean consumesJump() { return ownsGround() && (STATE.consumeJump() || pendingStandFrame || clearNativeCrawl); }

    /** Called before vanilla snapshots last tick's crouch/jump flags. */
    public static void beforeAiStep(LocalPlayer player) {
        if (player != Minecraft.getInstance().player) return;
        if (swimmingContext()) {
            swimmingInput(player.input);
            // Both native sinkInFluid and jumpInFluid run after aiStep HEAD. Clean only the
            // previous tick's carry here so ascent and descent receive the same new thrust.
            int vertical = swimmingInput().vertical();
            var motion = player.getDeltaMovement();
            double retained = SwimmingMotion.retainedVertical(motion.y, vertical,
                    player.getAttributeValue(net.neoforged.neoforge.common.NeoForgeMod.SWIM_SPEED));
            player.setDeltaMovement(motion.x, retained, motion.z);
            return;
        }
        enforceWaterMode();
        if (ownsGround()) {
            player.input.shiftKeyDown = STATE.mode() == Mode.CROUCH;
            if (consumesJump()) player.input.jumping = false;
            if (wadingContext()) wadingJump(player.input);
        }
    }

    /** KeyboardInput already has its raw direction booleans; apply exactly one posture speed factor. */
    public static void updateInput(Input input) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || input != mc.player.input) return;
        if (flying()) {
            boolean acceptsFlightInput = mc.screen == null && mc.isWindowActive() && mc.getOverlay() == null;
            input.jumping = acceptsFlightInput && BindingInput.physicallyHeld(mc.options.keyJump);
            input.shiftKeyDown = acceptsFlightInput && BindingInput.physicallyHeld(mc.options.keyShift);
            ((KeyMappingAccess) mc.options.keyShift).taczPubg$setRawDown(input.shiftKeyDown);
            return;
        }
        if (swimmingContext()) {
            swimmingInput(input);
            return;
        }
        enforceWaterMode();
        if (!ownsGround()) return;
        clearNativeMovementToggles();
        input.shiftKeyDown = STATE.mode() == Mode.CROUCH;
        if (consumesJump()) input.jumping = false;
        if (wadingContext()) wadingJump(input);
        float scale = switch (STATE.mode()) {
            case CROUCH, PRONE, WALK -> (float) mc.player.getAttributeValue(Attributes.SNEAKING_SPEED);
            default -> mc.player.isCrouching() || mc.player.isVisuallyCrawling()
                    ? (float) mc.player.getAttributeValue(Attributes.SNEAKING_SPEED) : 1.0F;
        };
        input.forwardImpulse = (input.up == input.down ? 0 : input.up ? 1 : -1) * scale;
        input.leftImpulse = (input.left == input.right ? 0 : input.left ? 1 : -1) * scale;
    }

    /** Shared physical keys must not leave native toggles latched when custom ground control ends. */
    private static void clearNativeMovementToggles() {
        var options = Minecraft.getInstance().options;
        ((KeyMappingAccess) options.keyShift).taczPubg$setRawDown(false);
        ((KeyMappingAccess) options.keySprint).taczPubg$setRawDown(false);
    }

    public static boolean nativeSprint(KeyMapping mapping) {
        if (mapping == Minecraft.getInstance().options.keySprint && swimmingContext()) return swimmingInput().fast();
        if (mapping == Minecraft.getInstance().options.keySprint && wadingContext()
                && MatchSwimming.legalGroundMode(Minecraft.getInstance().player, Mode.SPRINT) != Mode.SPRINT) return false;
        if (mapping != Minecraft.getInstance().options.keySprint || !ownsGround()) return mapping.isDown();
        return STATE.mode() == Mode.SPRINT && BindingInput.physicallyHeld(KEYS.get(Action.SPRINT));
    }
    public static boolean canStartSprint() {
        if (wadingContext() && MatchSwimming.legalGroundMode(Minecraft.getInstance().player, Mode.SPRINT) != Mode.SPRINT) return false;
        return swimmingContext() ? swimmingInput().fast() : !ownsGround() || STATE.mode() == Mode.SPRINT;
    }
    /** Run after vanilla sprint decisions and before LivingEntity applies water movement this tick. */
    public static void beforeTravel(LocalPlayer player) {
        if (player != Minecraft.getInstance().player) return;
        if (wadingContext()) {
            enforceWaterMode();
            wadingJump(player.input);
            // Vanilla cancels sprint above the waterline. Restore it only in the permitted
            // ankle-deep band, with the normal forward input, food and collision gates.
            boolean sprint = STATE.mode() == Mode.SPRINT && acceptsSwimmingInput()
                    && BindingInput.physicallyHeld(KEYS.get(Action.SPRINT)) && player.input.forwardImpulse >= .8F
                    && MatchSwimming.legalGroundMode(player, Mode.SPRINT) == Mode.SPRINT
                    && !player.isUsingItem() && !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    && (player.getFoodData().getFoodLevel() > 6 || player.mayFly())
                    && (!player.horizontalCollision || player.minorHorizontalCollision);
            player.setSprinting(sprint);
            return;
        }
        if (!swimmingContext()) return;
        boolean fast = swimmingInput().fast() && !player.isUsingItem()
                && !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                && (player.getFoodData().getFoodLevel() > 6 || player.getAbilities().mayfly);
        player.setSprinting(fast);
    }
    public static void afterAiStep(LocalPlayer player) {
        if (player == Minecraft.getInstance().player && ownsGround()
                && (STATE.mode() != Mode.SPRINT || !BindingInput.physicallyHeld(KEYS.get(Action.SPRINT)))) player.setSprinting(false);
        if (player == Minecraft.getInstance().player) pendingStandFrame = false;
    }

    private static void sync() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        boolean active = ownsGround();
        MovementService.predict(mc.player, STATE.mode(), active);
        if (sentMode != STATE.mode() || sentActive != active) {
            PacketDistributor.sendToServer(new MovementPayloads.Input(STATE.mode(), active));
            sentMode = STATE.mode(); sentActive = active;
        }
    }
    private static void reset(boolean send) {
        STATE.reset(); clearNativeCrawl = false; pendingStandFrame = false;
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            MovementService.predict(mc.player, Mode.STAND, false);
            if (send && sentActive && mc.getConnection() != null) PacketDistributor.sendToServer(new MovementPayloads.Input(Mode.STAND, false));
        }
        sentMode = Mode.STAND; sentActive = false;
        if (mc.player == null) { wasRescuing = false; wasSwimming = false; }
    }
}
