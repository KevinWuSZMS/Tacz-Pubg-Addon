package com.example.examplemod.combat.client;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

@EventBusSubscriber(modid = "tacz_pubg", value = Dist.CLIENT)
public final class SwimmingCombatClient {
    private SwimmingCombatClient() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void input(InputEvent.InteractionKeyMappingTriggered event) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || !SwimmingGunRestrictions.blocked(player)) return;
        boolean attack = event.isAttack() && (minecraft.hitResult == null || minecraft.hitResult.getType() != HitResult.Type.BLOCK);
        boolean throwable = event.isUseItem() && SwimmingGunRestrictions.throwable(player.getItemInHand(event.getHand()));
        if (attack || throwable) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
}
