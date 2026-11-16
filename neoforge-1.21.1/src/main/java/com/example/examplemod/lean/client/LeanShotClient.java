package com.example.examplemod.lean.client;

import com.example.examplemod.lean.LeanService;
import com.example.examplemod.lean.LeanShotPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Capture player/lean state exclusively on the client game thread, never TaCZ's sound scheduler. */
public final class LeanShotClient {
    private LeanShotClient() {}

    public static boolean valid(LocalPlayer player, ItemStack expectedGun) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == player && minecraft.getConnection() != null && player.isAlive()
                && !minecraft.isPaused() && player.getMainHandItem() == expectedGun;
    }

    public static void first(LocalPlayer player, ItemStack expectedGun, long timestamp, Runnable nativeSend) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            // The native packet contains no gun identity. A queued old-gun shot must not fire the new held gun.
            if (!valid(player, expectedGun)) return;
            round(player, timestamp, 0);
            nativeSend.run();
        });
    }

    public static void round(LocalPlayer player, long timestamp, int ordinal) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) throw new IllegalStateException("Shot pose must be sampled on the client thread");
        if (minecraft.player != player || minecraft.getConnection() == null || !player.isAlive()) return;
        PacketDistributor.sendToServer(new LeanShotPayloads.Pose(timestamp, ordinal,
                LeanService.inputSequence(player), LeanService.inputAgeSeconds(player), player.getXRot(), Mth.wrapDegrees(player.getYRot())));
    }
}
