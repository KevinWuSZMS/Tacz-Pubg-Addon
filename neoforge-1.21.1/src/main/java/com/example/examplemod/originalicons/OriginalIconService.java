package com.example.examplemod.originalicons;

import com.example.examplemod.lootconfig.LootConfigStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Presentation-only setting: saving it never reloads gun data or changes item identities. */
public final class OriginalIconService {
    private static long revision;
    private OriginalIconService() {}
    public static void register(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(OriginalIconPayloads::register);
        gameBus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) send(player);
        });
        gameBus.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() != null) send(event.getPlayer());
            else sync(event.getPlayerList().getServer());
        });
        gameBus.addListener((ServerStoppedEvent event) -> revision = 0);
    }
    public static void sync(MinecraftServer server) {
        var state = state(server);
        for (var player : server.getPlayerList().getPlayers()) PacketDistributor.sendToPlayer(player, state);
    }
    private static void send(ServerPlayer player) { PacketDistributor.sendToPlayer(player, state(player.server)); }
    private static OriginalIconPayloads.State state(MinecraftServer server) {
        return new OriginalIconPayloads.State(++revision,
                LootConfigStore.current(server.overworld()).gunSettings().usePubgOriginalIcons(),
                LootConfigStore.current(server.overworld()).gunSettings().lowPolyModels());
    }
}
