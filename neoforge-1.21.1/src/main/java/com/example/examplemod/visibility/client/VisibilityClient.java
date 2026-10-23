package com.example.examplemod.visibility.client;

import com.example.examplemod.visibility.VisibilityPayloads;
import com.example.examplemod.visibility.VisibilityPolicy;
import com.tacz.guns.block.TargetBlock;
import com.tacz.guns.block.entity.TargetBlockEntity;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Connection checked state; far-target replicas are visual objects, never placed into the client world. */
public final class VisibilityClient {
    record Replica(TargetBlockEntity target, int packedLight) {}
    private static VisibilityPolicy.Settings settings = VisibilityPolicy.Settings.defaults();
    private static final Map<BlockPos, Replica> targets = new LinkedHashMap<>();
    private static ResourceLocation dimension;
    private static long revision = -1, sequence = -1, ticks, snapshotAt;
    private static int omitted;
    private static boolean registered;
    private VisibilityClient() {}
    public static VisibilityPolicy.Settings settings() { return settings; }
    public static int omittedTargets() { return omitted; }
    static Map<BlockPos, Replica> targets() { return targets; }
    public static void register() {
        if (registered) return;
        registered = true;
        VisibilityPayloads.setClientHandlers((packet, connection) -> {
            if (!current(connection)) return;
            if (packet.revision() >= revision) { settings = packet.settings(); revision = packet.revision(); }
            if (Minecraft.getInstance().screen instanceof VisibilityConfigScreen screen) screen.accept(packet);
        }, VisibilityClient::acceptTargets);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (!mc.isPaused()) ticks++;
            if (!mc.level.dimension().location().equals(dimension) || ticks - snapshotAt > 60) targets.clear();
            if (!mc.isPaused()) for (var replica : targets.values())
                TargetBlockEntity.clientTick(mc.level, replica.target.getBlockPos(), replica.target.getBlockState(), replica.target);
        });
        FarTargetRenderer.register();
    }
    private static boolean current(Connection connection) {
        var listener = Minecraft.getInstance().getConnection();
        return listener != null && listener.getConnection() == connection;
    }
    private static void acceptTargets(VisibilityPayloads.Targets packet, Connection connection) {
        var mc = Minecraft.getInstance();
        if (!current(connection) || mc.level == null || !mc.level.dimension().location().equals(packet.dimension())) return;
        if (packet.sequence() <= sequence && packet.dimension().equals(dimension)) return;
        var next = new LinkedHashMap<BlockPos, Replica>();
        for (var snapshot : packet.targets()) {
            var state = Block.stateById(snapshot.stateId());
            if (!(state.getBlock() instanceof TargetBlock)) continue;
            if (mc.player != null && !VisibilityPolicy.inRange(snapshot.pos().getCenter().distanceToSqr(mc.player.position()), settings.targets() + 2)) continue;
            try {
                var old = targets.get(snapshot.pos());
                var target = new TargetBlockEntity(snapshot.pos(), state);
                target.setLevel(mc.level);
                if (old != null) { target.rot = old.target.rot; target.oRot = old.target.oRot; }
                else target.rot = target.oRot = state.getValue(TargetBlock.STAND) ? 0 : 90;
                var nbt = NbtIo.read(new DataInputStream(new ByteArrayInputStream(snapshot.nbt())), NbtAccounter.create(8192));
                target.loadAdditional(nbt, mc.level.registryAccess());
                next.put(snapshot.pos(), new Replica(target, snapshot.packedLight()));
            } catch (Exception ignored) { /* Keep malformed or oversized replicas out of the render loop. */ }
        }
        targets.clear(); targets.putAll(next);
        dimension = packet.dimension(); sequence = packet.sequence(); snapshotAt = ticks; omitted = packet.omitted();
    }
    private static void clear() {
        FarTargetRenderer.clear();
        settings = VisibilityPolicy.Settings.defaults(); targets.clear(); dimension = null;
        revision = -1; sequence = -1; ticks = snapshotAt = 0; omitted = 0;
    }
}
