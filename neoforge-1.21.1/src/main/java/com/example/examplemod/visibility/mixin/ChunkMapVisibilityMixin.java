package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityTrackerAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapVisibilityMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Int2ObjectMap<?> entityMap;
    @Inject(method = "tick", at = @At("TAIL"))
    private void pubg$refreshStationaryObservers(CallbackInfo callback) {
        if (level.getServer().getTickCount() % 20 != 0) return;
        for (var tracker : entityMap.values())
            if (tracker instanceof VisibilityTrackerAccess access) access.pubg$refreshVisibility(level.players());
    }
}
