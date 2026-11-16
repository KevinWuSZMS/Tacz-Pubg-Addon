package com.example.examplemod.lootspawn.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.inventory.client.MatchGunInteractionClient;

import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootFootprints;
import com.example.examplemod.lootspawn.GroundLootPayloads;
import com.example.examplemod.lootspawn.GroundLootPickup;
import com.example.examplemod.lootspawn.GroundLootRaycast;
import com.mojang.blaze3d.platform.InputConstants;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;

/** Targets our stationary loot independently of vanilla's tiny ItemEntity hitbox. */
public final class GroundLootClient {
    private static final KeyMapping PICKUP = new KeyMapping("key.tacz_pubg.pickup_loot", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, "key.categories.tacz_pubg");
    private static GroundLootEntity selected;
    private static boolean registered;

    private GroundLootClient() {}

    public static KeyMapping pickupKey() { return PICKUP; }

    public static void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(PICKUP));
        GroundLootEntity.setClientHighlight(entity -> entity == selected);
        NeoForge.EVENT_BUS.addListener(GroundLootClient::tick);
        NeoForge.EVENT_BUS.addListener(GroundLootClient::key);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, GroundLootClient::mouse);
        NeoForge.EVENT_BUS.addListener(GroundLootClient::hud);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> selected = null);
    }

    private static void tick(ClientTickEvent.Pre event) {
        selected = target();
        while (PICKUP.consumeClick()) tryPickupOrInteract();
    }

    private static void key(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS && PICKUP.matches(event.getKey(), event.getScanCode())) {
            while (PICKUP.consumeClick()) {}
            tryPickupOrInteract();
        }
    }

    private static void mouse(InputEvent.MouseButton.Pre event) {
        if (event.getAction() == GLFW.GLFW_PRESS && PICKUP.matchesMouse(event.getButton())
                && com.example.examplemod.combat.client.CombatInputClient.trySharedAction()) { event.setCanceled(true); return; }
        if (com.example.examplemod.vitals.client.MedicineClient.captureMouse(event)) {
            event.setCanceled(true);
            return;
        }
        if (MatchGunInteractionClient.captureMouse(event)) {
            event.setCanceled(true);
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS && PICKUP.matchesMouse(event.getButton())) {
            while (PICKUP.consumeClick()) {}
            // A rebound mouse button must not also start a held block attack/use.
            // When no valid pickup exists, vanilla receives the event unchanged.
            if (tryPickup()) event.setCanceled(true);
        }
    }

    /** A valid ground-loot target owns the action; otherwise use TaCZ's existing native interaction. */
    public static boolean tryPickupOrInteract() {
        if (com.example.examplemod.combat.client.CombatInputClient.trySharedAction()) return true;
        if (com.example.examplemod.vitals.client.MedicineClient.tryUseHeld()) return true;
        if (MatchGunInteractionClient.active()) {
            MatchGunInteractionClient.suppressSharedOffhandClick();
            if (com.example.examplemod.zones.match.flight.client.FlightClient.trySharedAction(PICKUP)) return true;
            if (!MatchGunInteractionClient.ready()) return false;
        }
        if (tryPickup()) return true;
        return MatchGunInteractionClient.interact();
    }

    private static boolean tryPickup() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.player.isSpectator()
                || (!mc.player.getMainHandItem().isEmpty() && !com.example.examplemod.inventory.InventoryConfig.active(mc.player))) return false;
        selected = target();
        if (selected == null) return false;
        // KeyboardHandler has already queued vanilla's F click before InputEvent.Key.
        // Consume that shared binding only for a valid empty-hand loot pickup.
        if (com.example.examplemod.keybindings.BindingInput.overlaps(mc.options.keySwapOffhand, PICKUP)) {
            while (mc.options.keySwapOffhand.consumeClick()) {}
        }
        PacketDistributor.sendToServer(new GroundLootPayloads.Pickup(selected.getId()));
        return true;
    }

    private static GroundLootEntity target() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.player.isSpectator()) return null;
        Vec3 eye = mc.player.getEyePosition(), end = eye.add(mc.player.getViewVector(1).scale(GroundLootPickup.reach(mc.player)));
        var block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        var candidates = new ArrayList<GroundLootRaycast.Target>();
        // The search envelope covers model extents, so aiming at a rifle's muzzle
        // also works when its tiny carrier entity is outside the narrow ray box.
        for (GroundLootEntity entity : mc.level.getEntitiesOfClass(GroundLootEntity.class, new AABB(eye, end).inflate(3))) {
            if (entity.isRemoved() || !entity.isResting() || entity.getItem().isEmpty() || entity.getQuantity() <= 0) continue;
            AABB bounds;
            if (entity.getItem().getItem() instanceof IGun) {
                bounds = GroundGunModelRenderer.bounds(entity.getItem(), mc.getItemRenderer().getModel(entity.getItem(), mc.level, null, entity.getId()));
            } else if (entity.getItem().getItem() instanceof IAmmo || entity.getItem().getItem() instanceof IAttachment) {
                bounds = GroundSupplementModelRenderer.bounds(entity.getItem(), mc.getItemRenderer().getModel(entity.getItem(), mc.level, null, entity.getId()));
            } else {
                bounds = GroundBakedModelRenderer.bounds(entity.getItem(), mc.getItemRenderer().getModel(entity.getItem(), mc.level, null, entity.getId()));
            }
            bounds = com.example.examplemod.modelscale.ModelScaleService.scale(bounds,
                    com.example.examplemod.modelscale.ModelScaleService.dropped(mc.level, entity.getItem()));
            candidates.add(new GroundLootRaycast.Target(entity.getId(), bounds.move(0, GroundLootFootprints.FLOOR_LIFT, 0).inflate(.025),
                    entity.position(), entity.getYRot()));
        }
        return GroundLootRaycast.nearest(eye, end, eye.distanceToSqr(block.getLocation()), candidates)
                .map(hit -> mc.level.getEntity(hit.id()) instanceof GroundLootEntity loot ? loot : null).orElse(null);
    }

    private static void hud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (selected == null || selected.isRemoved() || mc.player == null || mc.screen != null || mc.options.hideGui) return;
        Component hint = mc.player.getMainHandItem().isEmpty() || com.example.examplemod.inventory.InventoryConfig.active(mc.player)
                ? Component.translatable("gui.tacz_pubg.pickup_hint", PICKUP.getTranslatedKeyMessage(), selected.getItem().getHoverName(), selected.getQuantity())
                : Component.translatable("gui.tacz_pubg.pickup_empty_hand", selected.getItem().getHoverName(), selected.getQuantity());
        int x = (event.getGuiGraphics().guiWidth() - mc.font.width(Texts.resolve(hint))) / 2;
        int y = event.getGuiGraphics().guiHeight() / 2 + 18;
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(event.getGuiGraphics(), "loot_pickup",
                x, y, mc.font.width(Texts.resolve(hint)), mc.font.lineHeight)) {
            event.getGuiGraphics().drawString(mc.font, Texts.resolve(hint), x, y, 0xFFFFFFFF);
        }
    }
}
