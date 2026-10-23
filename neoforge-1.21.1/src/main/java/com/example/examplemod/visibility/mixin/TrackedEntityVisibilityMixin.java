package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityEntities;
import com.example.examplemod.visibility.VisibilityPolicy;
import com.example.examplemod.visibility.VisibilityService;
import com.example.examplemod.visibility.VisibilityTrackerAccess;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Changes only native pairing eligibility; movement/metadata/equipment/removal remain native. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityVisibilityMixin implements VisibilityTrackerAccess {
    @Shadow @Final private Entity entity;
    @Shadow @Final private ServerEntity serverEntity;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Shadow public abstract void updatePlayer(ServerPlayer player);
    @Unique private boolean pubg$wasManaged;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void pubg$distance(ServerPlayer player, CallbackInfo callback) {
        if (!VisibilityEntities.managed(entity)) return;
        pubg$wasManaged = true;
        callback.cancel();
        if (player == entity) return;
        int distance = VisibilityEntities.distance(entity, VisibilityService.settings(player.server));
        boolean eligible = player.level() == entity.level() && !entity.isRemoved()
                && VisibilityPolicy.inRange(player.distanceToSqr(entity), distance) && entity.broadcastToPlayer(player);
        if (eligible) {
            if (seenBy.add(player.connection)) serverEntity.addPairing(player);
        } else if (seenBy.remove(player.connection)) serverEntity.removePairing(player);
    }
    @Override public void pubg$refreshVisibility(List<ServerPlayer> players) {
        boolean managed = VisibilityEntities.managed(entity);
        if (managed || pubg$wasManaged) for (var player : players) updatePlayer(player);
        // The first refresh after dismount must run native eligibility for every observer.
        pubg$wasManaged = managed;
    }
}
