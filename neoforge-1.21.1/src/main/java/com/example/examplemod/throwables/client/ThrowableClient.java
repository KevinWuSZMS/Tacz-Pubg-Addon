package com.example.examplemod.throwables.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.keybindings.BindingInput;
import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.example.examplemod.throwables.PubgThrowableEntity;
import com.example.examplemod.throwables.PubgThrowableItem;
import com.example.examplemod.throwables.ThrowableSettingsService;
import com.example.examplemod.throwables.ThrowableKind;
import com.example.examplemod.throwables.ThrowablePayloads;
import com.example.examplemod.throwables.ThrowableRegistration;
import com.example.examplemod.vitals.PubgMedicineItem;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import me.xjqsh.lrtactical.client.renderer.item.ThrowableItemRendererWrapper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class ThrowableClient {
    private static final String CATEGORY = "key.categories.tacz_pubg";
    static final KeyMapping COOK = key("cook", GLFW.GLFW_KEY_R), CANCEL = key("cancel_use", GLFW.GLFW_KEY_X);
    static final KeyMapping THROWABLE = key("throwable", GLFW.GLFW_KEY_5), WHEEL = key("throwable_wheel", GLFW.GLFW_KEY_G);
    static final KeyMapping WHEEL_ALT = key("throwable_wheel_alt", GLFW.GLFW_KEY_F1);
    static final KeyMapping MED_WHEEL = key("medicine_wheel", GLFW.GLFW_KEY_GRAVE_ACCENT),
            MED_WHEEL_ALT = key("medicine_wheel_alt", GLFW.GLFW_KEY_F2);
    private static final KeyMapping MED = key("med_kit", GLFW.GLFW_KEY_7), AID = key("first_aid_kit", GLFW.GLFW_KEY_8),
            BANDAGE = key("bandage", GLFW.GLFW_KEY_9), BOOST = key("boost", GLFW.GLFW_KEY_0);
    private static final KeyMapping[] KEYS = {COOK, CANCEL, THROWABLE, WHEEL, WHEEL_ALT, MED_WHEEL, MED_WHEEL_ALT, MED, AID, BANDAGE, BOOST};
    private static final KeyMapping[] WHEEL_KEYS = {WHEEL, WHEEL_ALT, MED_WHEEL, MED_WHEEL_ALT};
    private static int fuse = -1, quickTicks, quickSlot = -1;
    private static boolean quickStarted;
    private static boolean cookedHeld, cookedReleaseSent;
    private static boolean wasSwimming;
    private static ItemStack cookedVisual = ItemStack.EMPTY;
    private static ItemStack animationVisual = ItemStack.EMPTY;
    private static int animationTicks, animationSlot = -1;
    private static final RadialWheelInput<KeyMapping> WHEEL_INPUT = new RadialWheelInput<>();
    private ThrowableClient() {}
    private static KeyMapping key(String id, int code) { return new KeyMapping("key.tacz_pubg." + id, code, CATEGORY); }
    public static void register(IEventBus modBus) {
        ThrowableTrajectoryClient.register();
        com.example.examplemod.hud.client.HudLayoutClient.registerPreview("throwable_status", ThrowableClient::fuseBounds,
                (graphics, rectangle, selected) -> drawFuse(graphics, rectangle, .65));
        modBus.addListener((RegisterKeyMappingsEvent event) -> { for (var key : KEYS) event.register(key); });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            event.registerEntityRenderer(ThrowableRegistration.THROWABLE.get(), ThrowableRenderer::new);
            event.registerEntityRenderer(ThrowableRegistration.SMOKE.get(), context ->
                    new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(context, .75f, false));
        });
        modBus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            private ThrowableItemRendererWrapper renderer;
            @Override public ThrowableItemRendererWrapper getCustomRenderer() {
                if (renderer == null) renderer = new ThrowableItemRendererWrapper() {
                    @Override public void tryExit(ItemStack stack, long time) {
                        var player = Minecraft.getInstance().player;
                        // SimpleBedrockModel observes the paid last grenade becoming empty before
                        // RenderHandEvent. Escrow is still held, so it must not start put_away.
                        if (player != null && player.getInventory().selected == animationSlot
                                && player.getMainHandItem().isEmpty() && cookedHeld && !cookedReleaseSent
                                && cookedVisual.getItem() == stack.getItem()) return;
                        super.tryExit(stack, time);
                    }
                    @Override public void updateContext(me.xjqsh.lrtactical.api.animation.ThrowableAnimationStateContext context,
                            ItemStack stack, net.minecraft.world.entity.player.Player player, float partialTick) {
                        super.updateContext(context, stack, player, partialTick);
                        if (cookedHeld && !cookedReleaseSent && cookedVisual.getItem() == stack.getItem()) {
                            context.setUsing(true);
                            context.setUsingTick(Math.max(context.getPrepareTime(), player.getTicksUsingItem()));
                        }
                    }
                    @Override public long getPutAwayTime(ItemStack stack) {
                        return stack.getItem() instanceof me.xjqsh.lrtactical.api.item.IThrowable item
                                ? item.getThrowableIndex(stack).map(index -> index.getData().getPutAwayTime()).orElse(0L)
                                : super.getPutAwayTime(stack);
                    }
                };
                return renderer;
            }
        }, ThrowableRegistration.ITEMS.values().stream().map(holder -> holder.get()).toArray(net.minecraft.world.item.Item[]::new)));
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "throwable_effects"), (graphics, delta) -> render(graphics)));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ThrowableClient::renderCommittedHand);
        ThrowablePayloads.setClientHandler(packet -> {
            if (packet.fuse() != -2) {
                if (packet.fuse() < 0 && cookedHeld && cookedReleaseSent && !cookedVisual.isEmpty()) {
                    animationVisual = cookedVisual.copy(); animationTicks = 20;
                }
                fuse = packet.fuse();
                cookedHeld = fuse >= 0;
                if (!cookedHeld) { cookedReleaseSent = false; cookedVisual = ItemStack.EMPTY; }
            }
        });
        ThrowablePayloads.setQuickUseClientHandler(packet -> {
            startAutomaticMedicineUse(packet.slot(), packet.duration(), true);
        });
    }
    private static void clear() {
        ThrowableTrajectoryClient.clear();
        fuse = -1;
        wasSwimming = false;
        cookedHeld = cookedReleaseSent = false; cookedVisual = ItemStack.EMPTY;
        animationVisual = ItemStack.EMPTY; animationTicks = 0; animationSlot = -1;
        cancelQuick(); WHEEL_INPUT.clear();
    }
    /** Capture item controls before vanilla F1/F2 and numbered-hotbar actions run. */
    public static boolean captureKey(long window, int key, int scan, int action, int modifiers) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()
                || window != mc.getWindow().getWindow()) return false;
        releaseWheelTrigger();
        if (mc.screen instanceof ThrowableWheelScreen screen) {
            for (KeyMapping mapping : KEYS) if (mapping.matches(key, scan)) {
                mapping.setDown(action != GLFW.GLFW_RELEASE);
                if (action == GLFW.GLFW_RELEASE) screen.keyReleased(key, scan, modifiers);
                return true;
            }
            return false;
        }
        if (mc.screen != null) return false;
        for (KeyMapping mapping : KEYS) if (mapping.matches(key, scan)) {
            if (mapping == COOK && !mapping.isDown() && !(mc.player.isUsingItem()
                    && mc.player.getUseItem().getItem() instanceof PubgThrowableItem)) return false;
            if (mapping == CANCEL && !mapping.isDown() && !mc.player.isUsingItem() && !cookedHeld
                    && !com.example.examplemod.vitals.client.MedicineClient.pending()) return false;
            mapping.setDown(action != GLFW.GLFW_RELEASE);
            if (action == GLFW.GLFW_PRESS) {
                if (BindingInput.freshPress(mapping, BindingInput.keyboardId(key, scan))) WHEEL_INPUT.pressed(mapping);
                if (mapping == COOK) requestCook();
                else if (mapping == CANCEL) requestCancel();
                else if (mapping == THROWABLE) sendQuick("throwable");
                else if (mapping == MED) sendQuick("med_kit");
                else if (mapping == AID) sendQuick("first_aid_kit");
                else if (mapping == BANDAGE) sendQuick("bandage");
                else if (mapping == BOOST) sendQuick("boost");
            }
            return true;
        }
        return false;
    }

    /** Wheel movement uses native event order without enabling MouseHandler's camera capture. */
    public static boolean captureWheelMouseMovement(long window, double dx, double dy) {
        var mc = Minecraft.getInstance();
        if (window == mc.getWindow().getWindow() && mc.screen instanceof ThrowableWheelScreen screen) {
            screen.movePointer(dx, dy);
            return true;
        }
        return false;
    }

    public static void captureWheelMouseButton(long window, int button, int action) {
        if (window != Minecraft.getInstance().getWindow().getWindow()) return;
        releaseWheelTrigger();
        if (action == GLFW.GLFW_PRESS) {
            for (var mapping : WHEEL_KEYS) {
                if (mapping.matchesMouse(button) && BindingInput.freshPress(mapping, BindingInput.mouseId(button))) WHEEL_INPUT.pressed(mapping);
            }
        }
    }

    static void releaseWheelTrigger() {
        if (WHEEL_INPUT.release(BindingInput::pollHeld)
                && Minecraft.getInstance().screen instanceof ThrowableWheelScreen screen) screen.confirmRelease();
    }

    static void wheelClosed() { WHEEL_INPUT.cancel(); }
    private static void requestCook() {
        var mc = Minecraft.getInstance();
        if (mc.player != null && SwimmingGunRestrictions.blocked(mc.player)) return;
        if (mc.player != null && mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && mc.player.getUseItem().getItem() instanceof PubgThrowableItem) {
            cookedVisual = mc.player.getUseItem().copyWithCount(1);
            animationSlot = mc.player.getInventory().selected;
            PacketDistributor.sendToServer(new ThrowablePayloads.Action(0));
        }
    }
    private static void requestCancel() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        com.example.examplemod.vitals.client.MedicineClient.cancelPending();
        cancelQuick(); PacketDistributor.sendToServer(new ThrowablePayloads.Action(1));
        cookedReleaseSent = true;
        mc.player.stopUsingItem();
    }
    private static void cancelQuick() {
        quickTicks = 0; quickSlot = -1; quickStarted = false;
    }
    public static boolean sustainingMedicineUse() {
        var mc = Minecraft.getInstance();
        return quickTicks > 0 && mc.player != null && mc.screen == null && mc.player.isAlive() && !mc.player.isSpectator()
                && !com.example.examplemod.combat.client.CombatInputClient.blocked()
                && mc.player.getInventory().selected == quickSlot && quickStarted && mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && mc.player.getUseItem() == mc.player.getMainHandItem()
                && mc.player.getMainHandItem().getItem() instanceof PubgMedicineItem;
    }
    public static boolean startAutomaticMedicineUse(int slot, int duration, boolean selectSlot) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !mc.player.isAlive() || mc.player.isSpectator()
                || com.example.examplemod.combat.client.CombatInputClient.blocked()) return false;
        if (selectSlot) mc.player.getInventory().selected = slot;
        if (mc.player.getInventory().selected != slot || !(mc.player.getMainHandItem().getItem() instanceof PubgMedicineItem)) return false;
        if (!mc.player.isUsingItem()) mc.player.startUsingItem(InteractionHand.MAIN_HAND);
        if (!mc.player.isUsingItem() || mc.player.getUsedItemHand() != InteractionHand.MAIN_HAND
                || mc.player.getUseItem() != mc.player.getMainHandItem()) return false;
        quickTicks = duration + 5; quickSlot = slot; quickStarted = true;
        return true;
    }
    public static void sendQuick(String action) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.isAlive() && !player.isSpectator())
            PacketDistributor.sendToServer(new ThrowablePayloads.QuickAction(action));
    }
    record Aim(ThrowableKind kind, int fuse) {}
    /** The physical release hides the preview immediately, including the escrowed last grenade. */
    static Aim trajectoryAim() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen != null
                || mc.options.hideGui || !mc.options.keyUse.isDown() || MatchVitalsHud.paused()) return null;
        if (SwimmingGunRestrictions.blocked(mc.player)) return null;
        if (cookedHeld && !cookedReleaseSent && cookedVisual.getItem() instanceof PubgThrowableItem item)
            return new Aim(item.kind, Math.max(1, fuse));
        if (mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof PubgThrowableItem item)
            return new Aim(item.kind, ThrowableSettingsService.profile(item.kind).fuseTicks());
        return null;
    }
    private static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { clear(); return; }
        // Keep a cancelled wheel closed until its trigger is physically released.
        releaseWheelTrigger();
        if (quickTicks > 0 && mc.screen != null) requestCancel();
        if (mc.isPaused() || MatchVitalsHud.paused()) return;
        cancelSwimmingThrowable();
        if (fuse > 0) fuse--;
        if (animationTicks > 0 && --animationTicks == 0) animationVisual = ItemStack.EMPTY;
        if (!mc.player.isAlive() || mc.player.isSpectator()) cancelQuick();
        if (cookedHeld && !cookedReleaseSent && (mc.screen != null || !mc.options.keyUse.isDown())) {
            cookedReleaseSent = true;
            PacketDistributor.sendToServer(new ThrowablePayloads.Action(mc.screen == null ? 2 : 1));
        }
        if (quickTicks > 0) {
            if (mc.screen != null || mc.player.getInventory().selected != quickSlot
                    || !(mc.player.getMainHandItem().getItem() instanceof PubgMedicineItem)
                    || quickStarted && !mc.player.isUsingItem()) cancelQuick();
            else {
                if (--quickTicks == 0) quickSlot = -1;
            }
        }
        if (mc.screen != null) return;
        while (COOK.consumeClick()) requestCook();
        while (CANCEL.consumeClick()) if (mc.player.isUsingItem() || cookedHeld) requestCancel();
        boolean active = mc.player.isAlive() && !mc.player.isSpectator();
        if (!active) {
            while (THROWABLE.consumeClick()) {}
            while (MED.consumeClick()) {} while (AID.consumeClick()) {}
            while (BANDAGE.consumeClick()) {} while (BOOST.consumeClick()) {}
            WHEEL_INPUT.clear(); return;
        }
        while (THROWABLE.consumeClick()) sendQuick("throwable");
        while (MED.consumeClick()) sendQuick("med_kit");
        while (AID.consumeClick()) sendQuick("first_aid_kit");
        while (BANDAGE.consumeClick()) sendQuick("bandage");
        while (BOOST.consumeClick()) sendQuick("boost");
        if (!mc.isWindowActive() || mc.getOverlay() != null || WHEEL_INPUT.blocked()) return;
        KeyMapping trigger = wheelDown(WHEEL) ? WHEEL : wheelDown(WHEEL_ALT) ? WHEEL_ALT
                : wheelDown(MED_WHEEL) ? MED_WHEEL : wheelDown(MED_WHEEL_ALT) ? MED_WHEEL_ALT : null;
        if (trigger != null && WHEEL_INPUT.tryOpen(trigger)) {
            cancelQuick();
            mc.setScreen(new ThrowableWheelScreen(trigger == MED_WHEEL || trigger == MED_WHEEL_ALT, trigger));
        }
    }
    private static boolean wheelDown(KeyMapping mapping) {
        return mapping.isDown() && BindingInput.pollHeld(mapping);
    }

    private static void cancelSwimmingThrowable() {
        var mc = Minecraft.getInstance();
        boolean swimming = SwimmingGunRestrictions.blocked(mc.player);
        boolean using = mc.player.isUsingItem() && SwimmingGunRestrictions.throwable(mc.player.getUseItem());
        if (swimming && (!wasSwimming || using || cookedHeld)) {
            ItemStack visual = !cookedVisual.isEmpty() ? cookedVisual.copy()
                    : using ? mc.player.getUseItem().copy() : mc.player.getMainHandItem().copy();
            if (cookedHeld || using && mc.player.getUseItem().getItem() instanceof PubgThrowableItem)
                PacketDistributor.sendToServer(new ThrowablePayloads.Action(1));
            if (using) mc.player.stopUsingItem();
            // An automatic drop must not leave the escrow hand or a delayed throw animation.
            cookedHeld = cookedReleaseSent = false;
            cookedVisual = animationVisual = ItemStack.EMPTY;
            animationTicks = 0;
            fuse = -1;
            if (SwimmingGunRestrictions.throwable(visual)
                    && IClientItemExtensions.of(visual.getItem()).getCustomRenderer() instanceof ThrowableItemRendererWrapper renderer) {
                renderer.tryInit(visual, mc.player, 0);
            }
        }
        wasSwimming = swimming;
    }
    private static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        PubgThrowableItem item = mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof PubgThrowableItem throwable
                ? throwable : null;
        if (mc.options.hideGui || item == null && !cookedHeld) return;
        if (MatchVitalsHud.active() && cookedHeld && fuse >= 0) {
            var rectangle = fuseBounds(graphics.guiWidth(), graphics.guiHeight());
            ThrowableKind kind = cookedVisual.getItem() instanceof PubgThrowableItem visual ? visual.kind : item != null ? item.kind : ThrowableKind.FRAG;
            double total = ThrowableSettingsService.profile(kind).cookedFuseTicks();
            try (var ignored = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "throwable_status",
                    rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height())) {
                drawFuse(graphics, rectangle, Math.clamp(fuse / Math.max(1, total), 0, 1));
            }
            return;
        }
        if (MatchVitalsHud.active() && (item == null || item.kind != ThrowableKind.C4)) return;
        int top = Math.min(graphics.guiHeight() - 105, graphics.guiHeight() / 2 + 35);
        String seconds = String.format(Locale.ROOT, "%.1f", Math.max(0, fuse) / 20.0);
        var label = item != null && item.kind == ThrowableKind.C4
                ? Texts.of("screen.tacz_pubg.throwable.deploying", String.format(Locale.ROOT, "%.1f", mc.player.getUseItemRemainingTicks() / 20.0))
                : fuse >= 0 ? Texts.of("screen.tacz_pubg.throwable.fuse", seconds)
                : Texts.of("screen.tacz_pubg.throwable.ready", mc.player.isShiftKeyDown()
                    ? Texts.of("screen.tacz_pubg.throwable.underhand") : Texts.of("screen.tacz_pubg.throwable.overhand"));
        var controls = Texts.of("screen.tacz_pubg.throwable.controls", COOK.getTranslatedKeyMessage(), CANCEL.getTranslatedKeyMessage());
        int width = Math.max(mc.font.width(label), mc.font.width(controls));
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "throwable_status",
                (graphics.guiWidth() - width) / 2, top - 22, width, 45)) {
        graphics.drawCenteredString(mc.font, label, graphics.guiWidth() / 2, top, fuse >= 0 ? 0xFFFFA060 : 0xFFF4E9C7);
        graphics.drawCenteredString(mc.font, controls, graphics.guiWidth() / 2, top + 13, 0xFFCCCCCC);
        if (cookedHeld && !cookedVisual.isEmpty()) graphics.renderItem(cookedVisual, graphics.guiWidth() / 2 - 8, top - 22);
        }
    }
    private static com.example.examplemod.hud.client.HudLayoutModel.Rect fuseBounds(int width, int height) {
        var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
        int radius = settings.integer("hud.fuse_radius");
        return new com.example.examplemod.hud.client.HudLayoutModel.Rect(width / 2 + settings.integer("hud.fuse_offset_x") - radius,
                height / 2 - radius, radius * 2 + 1, radius * 2 + 1);
    }
    private static void drawFuse(GuiGraphics graphics, com.example.examplemod.hud.client.HudLayoutModel.Rect rectangle, double remaining) {
        var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
        double radius = settings.integer("hud.fuse_radius"), line = settings.number("hud.fuse_line_width");
        double cx = rectangle.x() + radius, cy = rectangle.y() + radius;
        com.example.examplemod.vitals.client.CombatHudDraw.arc(graphics, cx, cy, radius, line, 0, 180, settings.color("hud.fuse_track_color"));
        com.example.examplemod.vitals.client.CombatHudDraw.arc(graphics, cx, cy, radius, line, 0, remaining * 180,
                settings.color(remaining <= settings.number("hud.fuse_warning_fraction") ? "hud.fuse_warning_color" : "hud.fuse_color"));
    }
    /** Keep the paid last grenade visible through LR's hold/throw animation without restoring an item. */
    private static void renderCommittedHand(RenderHandEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen != null
                || mc.player.getInventory().selected != animationSlot || !mc.player.getMainHandItem().isEmpty()) return;
        ItemStack visual = cookedHeld ? cookedVisual : animationTicks > 0 ? animationVisual : ItemStack.EMPTY;
        if (visual.isEmpty()) return;
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (IClientItemExtensions.of(visual.getItem()).getCustomRenderer() instanceof ThrowableItemRendererWrapper renderer) {
            if (renderer.needReInit(visual)) renderer.tryInit(visual, mc.player, event.getPartialTick());
            renderer.renderFirstPerson(mc.player, visual,
                    mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT
                            ? net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                            : net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                    event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), event.getPartialTick());
        }
    }
}
