package com.example.examplemod.lean.client;

import com.example.examplemod.keybindings.BindingInput;
import com.example.examplemod.keybindings.BindingProfile;
import com.example.examplemod.keybindings.BindingProfiles;
import com.example.examplemod.lean.LeanMotion;
import com.example.examplemod.lean.LeanPayloads;
import com.example.examplemod.lean.LeanService;
import com.example.examplemod.movement.client.MovementClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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

/** Ordinary registered bindings: match defaults Q/E, general defaults unbound. */
public final class LeanClient {
    private static final KeyMapping LEFT = key("lean_left"), RIGHT = key("lean_right");
    private static final LeanMotion.Input INPUT = new LeanMotion.Input();
    private static Object identity;
    private static BindingProfile profile;
    private static int sequence, sentDirection = Integer.MIN_VALUE;
    private LeanClient() { }
    private static KeyMapping key(String name) {
        return new KeyMapping("key.tacz_pubg." + name, KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.tacz_pubg");
    }

    public static void register(IEventBus bus) {
        LeanShotClient.register();
        bus.addListener((RegisterKeyMappingsEvent event) -> { event.register(LEFT); event.register(RIGHT); });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (InputEvent.Key event) -> {
            if (event.getAction() == GLFW.GLFW_REPEAT) return;
            if (LEFT.matches(event.getKey(), event.getScanCode())) change(-1, event.getAction());
            if (RIGHT.matches(event.getKey(), event.getScanCode())) change(1, event.getAction());
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (InputEvent.MouseButton.Post event) -> {
            if (LEFT.matchesMouse(event.getButton())) change(-1, event.getAction());
            if (RIGHT.matchesMouse(event.getButton())) change(1, event.getAction());
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, (ClientTickEvent.Pre event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            INPUT.reset(); identity = null; profile = null; sequence = 0; sentDirection = Integer.MIN_VALUE;
        });
    }

    private static boolean acceptsInput() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.getConnection() != null && mc.screen == null && mc.getOverlay() == null
                && mc.isWindowActive() && MovementClient.groundContext() && LeanService.eligible(mc.player);
    }
    private static void identity() {
        var mc = Minecraft.getInstance();
        if (identity != mc.player) {
            identity = mc.player; INPUT.reset(); sequence = 0; sentDirection = Integer.MIN_VALUE;
        }
        var next = BindingProfiles.get().activeProfile();
        if (next != profile) { profile = next; INPUT.reset(); }
    }
    private static void change(int side, int action) {
        identity();
        if (action == GLFW.GLFW_RELEASE) INPUT.release(side);
        else if (action == GLFW.GLFW_PRESS && acceptsInput()) INPUT.press(side);
        sync();
    }
    private static void tick() {
        identity();
        while (LEFT.consumeClick()) { }
        while (RIGHT.consumeClick()) { }
        if (!acceptsInput()) INPUT.reset();
        if (!BindingInput.physicallyHeld(LEFT)) INPUT.release(-1);
        if (!BindingInput.physicallyHeld(RIGHT)) INPUT.release(1);
        sync();
    }
    private static void sync() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        int direction = INPUT.direction();
        if (!LeanService.eligible(mc.player)) { INPUT.reset(); direction = 0; LeanService.clear(mc.player); }
        if (sentDirection != direction) {
            // A connection/player entity cannot receive two billion input edges in its lifetime.
            int next = ++sequence;
            LeanService.predict(mc.player, next, direction);
            PacketDistributor.sendToServer(new LeanPayloads.Input(mc.player.level().dimension().location(), mc.player.getId(), next, direction));
            sentDirection = direction;
        }
    }
}
