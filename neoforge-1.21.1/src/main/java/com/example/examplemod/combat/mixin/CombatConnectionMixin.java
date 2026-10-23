package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.CombatService;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Malformed/direct packets cannot bypass the downed interaction lock. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CombatConnectionMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="handleContainerClick",at=@At("HEAD"),cancellable=true)
    private void taczPubg$blockedInventory(ServerboundContainerClickPacket packet,CallbackInfo ci) {
        if(!player.server.isSameThread())return;
        if(CombatService.actionBlocked(player)){player.containerMenu.sendAllDataToRemote();ci.cancel();}
    }
    @Inject(method="handleInteract",at=@At("HEAD"),cancellable=true)
    private void taczPubg$blockedEntity(ServerboundInteractPacket packet,CallbackInfo ci) {if(player.server.isSameThread()&&CombatService.actionBlocked(player))ci.cancel();}
    @Inject(method="handlePlayerAction",at=@At("HEAD"),cancellable=true)
    private void taczPubg$blockedAction(ServerboundPlayerActionPacket packet,CallbackInfo ci) {
        if(!player.server.isSameThread())return;
        if(packet.getAction()!=ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM&&CombatService.actionBlocked(player))ci.cancel();
    }
    @Inject(method="handleSetCarriedItem",at=@At("HEAD"),cancellable=true)
    private void taczPubg$blockedSlot(ServerboundSetCarriedItemPacket packet,CallbackInfo ci) {
        if(!player.server.isSameThread())return;
        if(CombatService.downed(player)){player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(player.getInventory().selected));ci.cancel();}
    }
}
