package com.example.examplemod.lean.mixin;

import com.tacz.guns.network.message.ClientMessagePlayerShoot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ClientMessagePlayerShoot.class, remap = false)
public interface ClientMessageShotAccessor {
    @Accessor("timestamp") long taczPubg$shotTimestamp();
}
