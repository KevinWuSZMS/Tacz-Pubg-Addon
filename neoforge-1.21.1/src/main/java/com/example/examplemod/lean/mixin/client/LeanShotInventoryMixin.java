package com.example.examplemod.lean.mixin.client;

import com.example.examplemod.lean.client.LeanShotClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Track explicit local equip actions; incoming server ammo updates deliberately do not invalidate shots. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class LeanShotInventoryMixin {
    @Inject(method = "send", at = @At("HEAD"))
    private void taczPubg$invalidateQueuedEquip(Packet<?> packet, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().getConnection()) LeanShotClient.inventoryAction(packet);
    }
}
