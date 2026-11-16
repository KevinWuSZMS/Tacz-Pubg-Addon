package com.example.examplemod.keybindings.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccess {
    @Accessor("clickCount") int taczPubg$getClicks();
    @Accessor("clickCount") void taczPubg$setClicks(int value);
    @Accessor("isDown") void taczPubg$setRawDown(boolean value);
}
