package com.example.examplemod.movement.client.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Player.class)
public interface MovementPlayerAccess {
    @Accessor("jumpTriggerTime") void taczPubg$setJumpTriggerTime(int value);
}
