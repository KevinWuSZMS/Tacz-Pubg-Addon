package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;
import java.util.UUID;

/** Drop-source isolation and native injection targets, without starting a game. */
public final class MatchDropChecks {
    private static final ResourceLocation WORLD = ResourceLocation.parse("minecraft:overworld");
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (boolean blocks : List.of(false, true)) for (boolean mobs : List.of(false, true)) {
            var game = game(blocks, mobs);
            check(blocked(game, 8, 8, true) == !blocks, "block rule is independent of mob rule");
            check(blocked(game, 8, 8, false) == !mobs, "mob rule is independent of block rule");
            check(!blocked(game, 16.01, 8, true) && !blocked(game, 16.01, 8, false),
                    "neither rule suppresses drops outside the map");
            check(!MatchDropService.blocksDrops(List.of(game), ResourceLocation.parse("minecraft:the_nether"), 8, 8, true)
                            && !MatchDropService.blocksDrops(List.of(game), ResourceLocation.parse("minecraft:the_nether"), 8, 8, false),
                    "another dimension remains unaffected");
            game.stop();
            check(!blocked(game, 8, 8, true) && !blocked(game, 8, 8, false),
                    "ending a match removes both source restrictions");
        }
        check(!MatchDropService.blocksDrops(List.of(), WORLD, 8, 8, true)
                        && !MatchDropService.blocksDrops(List.of(), WORLD, 8, 8, false),
                "an inactive map never suppresses natural drops");
        // These required injections must match the installed Minecraft version exactly.
        target("net/minecraft/world/level/block/Block", "popResource",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V");
        target("net/minecraft/world/level/block/Block", "popResourceFromFace",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V");
        target("net/minecraft/world/level/block/Block", "popExperience",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V");
        target("net/minecraft/world/Containers", "dropContents",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/Container;)V");
        target("net/minecraft/world/Containers", "dropContents",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/NonNullList;)V");
        target("net/minecraft/world/entity/Entity", "spawnAtLocation",
                "(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;");
        System.out.println("Match drop checks passed: " + checks);
    }

    private static MatchEngine game(boolean blocks, boolean mobs) {
        var json = JsonParser.parseString(MatchConfigModel.toJson(MatchConfigModel.defaults(1))).getAsJsonObject();
        json.addProperty("allowBlockDrops", blocks);
        json.addProperty("allowMobDrops", mobs);
        json.addProperty("autoEnd", false);
        var map = new MapDefinition(1, "Drops", WORLD, 0, 0, 16, 16, new Circle(8, 8, 7), 1, List.of());
        return new MatchEngine(new MatchSession(UUID.randomUUID(), 0, map), MatchConfigModel.parse(json.toString()),
                List.of(new MatchEngine.Participant(UUID.randomUUID(), "One", "One")), 1);
    }

    private static boolean blocked(MatchEngine game, double x, double z, boolean block) {
        return MatchDropService.blocksDrops(List.of(game), WORLD, x, z, block);
    }

    private static void target(String owner, String name, String descriptor) throws Exception {
        var node = new ClassNode();
        try (var stream = MatchDropChecks.class.getResourceAsStream("/" + owner + ".class")) {
            if (stream == null) throw new AssertionError(owner);
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE);
        }
        check(node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)),
                owner + "." + name + descriptor);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
