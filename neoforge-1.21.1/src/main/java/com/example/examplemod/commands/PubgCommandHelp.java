package com.example.examplemod.commands;

import com.example.examplemod.localization.Texts;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Discoverable help and shared builders for the public command groups. */
public final class PubgCommandHelp {
    private PubgCommandHelp() {}
    public static final String[] TOPICS = {"config", "map", "match", "create", "destroy", "loot", "player"};

    public static int show(CommandSourceStack source, String topic) {
        source.sendSuccess(() -> switch (topic) {
            case "config" -> Texts.of("command.tacz_pubg.help.config");
            case "map" -> Texts.of("command.tacz_pubg.help.map");
            case "match" -> Texts.of("command.tacz_pubg.help.match");
            case "create" -> Texts.of("command.tacz_pubg.help.create");
            case "destroy" -> Texts.of("command.tacz_pubg.help.destroy");
            case "loot" -> Texts.of("command.tacz_pubg.help.loot");
            case "player" -> Texts.of("command.tacz_pubg.help.player");
            default -> Texts.of("command.tacz_pubg.help.root");
        }, false);
        if (topic.equals("map"))
            source.sendSuccess(() -> Texts.of("command.tacz_pubg.help.map_recovery"), false);
        return 1;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> group(String name) {
        return Commands.literal(name).requires(source -> source.hasPermission(2))
                .executes(context -> show(context.getSource(), name))
                .then(Commands.literal("help").executes(context -> show(context.getSource(), name)));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> alias(String name, CommandNode<CommandSourceStack> original) {
        if (original == null) throw new IllegalArgumentException("Missing command for alias: " + name);
        var alias = Commands.literal(name).requires(original.getRequirement());
        if (original.getCommand() != null) alias.executes(original.getCommand());
        for (var child : original.getChildren()) alias.then(child);
        return alias;
    }

    /** Attach root/topic help; individual command owners register their grouped operations directly. */
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.executes(context -> show(context.getSource(), "root"));
        var help = Commands.literal("help").executes(context -> show(context.getSource(), "root"));
        for (String topic : TOPICS)
            help.then(Commands.literal(topic).executes(context -> show(context.getSource(), topic)));
        root.then(help);
    }
}
