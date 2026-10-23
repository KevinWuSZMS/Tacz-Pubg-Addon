package com.example.examplemod.operatorpermissions;

import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.operatorpermissions.OperatorPermissions.Permission;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.EnumMap;
import java.util.Map;

/** JSON is the single live value; game rules edit that same revisioned world configuration. */
public final class OperatorPermissionsService {
    private static final Map<Permission, GameRules.Key<GameRules.BooleanValue>> RULES = new EnumMap<>(Permission.class);
    private static boolean registered;

    private OperatorPermissionsService() {}

    /** Run during mod construction, before any world creates its GameRules instance. */
    public static synchronized void register(IEventBus bus) {
        if (registered) return;
        registered = true;
        for (Permission permission : Permission.values()) {
            RULES.put(permission, GameRules.register(permission.gameRule(), GameRules.Category.PLAYER,
                    GameRules.BooleanValue.create(OperatorPermissions.defaults().value(permission),
                            (server, value) -> gameRuleChanged(server, permission, value.get()))));
        }
        bus.addListener(OperatorPermissionsService::onServerStarted);
    }

    public static OperatorPermissions current(MinecraftServer server) {
        return LootConfigStore.get(server.overworld()).document().general().operatorPermissions();
    }

    /** First world configuration honors choices made in Minecraft's Create World game-rule editor. */
    public static OperatorPermissions captureGameRules(MinecraftServer server) {
        OperatorPermissions result = OperatorPermissions.defaults();
        for (Permission permission : Permission.values())
            result = result.with(permission, server.getGameRules().getBoolean(RULES.get(permission)));
        return result;
    }

    public static boolean operator(ServerPlayer player) { return player.hasPermissions(2); }
    public static boolean enabled(ServerPlayer player, Permission permission) {
        return operator(player) && current(player.server).value(permission);
    }

    public static void applyToGameRules(MinecraftServer server, OperatorPermissions permissions) {
        // null deliberately skips listeners. Configuration save must not recursively perform six more saves.
        for (Permission permission : Permission.values())
            server.getGameRules().getRule(RULES.get(permission)).set(permissions.value(permission), null);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        applyToGameRules(event.getServer(), current(event.getServer()));
    }

    private static void gameRuleChanged(MinecraftServer server, Permission permission, boolean value) {
        if (server == null) return;
        LootConfigStore store = LootConfigStore.get(server.overworld());
        var result = store.saveOperatorPermissions(server.overworld(), store.document().general().operatorPermissions().with(permission, value));
        if (!result.success()) {
            applyToGameRules(server, store.document().general().operatorPermissions());
            LogUtils.getLogger().error("Unable to update PUBG operator game rule {}: {}", permission.gameRule(), result.message());
            // Do not let /gamerule report success when the durable configuration refused the change.
            throw new IllegalStateException(result.message());
        }
    }
}
