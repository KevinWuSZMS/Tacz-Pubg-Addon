package com.example.examplemod.backup;

import com.example.examplemod.commands.PubgSuggestions;
import com.example.examplemod.commands.MapCommandContext;
import com.example.examplemod.commands.PubgCommandHelp;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.MapZoneSavedData;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class MapJournalCommands {
    private MapJournalCommands() {}
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        var journal = Commands.literal("journal").requires(source -> source.hasPermission(2))
                .executes(context -> PubgCommandHelp.show(context.getSource(), "map"))
                .then(Commands.literal("list").executes(context -> {
                    var source = context.getSource();
                    for (var map : MapZoneSavedData.get(source.getServer()).maps())
                        source.sendSuccess(() -> Texts.component(MapResetService.description(source.getServer(), map.id())), false);
                    return 1;
                }));
        for (String action : new String[] {"status", "retry"}) journal.then(Commands.literal(action)
                .executes(context -> execute(context.getSource(), 0, action.equals("retry")))
                .then(Commands.argument("mapId", LongArgumentType.longArg(1)).suggests(PubgSuggestions.MAPS)
                        .executes(context -> execute(context.getSource(), LongArgumentType.getLong(context, "mapId"), action.equals("retry")))));
        var map = PubgCommandHelp.group("map").then(journal);
        for (Action action : Action.values()) map.then(confirmedAction(action));
        root.then(map);
    }

    private enum Action {
        SKIP_ENTITIES("skipEntities", "command.tacz_pubg.map.skip_entities_preview"),
        SKIP_TERRAIN("skipTerrain", "command.tacz_pubg.map.skip_terrain_preview"),
        SKIP_RESTORE("skipRestore", "command.tacz_pubg.map.skip_restore_preview"),
        RECORD_ENTITIES("recordEntities", "command.tacz_pubg.map.record_entities_preview"),
        IGNORE_ENTITIES("ignoreEntities", "command.tacz_pubg.map.ignore_entities_preview");

        final String command;
        final String preview;
        Action(String command, String preview) { this.command = command; this.preview = preview; }
    }

    /** A bare command only explains the operation; both default and explicit IDs require the final literal. */
    private static LiteralArgumentBuilder<CommandSourceStack> confirmedAction(Action action) {
        return Commands.literal(action.command).requires(source -> source.hasPermission(2))
                .executes(context -> executeAction(context.getSource(), 0, action, false))
                .then(Commands.literal("confirm")
                        .executes(context -> executeAction(context.getSource(), 0, action, true)))
                .then(Commands.argument("mapId", LongArgumentType.longArg(1)).suggests(PubgSuggestions.MAPS)
                        .executes(context -> executeAction(context.getSource(), LongArgumentType.getLong(context, "mapId"), action, false))
                        .then(Commands.literal("confirm").executes(context -> executeAction(context.getSource(),
                                LongArgumentType.getLong(context, "mapId"), action, true))));
    }

    private static int executeAction(CommandSourceStack source, long mapId, Action action, boolean confirmed) {
        try {
            var map = MapCommandContext.resolve(source, mapId);
            if (!confirmed) {
                // Always print the resolved ID so the eventual confirmation cannot silently retarget after movement.
                String command = "/pubgdebug map " + action.command + " " + map.id() + " confirm";
                var entry = MapResetData.get(source.getServer()).entries.get(map.id());
                String preview = entry != null && entry.entityCapture
                        && (action == Action.SKIP_ENTITIES || action == Action.SKIP_RESTORE)
                        ? "command.tacz_pubg.map.skip_entity_capture_preview" : action.preview;
                source.sendSuccess(() -> Texts.of(preview, map.id(), command), false);
                return 1;
            }
            switch (action) {
                case SKIP_ENTITIES -> MapResetService.skipRestoration(source.getServer(), map.id(), true, false);
                case SKIP_TERRAIN -> MapResetService.skipRestoration(source.getServer(), map.id(), false, true);
                case SKIP_RESTORE -> MapResetService.skipRestoration(source.getServer(), map.id(), true, true);
                case RECORD_ENTITIES -> MapResetService.chooseEntityRecording(source.getServer(), map.id(), true);
                case IGNORE_ENTITIES -> MapResetService.chooseEntityRecording(source.getServer(), map.id(), false);
            }
            source.sendSuccess(() -> Texts.of("command.tacz_pubg.map.confirmed_action", map.id(), action.command), true);
            source.sendSuccess(() -> Texts.component(MapResetService.description(source.getServer(), map.id())), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            source.sendFailure(Texts.component(String.valueOf(error.getMessage())));
            return 0;
        }
    }

    private static int execute(CommandSourceStack source, long mapId, boolean retry) {
        try {
            var map = MapCommandContext.resolve(source, mapId);
            if (retry) MapResetService.retry(source.getServer(), map.id());
            source.sendSuccess(() -> Texts.component(MapResetService.description(source.getServer(), map.id())), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            source.sendFailure(Texts.component(String.valueOf(error.getMessage())));
            return 0;
        }
    }
}
