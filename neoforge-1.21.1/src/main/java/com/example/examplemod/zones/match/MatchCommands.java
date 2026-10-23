package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.commands.PubgSuggestions;
import com.example.examplemod.commands.MapCommandContext;
import com.example.examplemod.commands.PubgCommandHelp;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneSavedData;
import com.example.examplemod.zones.match.lobby.LobbySavedData;
import com.example.examplemod.zones.match.lobby.LobbyService;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** OP-only match controls; an explicit map ID also works from the server console. */
public final class MatchCommands {
    private MatchCommands() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(PubgCommandHelp.group("match")
                .then(command("start", MatchCommands::start))
                .then(command("end", MatchCommands::end))
                .then(command("info", MatchCommands::info))
                .then(command("pause", MatchCommands::pause))
                .then(command("resume", MatchCommands::resume)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> command(String name, Action action) {
        return Commands.literal(name).requires(source -> source.hasPermission(2))
                .executes(context -> execute(context.getSource(), 0, action))
                .then(Commands.argument("mapId", LongArgumentType.longArg(1)).suggests(PubgSuggestions.MAPS)
                        .executes(context -> execute(context.getSource(), LongArgumentType.getLong(context, "mapId"), action)));
    }

    private static int execute(CommandSourceStack source, long mapId, Action action) throws CommandSyntaxException {
        if (!source.hasPermission(2)) {
            source.sendFailure(Texts.of("message.tacz_pubg.match_commands.command_requires_op_permission_level"));
            return 0;
        }
        try {
            mapId = MapCommandContext.resolve(source, mapId).id();
            return action.run(source, mapId);
        } catch (IllegalArgumentException | IllegalStateException error) {
            source.sendFailure(Texts.component(error.getMessage() == null ? Texts.tr("message.tacz_pubg.match_commands.match_operation_failed") : error.getMessage()));
            return 0;
        }
    }

    private static int start(CommandSourceStack source, long mapId) {
        var rooms = LobbySavedData.get(source.getServer());
        if (!rooms.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_commands.restore_protected_lobby_data_first", rooms.loadError()));
        if (rooms.hasRoom(mapId)) {
            String result = LobbyService.start(source.getServer(), mapId, rooms.revision());
            source.sendSuccess(() -> Texts.of("message.tacz_pubg.map_reset_service.map", mapId, result), true); return 1;
        }
        String result = MatchService.start(source.getServer(), mapId);
        source.sendSuccess(() -> Texts.of("message.tacz_pubg.map_reset_service.map", mapId, result), true);
        return 1;
    }

    private static int end(CommandSourceStack source, long mapId) {
        String result = LobbyService.end(source.getServer(), mapId);
        source.sendSuccess(() -> Texts.of("message.tacz_pubg.map_reset_service.map", mapId, result), true);
        return 1;
    }

    private static int info(CommandSourceStack source, long mapId) {
        source.sendSuccess(() -> Texts.component(com.example.examplemod.backup.MapResetService.description(source.getServer(), mapId)), false);
        if (LobbyService.isPreparing(source.getServer(), mapId)) {
            var lobby = LobbyService.view(source.getServer(), mapId);
            source.sendSuccess(() -> Texts.of("message.tacz_pubg.match_commands.map_bots_ready", mapId, lobby.readyBots(), lobby.requestedBots(), lobby.message()), false); return 1;
        }
        MatchStatus status = MatchService.status(source.getServer(), mapId);
        if (status == null) {
            MapDefinition map = MapZoneSavedData.get(source.getServer()).map(mapId);
            if (map == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_does_not_exist", mapId));
            source.sendSuccess(() -> Texts.of("message.tacz_pubg.match_commands.map_lobby_preparation_showing_configuration_preview", mapId, map.name()), false);
            return 1;
        }
        long seconds = MatchService.elapsedTicks(source.getServer(), mapId) / 20;
        source.sendSuccess(() -> Texts.of("message.tacz_pubg.match_commands.map_uuid_elapsed_s_phase_s_remaining_alive", mapId, status.sessionId(), stageName(status.stage()), seconds, status.phase(), status.phaseCount(), status.secondsRemaining(), status.playersAlive(), status.playersTotal(), status.teamsAlive(), (status.result().isBlank() ? "" : Texts.tr("screen.tacz_pubg.match_config.result", status.result()))), false);
        return 1;
    }

    private static int pause(CommandSourceStack source, long mapId) {
        MatchService.pause(source.getServer(), mapId);
        source.sendSuccess(() -> Texts.of("message.tacz_pubg.match_commands.paused_match_map_timers_shrinking_blue_zone_damage", mapId), true);
        return 1;
    }
    private static int resume(CommandSourceStack source, long mapId) {
        MatchService.resume(source.getServer(), mapId);
        source.sendSuccess(() -> Texts.of("message.tacz_pubg.match_commands.resumed_match_map", mapId), true);
        return 1;
    }
    private static String stageName(String stage) {
        return switch (stage) {
            case "no_circle" -> Texts.tr("screen.tacz_pubg.match_config.no_circle");
            case "preparing" -> Texts.tr("screen.tacz_pubg.match_config.preparing"); case "waiting" -> Texts.tr("screen.tacz_pubg.match_hud_client.waiting_shrink"); case "shrinking" -> Texts.tr("message.tacz_pubg.match_commands.shrinking");
            case "final" -> Texts.tr("message.tacz_pubg.match_commands.combat"); case "paused" -> Texts.tr("message.tacz_pubg.match_commands.paused"); case "finished" -> Texts.tr("screen.tacz_pubg.match_config.settled");
            case "stopped" -> Texts.tr("message.tacz_pubg.match_commands.ended_by_administrator"); default -> stage;
        };
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }

    @FunctionalInterface private interface Action { int run(CommandSourceStack source, long mapId); }
}
