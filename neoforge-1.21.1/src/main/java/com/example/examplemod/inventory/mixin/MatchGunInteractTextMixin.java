package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.MatchGunInteractionClient;
import com.example.examplemod.hud.client.InteractionPromptHud;
import com.example.examplemod.localization.Texts;
import com.tacz.guns.client.gui.overlay.InteractKeyTextOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native block/entity hints always show the live pickup binding, including user rebinds. */
@Mixin(value = InteractKeyTextOverlay.class, remap = false)
abstract class MatchGunInteractTextMixin {
    @Redirect(method = {"renderBlockText", "renderEntityText"}, at = @At(value = "FIELD",
            target = "Lcom/tacz/guns/client/input/InteractKey;INTERACT_KEY:Lnet/minecraft/client/KeyMapping;",
            opcode = Opcodes.GETSTATIC, remap = false), remap = false)
    private static KeyMapping taczPubg$showPickupBinding() {
        return MatchGunInteractionClient.displayedBinding();
    }

    /** Keep TaCZ's target/filter decisions while sharing the medicine cancel-key anchor. */
    @Inject(method = "renderText", at = @At("HEAD"), cancellable = true, remap = false)
    private static void taczPubg$alignInteractionPrompt(GuiGraphics graphics, int width, int height,
            Font font, String keyName, boolean willFilterByHand, CallbackInfo ci) {
        if (!MatchGunInteractionClient.active()) return;
        if (InteractionPromptHud.worldInteractionVisible() && !com.example.examplemod.lootspawn.client.GroundLootClient.hasVisibleTarget())
            InteractionPromptHud.render(graphics, Component.literal(keyName), Texts.of("gui.tacz_pubg.interact_action"),
                willFilterByHand ? Component.translatable("gui.tacz.interact_key.text.gun_smith_table_filter") : Component.empty());
        ci.cancel();
    }
}
