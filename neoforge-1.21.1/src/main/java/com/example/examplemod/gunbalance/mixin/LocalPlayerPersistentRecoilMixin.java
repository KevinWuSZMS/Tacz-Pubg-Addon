package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.client.PersistentRecoilClient;
import com.tacz.guns.client.gameplay.LocalPlayerShoot;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The r7 local success path is per projectile, including each Burst projectile. */
@Mixin(value = LocalPlayerShoot.class, remap = false)
public abstract class LocalPlayerPersistentRecoilMixin {
    @Redirect(method = "lambda$doShoot$2", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToServer(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"),
            remap = false)
    private void taczPubg$sendAimBeforeNextTrigger(CustomPacketPayload payload, CustomPacketPayload[] additional) {
        PersistentRecoilClient.sendShootWithAim(payload, additional);
    }

    @Redirect(method = "lambda$doShoot$1", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),
            remap = false)
    private Event taczPubg$commitOnlyUncancelledLocalShot(IEventBus eventBus, Event event) {
        return PersistentRecoilClient.postLocalFire(eventBus, event);
    }
}
