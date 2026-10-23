package com.example.examplemod.spawn.client;

import com.example.examplemod.spawn.SpawnAreaPayloads;
import com.example.examplemod.spawn.SpawnAreaService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Contextual authoring shortcuts; the vanilla attack/use bindings still select corners on blocks. */
public final class SpawnAreaKeys {
    private static final String CATEGORY = "key.categories.tacz_pubg.authoring";
    private static final IKeyConflictContext AIR_TOOL = new IKeyConflictContext() {
        @Override public boolean isActive() {
            Minecraft client = Minecraft.getInstance();
            return client.screen == null && client.player != null && client.level != null
                    && SpawnAreaService.holdsTool(client.player) && !hasBlockTarget();
        }
        @Override public boolean conflicts(IKeyConflictContext other) { return other == this; }
    };
    public static final KeyMapping CLEAR = new KeyMapping("key.tacz_pubg.area_clear", AIR_TOOL,
            KeyModifier.NONE, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, CATEGORY);
    public static final KeyMapping SAVE = new KeyMapping("key.tacz_pubg.area_save", AIR_TOOL,
            KeyModifier.NONE, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, CATEGORY);
    public static final KeyMapping ERASE = new KeyMapping("key.tacz_pubg.area_erase", AIR_TOOL,
            KeyModifier.SHIFT, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, CATEGORY);

    private SpawnAreaKeys() {}

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CLEAR);
        event.register(SAVE);
        event.register(ERASE);
    }

    public static boolean hasBlockTarget() {
        HitResult hit = Minecraft.getInstance().hitResult;
        return hit != null && hit.getType() == HitResult.Type.BLOCK;
    }

    /** NeoForge's documented tick/consumeClick path also handles keyboard rebindings. */
    static void tick() {
        consume(ERASE, SpawnAreaPayloads.ToolAction.ERASE);
        consume(SAVE, SpawnAreaPayloads.ToolAction.SAVE);
        consume(CLEAR, SpawnAreaPayloads.ToolAction.CLEAR);
    }

    private static void consume(KeyMapping mapping, SpawnAreaPayloads.ToolAction action) {
        while (mapping.consumeClick()) {
            if (AIR_TOOL.isActive()) PacketDistributor.sendToServer(new SpawnAreaPayloads.ToolRequest(action));
        }
    }

    /** Prevent an air shortcut from simultaneously punching an entity or using an offhand item. */
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!AIR_TOOL.isActive() || (!event.isAttack() && !event.isUseItem())) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    static String clearLabel() { return CLEAR.getTranslatedKeyMessage().getString(); }
    static String saveLabel() { return SAVE.getTranslatedKeyMessage().getString(); }
    static String eraseLabel() { return ERASE.getTranslatedKeyMessage().getString(); }
}
