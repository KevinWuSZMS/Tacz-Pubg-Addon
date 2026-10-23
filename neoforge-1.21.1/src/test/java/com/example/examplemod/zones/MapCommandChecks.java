package com.example.examplemod.zones;

import com.example.examplemod.commands.MapCommandContext;
import com.example.examplemod.commands.PubgSuggestions;
import com.example.examplemod.spawn.SpawnAreaService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Real command parsing plus location/overlap defaults; no world is created or changed. */
public final class MapCommandChecks {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static int checks;

    public static void main(String[] args) throws Exception {
        commandTree();
        confirmationGate();
        mapDefaults();
        System.out.println("MapCommandChecks: " + checks + " command and context assertions passed");
    }

    private static void commandTree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        SpawnAreaService.registerCommands(new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.DEDICATED, null));
        var root = dispatcher.getRoot().getChild("pubgdebug");
        check(root != null && root.getCommand() != null, "debug root provides readable help");
        for (int permission = 0; permission <= 4; permission++)
            check(root.getRequirement().test(source(permission)) == (permission >= 2), "root keeps OP 2 protection");
        for (String command : new String[] {
                "help", "help config", "help map", "help match", "help create", "help destroy", "help loot", "help player",
                "config", "config help", "map", "map help", "map list", "map info", "map info 7", "map select 7",
                "map create 0 0 30 40", "map create ~ ~ ~30 ~40", "map destroy", "map destroy 7",
                "map freeze", "map freeze 7", "map unfreeze", "map unfreeze 7", "map journal status", "map journal status 7",
                "map journal retry", "map journal retry 7", "map journal list",
                "create map 0 0 30 40", "create safezone 15 15 10", "create safezone 15 15 10 7",
                "create bombingzone 15 15 10", "create bombingzone 15 15 10 7",
                "create staging 15 15 3", "create staging 15 15 3 7",
                "create flightpath 0 0 30 30", "create flightpath 0 0 30 30 7", "create loot",
                "destroy map", "destroy map 7", "destroy safezone", "destroy safezone 7", "destroy bombingzone 12",
                "destroy bombingzone 12 7", "destroy flightpath", "destroy flightpath 7", "destroy staging", "destroy staging 7", "destroy loot",
                "match start", "match start 7", "match end", "match end 7", "match pause", "match pause 7",
                "match resume", "match resume 7", "match info", "match info 7",
                "loot show true", "loot show false", "loot area", "loot area add", "loot area remove", "loot area info", "loot area info 7",
                "loot area list", "loot area list 2", "loot area clearSelection", "loot area clear", "loot area clear confirm",
                "loot respawn", "loot respawn status"
        }) {
            var parsed = dispatcher.parse("pubgdebug " + command, source(2));
            check(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty() && parsed.getContext().getCommand() != null,
                    "registered executable syntax: " + command);
        }
        for (String command : new String[] {
                "createMap 0 0 30 30", "createSafeZone 15 15 5", "createBombingZone 15 15 5",
                "createStagingSpawn 15 15 3", "createFlightPath 0 0 30 30", "removeFlightPath", "removeFlightPath 7",
                "destroyMap", "destroyMap 7", "removeSafeZone", "removeSafeZone 7", "removeBombingZone 12",
                "removeBombingZone 12 7", "removeStagingSpawn", "removeStagingSpawn 7",
                "freezeMap", "freezeMap 7", "unfreezeMap", "unfreezeMap 7", "mapSelect 7", "mapList", "mapInfo", "mapInfo 7",
                "startMatch", "endMatch 7", "pauseMatch 7", "resumeMatch 7", "matchInfo 7", "showItemSpawnArea true",
                "area add", "area remove", "area clear confirm", "area info", "area info 7", "area list", "area list 2",
                "area clearSelection", "itemRespawn", "itemRespawn status", "mapJournal status", "mapJournal retry 7"
        }) {
            var parsed = dispatcher.parse("pubgdebug " + command, source(2));
            check(parsed.getReader().canRead() || !parsed.getExceptions().isEmpty(),
                    "legacy entry is no longer executable: " + command);
            check(root.getChild(command.split(" ", 2)[0]) == null, "legacy entry is absent from command suggestions: " + command);
        }
        check(root.getChild("map").getChild("create").getChild("pos1")
                        == root.getChild("create").getChild("map").getChild("pos1"),
                "both grouped map creation conveniences share the same executable arguments");
        check(root.getChild("map").getChild("destroy").getCommand()
                        == root.getChild("destroy").getChild("map").getCommand(),
                "both grouped map deletion conveniences share the same operation");
        check(root.getChild("loot").getChild("area").getChild("add").getCommand()
                        == root.getChild("create").getChild("loot").getCommand(),
                "both grouped loot creation conveniences share the same operation");
        check(root.getChild("loot").getChild("area").getChild("remove").getCommand()
                        == root.getChild("destroy").getChild("loot").getCommand(),
                "both grouped loot removal conveniences share the same operation");
        for (String action : new String[] {"skipEntities", "skipTerrain", "skipRestore", "recordEntities", "ignoreEntities"}) {
            var operation = root.getChild("map").getChild(action);
            check(operation != null, "recovery operation is discoverable: " + action);
            var id = operation.getChild("mapId");
            check(id instanceof ArgumentCommandNode<?, ?> argument && argument.getCustomSuggestions() == PubgSuggestions.MAPS,
                    "recovery IDs complete existing maps: " + action);
            check(operation.getCommand() != operation.getChild("confirm").getCommand()
                            && id.getCommand() != id.getChild("confirm").getCommand(),
                    "preview and confirmation use distinct execution branches: " + action);
            for (int permission = 0; permission <= 4; permission++)
                check(operation.getRequirement().test(source(permission)) == (permission >= 2),
                        "recovery operation retains OP 2 protection: " + action);
            for (String suffix : new String[] {"", " confirm", " 7", " 7 confirm"}) {
                var parsed = dispatcher.parse("pubgdebug map " + action + suffix, source(2));
                check(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty() && parsed.getContext().getCommand() != null,
                        "recovery preview/confirmation syntax: " + action + suffix);
            }
            for (String suffix : new String[] {" 0 confirm", " -1 confirm", " confirm 7", " 7 yes", " 7 confirm confirm"}) {
                var parsed = dispatcher.parse("pubgdebug map " + action + suffix, source(2));
                check(parsed.getReader().canRead() || !parsed.getExceptions().isEmpty(),
                        "only a final literal confirm is accepted: " + action + suffix);
            }
        }
        for (String command : new String[] {"map freeze 0", "map unfreeze -1", "destroy bombingzone 0", "map create 0 0"})
            check(!dispatcher.parse("pubgdebug " + command, source(2)).getExceptions().isEmpty()
                            || dispatcher.parse("pubgdebug " + command, source(2)).getContext().getCommand() == null,
                    "invalid or incomplete arguments cannot execute: " + command);
        check(dispatcher.parse("pubgdebug map unfreeze 7", source(1)).getReader().canRead(), "unprivileged caller cannot enter grouped commands");
        rejects(() -> MapCommandContext.resolve(source(2), 0), "console cannot silently choose an arbitrary map");
        rejects(() -> MapCommandContext.resolve(source(1), 7), "context resolution also checks permission");
    }

    /** Check the real command implementation's dry-run return precedes every recovery mutation. */
    private static void confirmationGate() throws Exception {
        try (var input = MapCommandChecks.class.getResourceAsStream("/com/example/examplemod/backup/MapJournalCommands.class")) {
            check(input != null, "journal command implementation is bundled");
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var execute = node.methods.stream().filter(method -> method.name.equals("executeAction")).findFirst().orElseThrow();
            var instructions = execute.instructions;
            int guard = -1;
            for (int i = 1; i < instructions.size(); i++)
                if (instructions.get(i) instanceof JumpInsnNode branch && branch.getOpcode() == Opcodes.IFNE
                        && instructions.get(i - 1) instanceof VarInsnNode value && value.getOpcode() == Opcodes.ILOAD && value.var == 4) {
                    guard = instructions.indexOf(branch.label); break;
                }
            check(guard >= 0, "the confirmed flag explicitly gates the mutation branch");
            int mutations = 0;
            boolean previewReturns = false;
            for (int i = 0; i < instructions.size(); i++) {
                if (i < guard && instructions.get(i).getOpcode() == Opcodes.IRETURN) previewReturns = true;
                if (instructions.get(i) instanceof MethodInsnNode call
                        && call.owner.equals("com/example/examplemod/backup/MapResetService")
                        && (call.name.equals("skipRestoration") || call.name.equals("chooseEntityRecording"))) {
                    mutations++;
                    check(i > guard, "service mutation is reachable only after the confirmation branch");
                }
            }
            check(previewReturns, "preview exits before the confirmed-only service calls");
            check(mutations == 5, "all five confirmed recovery/recording actions share the gate");
        }
    }

    private static void mapDefaults() {
        var data = new MapZoneSavedData();
        var old = data.saveMap(data.revision(), draft("old", OVERWORLD));
        var newer = data.saveMap(data.revision(), draft("newer", OVERWORLD));
        check(MapCommandContext.containing(data.maps(), OVERWORLD, 0, 0).getFirst().id() == newer.id(), "overlap chooses the newest surviving map");
        data.deleteMap(data.revision(), newer.id());
        var replacement = data.saveMap(data.revision(), draft("replacement", OVERWORLD));
        check(replacement.id() == newer.id(), "deleted highest ID is reused by current allocator");
        check(MapCommandContext.containing(data.maps(), OVERWORLD, 0, 0).getFirst().name().equals("replacement"), "reused highest ID still belongs to latest creation");
        var editedOld = new MapZoneModel.MapDefinition(old.id(), "edited old", old.dimension(), old.minX(), old.minZ(),
                old.maxX(), old.maxZ(), old.safeZone(), old.blueDamagePerSecond(), old.bombingZones());
        data.saveMap(data.revision(), editedOld);
        check(MapCommandContext.containing(data.maps(), OVERWORLD, 0, 0).getFirst().id() == replacement.id(), "editing an old map does not change creation precedence");
        var loaded = MapZoneSavedData.load(data.save(new CompoundTag(), null), null);
        check(MapCommandContext.containing(loaded.maps(), OVERWORLD, 0, 0).getFirst().id() == replacement.id(), "selection precedence survives persistence");
        var otherDimension = data.saveMap(data.revision(), draft("nether", NETHER));
        check(MapCommandContext.containing(data.maps(), OVERWORLD, 0, 0).getFirst().id() == replacement.id(), "a newer map in another dimension does not steal selection");
        check(MapCommandContext.containing(data.maps(), NETHER, 0, 0).getFirst().id() == otherDimension.id(), "the caller's dimension is respected");
        check(MapCommandContext.containing(data.maps(), OVERWORLD, 10, 10).size() == 2, "map edges are included");
        check(MapCommandContext.containing(data.maps(), OVERWORLD, 10.01, 0).isEmpty(), "outside maps there is no fallback to editor selection");
        check(MapCommandContext.containing(data.maps(), OVERWORLD, Double.NaN, 0).isEmpty(), "nonfinite positions select nothing");
        for (var map : List.copyOf(data.maps())) data.deleteMap(data.revision(), map.id());
        var restarted = data.saveMap(data.revision(), draft("restarted", OVERWORLD));
        check(restarted.id() == 1 && MapCommandContext.containing(data.maps(), OVERWORLD, 0, 0).getFirst().id() == 1,
                "deleting every map and restarting numbering retains the newest-map invariant");
    }

    private static MapZoneModel.MapDefinition draft(String name, ResourceLocation dimension) {
        return new MapZoneModel.MapDefinition(0, name, dimension, -10, -10, 10, 10, null, 1, List.of());
    }
    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "offline-check", Component.literal("offline-check"), null, null);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
