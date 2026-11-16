package com.example.examplemod.lean.mixin;

import com.example.examplemod.lean.client.LeanShotClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.tacz.guns.client.gameplay.LocalPlayerShoot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LocalPlayerShoot.class, remap = false)
public abstract class LocalPlayerShotMixin {
    @Shadow @Final private LocalPlayer player;

    @WrapOperation(method = "doShoot", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ScheduledExecutorService;scheduleAtFixedRate(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;"), remap = false)
    private ScheduledFuture<?> taczPubg$captureScheduledShot(ScheduledExecutorService scheduler, Runnable task,
            long delay, long period, TimeUnit unit, Operation<ScheduledFuture<?>> original,
            @Local(argsOnly = true) ItemStack expectedGun) {
        return original.call(scheduler, LeanShotClient.scheduled(player, expectedGun, task), delay, period, unit);
    }

    @WrapOperation(method = "lambda$doShoot$2", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToServer(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"), remap = false)
    private void taczPubg$deferNativeShot(CustomPacketPayload payload, CustomPacketPayload[] additional,
            Operation<Void> original) {
        long timestamp = ((ClientMessageShotAccessor) payload).taczPubg$shotTimestamp();
        LeanShotClient.defer(timestamp, () -> original.call(payload, additional));
    }

    @WrapOperation(method = "lambda$doShoot$2", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;submitAsync(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"), remap = false)
    private CompletableFuture<Void> taczPubg$dispatchRound(Minecraft minecraft, Runnable nativeAnimation,
            Operation<CompletableFuture<Void>> original, @Local(argsOnly = true) AtomicInteger count) {
        return original.call(minecraft, LeanShotClient.roundTask(player, count.get(), nativeAnimation));
    }
}
