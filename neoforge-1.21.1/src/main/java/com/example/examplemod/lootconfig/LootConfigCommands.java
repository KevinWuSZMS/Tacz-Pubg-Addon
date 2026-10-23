package com.example.examplemod.lootconfig;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.example.examplemod.commands.PubgCommandHelp;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class LootConfigCommands {
    private LootConfigCommands() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("config").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    LootConfigPayloads.open(context.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("help").executes(context -> PubgCommandHelp.show(context.getSource(), "config"))));
    }
}
