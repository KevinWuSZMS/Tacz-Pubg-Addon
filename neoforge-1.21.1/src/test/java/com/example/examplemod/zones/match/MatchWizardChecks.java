package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.ZoneSettings.BombingProperties;
import com.example.examplemod.zones.bombing.BombingTimeline;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

public final class MatchWizardChecks {
    private static int checks;
    public static void main(String[] args) {
        var original = new MapZoneModel.MapDefinition(7, "Boundary only", ResourceLocation.parse("minecraft:overworld"),
                -500, -250, 500, 250, null, 1, List.of());
        var rules = MatchConfigModel.defaults(7);
        var prepared = MatchMapPreparation.prepare(original, rules, 317);
        check(original.safeZone() == null && original.bombingZones().isEmpty(), "authoring definitions stay untouched");
        check(prepared.safeZone() != null && prepared.safeZone().radius() == 250, "safe circle is derived from shorter boundary dimension");
        check(prepared.bombingZones().isEmpty(), "map preparation does not activate automatic bombing before its match-clock warning");
        var bombing = new BombingTimeline(prepared, rules, 317);
        long firstWarning = rules.flowRules().bombingFirstSeconds() * 20L;
        check(bombing.frame(firstWarning - 1, 1, prepared.safeZone()).zones().isEmpty(),
                "automatic bombing stays absent before the first warning");
        var warning = bombing.frame(firstWarning, 1, prepared.safeZone());
        check(warning.stage().equals("warning") && !warning.striking() && warning.zones().size() == 1,
                "the match timeline generates one circle at the first warning");
        var area = warning.zones().getFirst().area();
        check(area.centerX() - area.radius() >= prepared.minX() && area.centerX() + area.radius() <= prepared.maxX()
                && area.centerZ() - area.radius() >= prepared.minZ() && area.centerZ() + area.radius() <= prepared.maxZ()
                && prepared.safeZone().contains(area.centerX(), area.centerZ()),
                "the whole generated bombing circle stays inside playable boundaries and its center is inside the safe circle");
        check(warning.equals(new BombingTimeline(prepared, rules, 317).frame(firstWarning, 1, prepared.safeZone())),
                "automatic bombing geometry and warning timing reproduce from the frozen match seed");
        check(prepared.equals(MatchMapPreparation.prepare(original, rules, 317)), "same frozen match seed is reproducible");
        check(MapZoneModel.validate(prepared).isEmpty(), "derived map obeys full map invariants");
        var disabled = new MatchConfigModel.Settings(7, rules.name(), 1, 100, 4, rules.preparationSeconds(),
                rules.maxMatchSeconds(), rules.disconnectGraceSeconds(), true, true, true, false, rules.phases(),
                new MatchConfigModel.BombingRules(false, .2, 120, BombingProperties.defaults()));
        var authored = new MapZoneModel.MapDefinition(prepared.id(), prepared.name(), prepared.dimension(),
                prepared.minX(), prepared.minZ(), prepared.maxX(), prepared.maxZ(), prepared.safeZone(),
                prepared.blueDamagePerSecond(), warning.zones());
        check(MatchMapPreparation.prepare(authored, disabled, 9).bombingZones().isEmpty(),
                "rule switch removes authored bombing candidates");
        check(new BombingTimeline(prepared, disabled, 9).frame(firstWarning, 1, prepared.safeZone()).zones().isEmpty(),
                "rule switch also disables automatic bombing");
        check(MatchConfigModel.parse(MatchConfigModel.toJson(disabled)).equals(disabled), "100 seats, team size and per-match bombing survive codec");
        var blocks = java.util.stream.IntStream.range(0, 256).mapToObj(i -> "test:block_" + i).toList();
        var manyBlocks = new MatchConfigModel.Settings(7, rules.name(), 1, 100, 4, 30, 2400, 60, true, true, true, false,
                rules.phases(), new MatchConfigModel.BombingRules(true, .2, 60, new BombingProperties("tacz:rpg_rocket", 10, 4, 0, false, 200, blocks)));
        check(MatchConfigModel.parse(MatchConfigModel.toJson(manyBlocks)).equals(manyBlocks), "full 256-entry block list survives rule and preset wire codec");
        var library = new MatchRulePresets();
        library.put("100 players", disabled);
        var loaded = library.load("100 players", 9);
        check(loaded.mapId() == 9 && loaded.maxPlayers() == 100 && loaded.teamSize() == 4
                && loaded.bombingRules().equals(disabled.bombingRules()), "named rules remap only map identity");
        check(library.list().size() == 1 && library.load("100 players", 7).mapId() == 7, "loading leaves saved named rules intact");
        check(library.save(new CompoundTag(), null).getList("rules", 10).size() == 1, "named rules persist independently of map templates");
        library.saveRule("", "Other rule", disabled);
        rejects(() -> library.saveRule("100 players", "Other rule", disabled));
        check(library.list().size() == 2 && library.load("100 players", 7).equals(disabled), "rename collision preserves both rules");
        library.saveRule("100 players", "Renamed rule", disabled);
        rejects(() -> library.load("100 players", 7));
        check(library.load("Renamed rule", 7).equals(disabled), "rename moves the selected rule without duplicating it");
        library.delete("Renamed rule");
        check(library.list().size() == 1 && loaded.maxPlayers() == 100, "library deletion preserves already applied map settings");
        rejects(() -> library.delete("Renamed rule"));
        for (String action : List.of("save", "load", "delete")) {
            var packet = new MatchConfigPayloads.Preset(java.util.UUID.randomUUID(), 3, 7, "新规则", "{}", action, "旧规则");
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                    net.minecraft.core.RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
            try {
                MatchConfigPayloads.Preset.CODEC.encode(buffer, packet);
                check(MatchConfigPayloads.Preset.CODEC.decode(buffer).equals(packet), "rule-library wire round-trip retains action and rename identity: " + action);
            } finally { buffer.release(); }
        }
        var botNames = new java.util.HashSet<String>();
        var token = java.util.UUID.fromString("7cf6455e-ff8c-41da-9aeb-c6d81d13c20f");
        for (int seat = 1; seat <= 100; seat++) {
            String name = com.example.examplemod.zones.match.bot.BotRules.botName(token, seat);
            check(name.length() <= 16 && botNames.add(name), "every bot identity through seat 100 fits Minecraft's wire name limit");
        }
        for (double invalid : new double[]{Double.NaN, -.1, 0, 1.1}) rejects(() -> new MatchConfigModel.BombingRules(true, invalid, 60, BombingProperties.defaults()));
        rejects(() -> new MatchConfigModel.BombingRules(true, .2, 60.5, BombingProperties.defaults()));
        System.out.println("MatchWizardChecks: " + checks + " automatic map, named rules, 100-player and bombing checks passed");
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void rejects(Runnable action) { try { action.run(); throw new AssertionError("invalid rule accepted"); } catch (IllegalArgumentException expected) { checks++; } }
}
