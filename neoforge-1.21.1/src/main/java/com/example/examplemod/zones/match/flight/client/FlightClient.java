package com.example.examplemod.zones.match.flight.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.match.flight.FlightPayloads;
import com.example.examplemod.zones.match.flight.FlightRegistration;
import com.example.examplemod.zones.match.flight.FlightMotion;
import com.example.examplemod.zones.match.flight.ParachuteItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class FlightClient {
    private static final KeyMapping JUMP = new KeyMapping("key.tacz_pubg.flight_jump", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, "key.categories.tacz_pubg");
    private static final KeyMapping DIVE = new KeyMapping("key.tacz_pubg.flight_dive", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, "key.categories.tacz_pubg");
    private static final KeyMapping GLIDE = new KeyMapping("key.tacz_pubg.flight_glide", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL, "key.categories.tacz_pubg");
    private static FlightPayloads.State current = FlightPayloads.State.NONE;
    private static int age;
    private static Vec3 lastPosition;
    private static double speed;
    private FlightClient() {}
    static int renderStage(LivingEntity entity) {
        if (entity == Minecraft.getInstance().player) return current.stage();
        var marker = entity.getItemBySlot(EquipmentSlot.LEGS)
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return marker.hasUUID("flight_pack") ? marker.getInt(ParachuteItem.FLIGHT_STAGE_TAG) : 0;
    }
    public static void register(IEventBus bus) {
        ParachuteCanopyRenderer.register(bus);
        FlightPayloads.setClientHandler(s -> {
            if (!s.session().equals(current.session()) || s.stage() < 2 || current.stage() < 2) {
                lastPosition = null; speed = 0;
            }
            current = s; age = 0;
        });
        FlightMotion.setClientControls(player -> {
            var mc = Minecraft.getInstance();
            if (player != mc.player || current.stage() < 2 || current.stage() > 3) return null;
            boolean controls = mc.screen == null;
            float forward = controls ? (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0) : 0;
            float strafe = controls ? (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0) : 0;
            return new FlightMotion.Control(current.stage(), forward, strafe, player.getYRot(), player.getXRot(),
                    controls && DIVE.isDown(), controls && GLIDE.isDown());
        });
        bus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(JUMP); event.register(DIVE); event.register(GLIDE);
        });
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(FlightRegistration.TRANSPORT.get(), NoopRenderer::new));
        bus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "flight"), (graphics, delta) -> render(graphics)));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            current = FlightPayloads.State.NONE; lastPosition = null; speed = 0;
            FreefallPose.clear();
        });
        NeoForge.EVENT_BUS.addListener((MovementInputUpdateEvent event) -> {
            if (current.stage() == 0) return;
            var input = event.getInput();
            if (current.stage() == 1) {
                input.forwardImpulse = input.leftImpulse = 0;
                input.up = input.down = input.left = input.right = false;
            }
            input.jumping = input.shiftKeyDown = false;
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> {
            var mc = Minecraft.getInstance();
            if (!mc.isPaused() && ++age > 100) current = FlightPayloads.State.NONE;
            while (JUMP.consumeClick()) performJumpAction();
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            var mc = Minecraft.getInstance();
            if (mc.isPaused()) return;
            if (mc.player == null || current.stage() < 2 || !ParachuteItem.isEquipped(mc.player)) {
                lastPosition = null; speed = 0; return;
            }
            Vec3 position = mc.player.position();
            if (lastPosition != null) {
                double distance = position.distanceTo(lastPosition);
                // Read actual movement, including horizontal glide and collision braking.
                // A teleport correction is not a physical speed sample.
                if (distance <= FlightMotion.FREEFALL_LIMIT_METRES_PER_SECOND / 20 + 1)
                    speed += (distance * 20 * 3.6 - speed) * .4;
            }
            lastPosition = position;
        });
    }
    /** Preserve the flight action when another match input captures the same physical binding. */
    public static boolean trySharedAction(KeyMapping binding) {
        if (current.stage() <= 0 || !com.example.examplemod.keybindings.BindingInput.overlaps(JUMP, binding)) return false;
        while (JUMP.consumeClick()) {}
        performJumpAction();
        return true;
    }
    private static void performJumpAction() {
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.isPaused()) return;
        // F is also vanilla's swap-offhand key. A flight action must not swap a gun/item.
        if (current.stage() > 0 && com.example.examplemod.keybindings.BindingInput.overlaps(JUMP, mc.options.keySwapOffhand))
            while (mc.options.keySwapOffhand.consumeClick()) {}
        if (current.stage() == 1 && current.jumpWait() == 0)
            PacketDistributor.sendToServer(new FlightPayloads.Jump(current.session()));
        else if (current.stage() == 2 && current.canDeploy())
            PacketDistributor.sendToServer(new FlightPayloads.Deploy(current.session()));
    }
    private static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (mc.options.hideGui || current.stage() == 0 || mc.screen != null || mc.player == null || mc.level == null) return;
        int cx = graphics.guiWidth() / 2, cy = graphics.guiHeight() / 2;
        if (current.stage() == 1) {
            var text = current.jumpWait() > 0 ? Texts.of("message.tacz_pubg.flight.wait", current.jumpWait())
                    : Texts.of("message.tacz_pubg.flight.jump", JUMP.getTranslatedKeyMessage());
            try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "flight",
                    cx - 100, cy + 40, 200, mc.font.lineHeight)) {
            graphics.drawCenteredString(mc.font, text, cx, cy + 40, 0xFFFFFF);
            }
            return;
        }
        if (!ParachuteItem.isEquipped(mc.player)) return;
        int half = Math.max(30, (int) (graphics.guiHeight() * .15));
        int gap = Math.max(64, (int) (graphics.guiWidth() * .238));
        cy += (int) (graphics.guiHeight() * .01);
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "flight",
                cx - gap - 65, cy - half - 15, gap * 2 + 130, half * 2 + 52)) {
        double top = Math.max(current.launchHeight(), mc.player.getY());
        double bottom = mc.level.getMinBuildHeight();
        axis(graphics, cx - gap, cy - half, half * 2, mc.player.getY() - bottom, top - bottom,
                Long.toString(Math.round(mc.player.getY())), "M", true, "message.tacz_pubg.flight.altitude");
        axis(graphics, cx + gap, cy - half, half * 2, speed, 250,
                Long.toString(Math.round(speed)), "KM/H", false, "message.tacz_pubg.flight.speed");
        // Ground and automatic deployment marks use cached client terrain, never a chunk load.
        var at = mc.player.blockPosition();
        if (mc.level.hasChunkAt(at)) {
            int surface = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            int mark = cy + half - (int) ((surface + current.autoDeployHeight() - bottom) / Math.max(1, top - bottom) * half * 2);
            graphics.fill(cx - gap - 3, Math.clamp(mark, cy - half, cy + half),
                    cx - gap + 8, Math.clamp(mark, cy - half, cy + half) + 2, 0xFFE4BD58);
        }
        if (current.stage() == 2 && current.canDeploy()) {
            var text = Texts.of("message.tacz_pubg.flight.deploy", JUMP.getTranslatedKeyMessage());
            int x = cx + (int) (graphics.guiWidth() * .06), y = cy + (int) (graphics.guiHeight() * .06);
            graphics.fill(x - 3, y - 3, x + mc.font.width(text) + 3, y + mc.font.lineHeight + 2, 0x90000000);
            graphics.drawString(mc.font, text, x, y, 0xFFFFFFFF);
        }
        graphics.drawCenteredString(mc.font, Texts.of(current.stage() == 3
                        ? "message.tacz_pubg.flight.canopy_controls" : "message.tacz_pubg.flight.fall_controls",
                DIVE.getTranslatedKeyMessage(), GLIDE.getTranslatedKeyMessage()), cx, cy + half + 26, 0xDDDDDD);
        }
    }
    private static void axis(GuiGraphics g, int x, int top, int height, double value, double maximum,
            String number, String unit, boolean left, String label) {
        var font = Minecraft.getInstance().font;
        g.fill(x, top, x + 1, top + height + 1, 0x90FFFFFF);
        for (int i = 0; i <= 12; i++) {
            int y = top + height * i / 12, width = i % 3 == 0 ? 7 : 4;
            g.fill(left ? x : x - width, y, left ? x + width : x, y + 1, 0xA0FFFFFF);
        }
        int at = top + height - (int) (Math.clamp(value / Math.max(1, maximum), 0, 1) * height);
        g.fill(x - 3, at - 1, x + 4, at + 2, 0xFFFFFFFF);
        int numberWidth = font.width(number), unitWidth = (int) Math.ceil(font.width(unit) * .75);
        int textX = left ? x - 8 - numberWidth - unitWidth - 2 : x + 9;
        g.fill(textX - 2, at - 6, textX + numberWidth + unitWidth + 4, at + 5, 0x50000000);
        g.drawString(font, number, textX, at - 4, 0xFFFFFFFF);
        g.pose().pushPose();
        g.pose().scale(.75F, .75F, 1);
        g.drawString(font, unit, (int) ((textX + numberWidth + 2) / .75), (int) ((at - 2) / .75), 0xFFBFC5C8);
        g.pose().popPose();
        g.drawCenteredString(font, Texts.of(label), x, top + height + 5, 0xFFDDDDDD);
    }
}
