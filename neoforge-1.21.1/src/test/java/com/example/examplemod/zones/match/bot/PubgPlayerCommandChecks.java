package com.example.examplemod.zones.match.bot;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Registers and parses the real command tree; execution never creates a world or a player. */
public final class PubgPlayerCommandChecks {
    private static int checks;
    public static void main(String[] args) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        PubgPlayerCommands.register(new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.DEDICATED, null));
        var root = dispatcher.getRoot().getChild("pubgplayer");
        check(root != null, "pubgplayer is registered as its own command");
        for (int permission = 0; permission <= 4; permission++)
            check(root.getRequirement().test(source(permission)) == (permission >= 2), "actual command root checks OP 2 permission");
        var target = root.getChild("player"); check(target != null, "the controlled bot is an explicit name argument");
        for (String argument : new String[] {"auto", "seek", "loot", "attack", "evade", "patrol", "guard", "hold",
                "stop", "enter-circle", "reload", "despawn", "kill", "fire hold", "fire free",
                "move 1 65 -2", "move ~1 ~ ~-2", "follow Teammate"}) {
            var parse = dispatcher.parse("pubgplayer PUBG_example_01 " + argument, source(2));
            check(parse.getReader().canRead() || !parse.getExceptions().isEmpty(), "legacy bot command is rejected: " + argument);
            check(target.getChild(argument.split(" ", 2)[0]) == null, "legacy bot command is absent from suggestions: " + argument);
        }
        for (String argument : new String[] {"behavior auto", "behavior seek", "behavior loot", "behavior attack",
                "behavior evade", "behavior patrol", "behavior guard", "behavior hold", "movement move 1 65 -2",
                "movement move ~1 ~ ~-2", "movement follow Teammate",
                "movement stop", "movement enter-circle", "combat fire hold", "combat fire free", "combat reload",
                "manage despawn", "status", "help", "behavior", "movement", "combat", "manage"}) {
            var parse = dispatcher.parse("pubgplayer PUBG_example_01 " + argument, source(2));
            check(!parse.getReader().canRead() && parse.getExceptions().isEmpty()
                    && parse.getContext().getCommand() != null, "grouped bot command is executable: " + argument);
        }
        var status = dispatcher.parse("pubgplayer PUBG_example_01", source(2));
        check(!status.getReader().canRead() && status.getContext().getCommand() != null, "bare bot name retains status convenience");
        check(root.getChild("list") != null && root.getChild("help") != null, "bot list and help are discoverable");
        check(target.getChild("movement").getChild("follow").getChild("teammate") != null, "follow uses a native online player selector");
        var unprivileged = dispatcher.parse("pubgplayer PUBG_example_01 behavior attack", source(1));
        check(unprivileged.getReader().canRead(), "OP 1 cannot enter the command tree");
        System.out.println("PubgPlayerCommandChecks: " + checks + " actual Brigadier command and permission assertions passed");
    }
    private static CommandSourceStack source(int level) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, level, "offline-check", Component.literal("offline-check"), null, null);
    }
    private static void check(boolean yes, String message) { checks++; if (!yes) throw new AssertionError(message); }
}
