package com.example.examplemod.zones.match.bot;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.commands.PubgCommandHelp;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** OP-only direct controls over this addon's owned, match-bound Carpet bots. */
public final class PubgPlayerCommands {
    private PubgPlayerCommands() {}
    @FunctionalInterface private interface Action { String run(CommandContext<CommandSourceStack> context); }
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("pubgplayer")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    PubgCommandHelp.show(context.getSource(), "player");
                    return list(context.getSource());
                })
                .then(Commands.literal("help").executes(context -> PubgCommandHelp.show(context.getSource(), "player")))
                .then(Commands.literal("list").executes(context -> list(context.getSource())));
        var target = Commands.argument("player", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MatchBotService.names(context.getSource().getServer()), builder))
                .executes(context -> execute(context, c -> MatchBotService.status(c.getSource().getServer(), name(c))));
        var behavior = Commands.literal("behavior").executes(context -> PubgCommandHelp.show(context.getSource(), "player"));
        for (String mode : new String[] {"auto", "seek", "loot", "attack", "evade", "patrol", "guard", "hold"})
            behavior.then(Commands.literal(mode).executes(context -> execute(context, c ->
                    MatchBotService.behavior(c.getSource().getServer(), name(c), mode, null, null))));
        target.then(behavior);
        var movement = Commands.literal("movement").executes(context -> PubgCommandHelp.show(context.getSource(), "player"));
        movement.then(Commands.literal("stop").executes(context -> execute(context, c ->
                MatchBotService.behavior(c.getSource().getServer(), name(c), "hold", null, null))));
        movement.then(Commands.literal("enter-circle").executes(context -> execute(context, c ->
                MatchBotService.behavior(c.getSource().getServer(), name(c), "circle", null, null))));
        movement.then(Commands.literal("move").then(Commands.argument("position", Vec3Argument.vec3())
                .executes(context -> execute(context, c -> MatchBotService.behavior(c.getSource().getServer(), name(c), "move", Vec3Argument.getVec3(c, "position"), null)))));
        movement.then(Commands.literal("follow").then(Commands.argument("teammate", EntityArgument.player())
                .executes(context -> {
                    var teammate = EntityArgument.getPlayer(context, "teammate");
                    return execute(context, c -> MatchBotService.behavior(c.getSource().getServer(), name(c), "follow", null, teammate.getUUID()));
                })));
        target.then(movement);
        target.then(Commands.literal("combat").executes(context -> PubgCommandHelp.show(context.getSource(), "player"))
                .then(Commands.literal("fire")
                .then(Commands.literal("hold").executes(context -> execute(context, c -> MatchBotService.fire(c.getSource().getServer(), name(c), false))))
                .then(Commands.literal("free").executes(context -> execute(context, c -> MatchBotService.fire(c.getSource().getServer(), name(c), true)))))
                .then(Commands.literal("reload").executes(context -> execute(context, c -> MatchBotService.reload(c.getSource().getServer(), name(c))))));
        target.then(Commands.literal("status").executes(context -> execute(context, c -> MatchBotService.status(c.getSource().getServer(), name(c)))));
        target.then(Commands.literal("manage").executes(context -> PubgCommandHelp.show(context.getSource(), "player"))
                .then(Commands.literal("despawn").executes(context -> execute(context, c -> MatchBotService.despawn(c.getSource().getServer(), name(c))))));
        target.then(Commands.literal("help").executes(context -> PubgCommandHelp.show(context.getSource(), "player")));
        command.then(target); event.getDispatcher().register(command);
    }
    private static int list(CommandSourceStack source) {
        var names = MatchBotService.names(source.getServer());
        source.sendSuccess(() -> Texts.of("message.tacz_pubg.pubg_player_commands.managed_bots_behaviors_auto_seek_loot_attack_evade",
                names.isEmpty() ? Texts.tr("message.tacz_pubg.pubg_player_commands.waiting_lobby_bot_creation") : String.join("、", names)), false);
        return names.size();
    }
    private static String name(CommandContext<CommandSourceStack> context) { return StringArgumentType.getString(context, "player"); }
    private static int execute(CommandContext<CommandSourceStack> context, Action action) {
        if (!context.getSource().hasPermission(2)) { context.getSource().sendFailure(Texts.of("message.tacz_pubg.pubg_player_commands.op_permission_level_required_manage_bots")); return 0; }
        try {
            String result = action.run(context); context.getSource().sendSuccess(() -> Texts.component(result), true); return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            context.getSource().sendFailure(Texts.component(error.getMessage() == null ? Texts.tr("message.tacz_pubg.pubg_player_commands.check_bot_status_try_again") : error.getMessage())); return 0;
        }
    }
}
