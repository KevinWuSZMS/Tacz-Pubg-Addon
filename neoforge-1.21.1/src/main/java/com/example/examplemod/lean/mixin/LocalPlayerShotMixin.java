package com.example.examplemod.lean.mixin;

import com.example.examplemod.lean.client.LeanShotClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.tacz.guns.client.gameplay.LocalPlayerShoot;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LocalPlayerShoot.class, remap = false)
public abstract class LocalPlayerShotMixin {
    @Shadow @Final private LocalPlayer player;
    @Unique private final Map<AtomicInteger, Long> taczPubg$shotGroups = new ConcurrentHashMap<>();

    @WrapOperation(method = "lambda$doShoot$2", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToServer(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"), remap = false)
    private void taczPubg$sendPoseBeforeNativeShot(CustomPacketPayload payload, CustomPacketPayload[] additional,
            Operation<Void> original, @Local(argsOnly = true) AtomicInteger count,
            @Local(argsOnly = true) ItemStack expectedGun) {
        long timestamp = ((ClientMessageShotAccessor) payload).taczPubg$shotTimestamp();
        // Concurrent burst tasks have distinct counters; never identify a round using the latest global timestamp.
        if (taczPubg$shotGroups.size() >= 64) taczPubg$shotGroups.clear();
        taczPubg$shotGroups.put(count, timestamp);
        LeanShotClient.first(player, expectedGun, timestamp, () -> original.call(payload, additional));
    }

    @WrapOperation(method = "lambda$doShoot$2", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;submitAsync(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"), remap = false)
    private CompletableFuture<Void> taczPubg$sampleEachClientRound(Minecraft minecraft, Runnable nativeAnimation,
            Operation<CompletableFuture<Void>> original, @Local(argsOnly = true) AtomicInteger count,
            @Local(argsOnly = true) int maximumCount, @Local(argsOnly = true) ItemStack expectedGun) {
        int ordinal = count.get();
        Long timestamp = taczPubg$shotGroups.get(count);
        if (ordinal + 1 >= maximumCount) taczPubg$shotGroups.remove(count);
        return original.call(minecraft, (Runnable) () -> {
            if (!LeanShotClient.valid(player, expectedGun)) return;
            if (ordinal > 0 && timestamp != null) LeanShotClient.round(player, timestamp, ordinal);
            nativeAnimation.run();
        });
    }
}
