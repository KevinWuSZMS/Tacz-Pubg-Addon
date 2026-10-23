package com.example.examplemod.zones.match.lobby;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchConfigPayloads;
import com.example.examplemod.zones.match.MatchStatus;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Bounded room/match packet checks. No Carpet bodies, server, world or gun runtime is started. */
public final class LobbyWireChecks {
    private static final UUID REQUEST = UUID.fromString("99d19777-15bd-45a4-bc73-1de1f30b8158");
    private static final UUID SESSION = UUID.fromString("7cf6455e-ff8c-41da-9aeb-c6d81d13c20f");
    private static int checks;
    private LobbyWireChecks() {}

    public static void main(String[] args) {
        requestAndControlPackets();
        roomSavePackets();
        completeSnapshots();
        malformedSnapshots();
        statusAndHudPackets();
        System.out.println("Lobby wire checks passed: " + checks);
    }

    private static void requestAndControlPackets() {
        for (long map : new long[]{0, 1, Long.MAX_VALUE - 1}) {
            var packet = new MatchConfigPayloads.Request(REQUEST, map);
            check(roundTrip(MatchConfigPayloads.Request.CODEC, packet).equals(packet), "request map boundary round trip " + map);
        }
        rejects(() -> new MatchConfigPayloads.Request(null, 1), "request owns a request UUID");
        for (long map : new long[]{-1, Long.MAX_VALUE})
            rejects(() -> new MatchConfigPayloads.Request(REQUEST, map), "request rejects invalid map " + map);
        for (String action : List.of("start", "end", "refresh", "pause", "resume")) {
            var packet = new MatchConfigPayloads.Control(REQUEST, 41, 7, action, 13);
            check(roundTrip(MatchConfigPayloads.Control.CODEC, packet).equals(packet), "control preserves independent lobby revision " + action);
        }
        var legacy = new MatchConfigPayloads.Control(REQUEST, 0, 1, "start");
        check(legacy.lobbyRevision() == 0, "legacy control overload has explicit initial room revision");
        check(roundTrip(MatchConfigPayloads.Control.CODEC, new MatchConfigPayloads.Control(REQUEST, 0, 1, "end", -1))
                .lobbyRevision() == -1, "ending can omit a room optimistic-concurrency revision");
        rejects(() -> new MatchConfigPayloads.Control(REQUEST, -1, 1, "start", 0), "control rejects negative match revision");
        rejects(() -> new MatchConfigPayloads.Control(REQUEST, 0, 0, "start", 0), "control targets an explicit map");
        rejects(() -> new MatchConfigPayloads.Control(REQUEST, 0, Long.MAX_VALUE, "start", 0), "control rejects reserved maximum map ID");
        rejects(() -> new MatchConfigPayloads.Control(REQUEST, 0, 1, "delete", 0), "control action is allowlisted");
        rejects(() -> new MatchConfigPayloads.Control(REQUEST, 0, 1, "start", -1), "start requires room revision");
        rejects(() -> new MatchConfigPayloads.Control(REQUEST, 0, 1, "end", -2), "end revision lower bound is enforced");
        decodeRejects(MatchConfigPayloads.Control.CODEC, b -> {
            b.writeUUID(REQUEST); b.writeVarLong(0); b.writeVarLong(1); b.writeUtf("arbitrary-action"); b.writeVarLong(0);
        }, "wire control uses the same action allowlist as the constructor");
    }

    private static void roomSavePackets() {
        for (int count : new int[]{0, 1, 20, 100}) {
            LobbyModel.Room room = room(count);
            var save = new MatchConfigPayloads.SaveLobby(REQUEST, 9, LobbyModel.toJson(room));
            var decoded = roundTrip(MatchConfigPayloads.SaveLobby.CODEC, save);
            check(decoded.equals(save) && LobbyModel.parse(decoded.json()).equals(room), "room save Unicode/seat round trip " + count);
        }
        rejects(() -> new MatchConfigPayloads.SaveLobby(null, 0, "{}"), "room save requires request UUID");
        rejects(() -> new MatchConfigPayloads.SaveLobby(REQUEST, -1, "{}"), "room save requires current revision");
        rejects(() -> new MatchConfigPayloads.SaveLobby(REQUEST, 0, null), "room save requires JSON bytes");
        rejects(() -> new MatchConfigPayloads.SaveLobby(REQUEST, 0, "中".repeat(LobbyModel.MAX_JSON_BYTES / 3 + 1)),
                "room save constructor measures UTF-8 bytes rather than character count");
        String exactly = "a".repeat(LobbyModel.MAX_JSON_BYTES);
        check(roundTrip(MatchConfigPayloads.SaveLobby.CODEC, new MatchConfigPayloads.SaveLobby(REQUEST, 0, exactly)).json().equals(exactly),
                "room wire permits the exact byte limit before application JSON validation");
        decodeRejects(MatchConfigPayloads.SaveLobby.CODEC, b -> {
            b.writeUUID(REQUEST); b.writeVarLong(0); b.writeVarInt(LobbyModel.MAX_JSON_BYTES + 1);
        }, "room decoder rejects oversize declared length before allocating");
        decodeRejects(MatchConfigPayloads.SaveLobby.CODEC, b -> {
            b.writeUUID(REQUEST); b.writeVarLong(0); b.writeByteArray(new byte[]{(byte) 0xC3, 0x28});
        }, "room decoder rejects malformed UTF-8");
        decodeRejects(MatchConfigPayloads.SaveLobby.CODEC, b -> {
            b.writeUUID(REQUEST); b.writeVarLong(0); b.writeVarInt(12); b.writeByte(123);
        }, "room decoder rejects truncated byte array");
        for (String invalid : List.of("{}", "[]", "null", "{\"mapId\":1,\"mapId\":2,\"teamSize\":1,\"members\":[]}",
                LobbyModel.toJson(room(20)).replace("\"teamSize\":4", "\"teamSize\":0"))) {
            var decoded = roundTrip(MatchConfigPayloads.SaveLobby.CODEC, new MatchConfigPayloads.SaveLobby(REQUEST, 0, invalid));
            rejects(() -> LobbyModel.parse(decoded.json()), "bounded room payload remains subject to strict application JSON validation");
        }
        String rules = MatchConfigModel.toJson(MatchConfigModel.defaults(7));
        var matchSave = new MatchConfigPayloads.Save(REQUEST, 4, rules);
        check(roundTrip(MatchConfigPayloads.Save.CODEC, matchSave).equals(matchSave), "match rule save wire remains compatible");
        decodeRejects(MatchConfigPayloads.Save.CODEC, b -> {
            b.writeUUID(REQUEST); b.writeVarLong(0); b.writeByteArray(new byte[]{(byte) 0x80});
        }, "match rule decoder also rejects malformed UTF-8");
    }

    private static void completeSnapshots() {
        var empty = new MatchConfigPayloads.Snapshot(REQUEST, 0, List.of(), 0, null, null,
                MatchConfigPayloads.READ, "请选择地图");
        check(roundTrip(MatchConfigPayloads.Snapshot.CODEC, empty).equals(empty), "empty selection supports a null lobby/status/settings snapshot");
        List<LobbyModel.OnlinePlayer> online = new ArrayList<>();
        for (int i = 0; i < LobbyModel.MAX_ONLINE; i++) online.add(new LobbyModel.OnlinePlayer(new UUID(1234, i),
                "玩家" + i, i % 2 == 0, i % 2 == 0 ? "已在地图内就位" : "请进入地图"));
        var view = new LobbyModel.View(23, room(20), online, "spawning", 9, 10, true, "登记中");
        var header = new MatchConfigPayloads.MapHeader(7, "艾伦格 · 编队", "minecraft:overworld", true);
        var snapshot = new MatchConfigPayloads.Snapshot(REQUEST, 18, List.of(header), 7, MatchConfigModel.defaults(7),
                status(20), MatchConfigPayloads.CONTROLLED, "已提交", view,
                List.of(new MatchConfigPayloads.RulePreset("上一局规则")), "上一局规则");
        var decoded = roundTrip(MatchConfigPayloads.Snapshot.CODEC, snapshot);
        check(decoded.equals(snapshot), "full snapshot round trip preserves 20 seats, 256 candidates, status and both revisions");
        check(decoded.preferredRuleName().equals("上一局规则") && decoded.selectedMapId() == 7,
                "the last-played rule selection travels with the same map's full snapshot");
        check(decoded.lobby().online().get(255).name().equals("玩家255"), "Unicode candidate names survive the full packet");
        online.clear();
        check(view.online().size() == LobbyModel.MAX_ONLINE, "view owns a defensive candidate snapshot");
        rejects(() -> decoded.maps().clear(), "decoded map list is immutable");
        rejects(() -> decoded.lobby().online().clear(), "decoded online list is immutable");
        rejects(() -> decoded.lobby().room().members().clear(), "decoded room owns immutable member list");
        rejects(() -> new MatchConfigPayloads.Snapshot(REQUEST, -2, List.of(), 0, null, null, 0, ""), "snapshot revision lower bound");
        rejects(() -> new MatchConfigPayloads.Snapshot(REQUEST, 0, List.of(), -1, null, null, 0, ""), "snapshot selected map lower bound");
        rejects(() -> new MatchConfigPayloads.Snapshot(REQUEST, 0, List.of(), 0, null, null, 6, ""), "snapshot outcome allowlist");
        rejects(() -> new MatchConfigPayloads.Snapshot(REQUEST, 0, List.of(), 0, null, null, 0, "x".repeat(com.example.examplemod.localization.Texts.MAX_MESSAGE_LENGTH + 1)), "snapshot message bound");
        rejects(() -> new MatchConfigPayloads.Snapshot(REQUEST, 0, List.of(), 8, MatchConfigModel.defaults(7), null, 0, ""), "snapshot settings belong to selected map");
        rejects(() -> new MatchConfigPayloads.Snapshot(REQUEST, 0, List.of(), 8, null, status(20), 0, ""), "snapshot status belongs to selected map");
        rejects(() -> new MatchConfigPayloads.Snapshot(REQUEST, 0, List.of(), 8, null, null, 0, "", view), "snapshot room belongs to selected map");
        rejects(() -> new LobbyModel.View(-1, room(0), List.of(), "idle", 0, 0, true, ""), "room view revision bound");
        rejects(() -> new LobbyModel.View(0, room(0), List.of(), "unknown", 0, 0, true, ""), "room stage allowlist");
        rejects(() -> new LobbyModel.View(0, room(0), List.of(), "spawning", -1, 1, true, ""), "room ready count lower bound");
        rejects(() -> new LobbyModel.View(0, room(0), List.of(), "spawning", 2, 1, true, ""), "room ready count cannot exceed requested");
        rejects(() -> new LobbyModel.View(0, room(0), List.of(), "spawning", 0, 101, true, ""), "room requested count cap");
        rejects(() -> new MatchConfigPayloads.MapHeader(0, "Map", "minecraft:overworld", true), "map header ID bound");
        rejects(() -> new MatchConfigPayloads.MapHeader(7, "Map", "UPPERCASE:invalid", true), "map header dimension syntax");
    }

    private static void malformedSnapshots() {
        for (int count : new int[]{-1, MapZoneModel.MAX_MAPS + 1}) decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            snapshotHeader(b); b.writeVarInt(count);
        }, "snapshot declared map count bound " + count);
        for (int count : new int[]{-1, LobbyModel.MAX_ONLINE + 1}) decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            lobbyPrefix(b); b.writeVarInt(count);
        }, "snapshot declared online count bound " + count);
        decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            lobbyPrefix(b); b.writeVarInt(2);
            for (int i = 0; i < 2; i++) { b.writeUUID(REQUEST); b.writeUtf("Steve"); b.writeBoolean(true); b.writeUtf(""); }
            lobbySuffix(b, "idle", 0, 0);
        }, "snapshot rejects duplicate candidate UUIDs");
        decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            lobbyPrefix(b); b.writeVarInt(1); b.writeUUID(REQUEST); b.writeUtf("x".repeat(33)); b.writeBoolean(true); b.writeUtf("");
            lobbySuffix(b, "idle", 0, 0);
        }, "snapshot bounds candidate name before creating the view");
        decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            lobbyPrefix(b); b.writeVarInt(1); b.writeUUID(REQUEST); b.writeUtf("Steve"); b.writeBoolean(true); b.writeUtf("x".repeat(257));
            lobbySuffix(b, "idle", 0, 0);
        }, "snapshot bounds candidate eligibility message");
        for (String stage : List.of("unknown", "finished")) decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            lobbyPrefix(b); b.writeVarInt(0); lobbySuffix(b, stage, 0, 0);
        }, "snapshot rejects invalid room stage " + stage);
        for (int[] counts : List.of(new int[]{-1, 0}, new int[]{2, 1}, new int[]{0, 101}))
            decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
                lobbyPrefix(b); b.writeVarInt(0); lobbySuffix(b, "spawning", counts[0], counts[1]);
            }, "snapshot validates ready/requested count relation");
        decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            snapshotHeader(b); b.writeVarInt(0); b.writeBoolean(true);
            var rules = JsonParser.parseString(MatchConfigModel.toJson(MatchConfigModel.defaults(7))).getAsJsonObject();
            rules.addProperty("maxPlayers", 101); b.writeByteArray(rules.toString().getBytes(StandardCharsets.UTF_8));
        }, "snapshot template cannot smuggle a 21-player setting");
        decodeRejects(MatchConfigPayloads.Snapshot.CODEC, b -> {
            snapshotHeader(b); b.writeVarInt(0); b.writeBoolean(false); b.writeBoolean(false); b.writeBoolean(true);
            b.writeVarLong(0); b.writeByteArray(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80});
        }, "snapshot lobby JSON rejects UTF-8 encoded surrogate");
    }

    private static void statusAndHudPackets() {
        for (MatchStatus status : new MatchStatus[]{null, status(1), status(20)}) {
            var hud = new MatchConfigPayloads.Hud(91, status);
            check(roundTrip(MatchConfigPayloads.Hud.CODEC, hud).equals(hud), "HUD status boundary round trip");
        }
        rejects(() -> new MatchConfigPayloads.Hud(-1, null), "HUD sequence remains nonnegative");
        for (int total : new int[]{0, 101}) decodeRejects(MatchConfigPayloads.Hud.CODEC, b -> {
            b.writeVarLong(0); writeStatus(b, total, total, Math.min(total, 2), "preparing", null);
        }, "HUD decoder enforces total roster bound " + total);
        decodeRejects(MatchConfigPayloads.Hud.CODEC, b -> {
            b.writeVarLong(0); writeStatus(b, 20, 21, 2, "preparing", null);
        }, "HUD alive count belongs to frozen roster");
        decodeRejects(MatchConfigPayloads.Hud.CODEC, b -> {
            b.writeVarLong(0); writeStatus(b, 20, 2, 3, "preparing", null);
        }, "HUD team count belongs to alive roster");
        decodeRejects(MatchConfigPayloads.Hud.CODEC, b -> {
            b.writeVarLong(0); writeStatus(b, 20, 20, 5, "invalid", null);
        }, "HUD stage allowlist");
        for (Circle circle : List.of(new Circle(Double.NaN, 0, 10), new Circle(0, 0, 0),
                new Circle(0, 0, MapZoneModel.MAX_RADIUS + 1), new Circle(MapZoneModel.MAX_COORDINATE + 1, 0, 10)))
            decodeRejects(MatchConfigPayloads.Hud.CODEC, b -> {
                b.writeVarLong(0); writeStatus(b, 20, 20, 5, "preparing", circle);
            }, "HUD circle rejects invalid geometry");
    }

    private static LobbyModel.Room room(int count) {
        List<LobbyModel.Member> members = new ArrayList<>();
        for (int i = 0; i < count; i++) members.add(new LobbyModel.Member(new UUID(99, i), "成员" + i,
                i % 2 == 0 ? "human" : "bot", i / 4 + 1, i % 4 + 1, i % 3 == 0 ? "hard" : "normal",
                i % 3 == 0 ? "scavenger" : i % 3 == 1 ? "smg" : "rifle"));
        return new LobbyModel.Room(7, 4, members);
    }
    private static MatchStatus status(int total) {
        return new MatchStatus(7, SESSION, "preparing", 0, 9, 30, total, Math.min(total, 5), total,
                "", new Circle(0, 0, 128), new Circle(1, 2, 64));
    }
    private static void snapshotHeader(RegistryFriendlyByteBuf b) {
        b.writeUUID(REQUEST); b.writeVarLong(0); b.writeVarLong(7); b.writeVarInt(0); b.writeUtf("");
    }
    private static void lobbyPrefix(RegistryFriendlyByteBuf b) {
        snapshotHeader(b); b.writeVarInt(0); b.writeBoolean(false); b.writeBoolean(false); b.writeBoolean(true);
        b.writeVarLong(0); b.writeByteArray(LobbyModel.toJson(room(0)).getBytes(StandardCharsets.UTF_8));
    }
    private static void lobbySuffix(RegistryFriendlyByteBuf b, String stage, int ready, int requested) {
        b.writeUtf(stage); b.writeVarInt(ready); b.writeVarInt(requested); b.writeBoolean(true); b.writeUtf("");
    }
    private static void writeStatus(RegistryFriendlyByteBuf b, int total, int alive, int teams, String stage, Circle circle) {
        b.writeBoolean(true); b.writeVarLong(7); b.writeUUID(SESSION); b.writeUtf(stage); b.writeVarInt(0); b.writeVarInt(9);
        b.writeVarInt(30); b.writeVarInt(alive); b.writeVarInt(teams); b.writeVarInt(total); b.writeUtf("");
        b.writeBoolean(circle != null);
        if (circle != null) { b.writeDouble(circle.centerX()); b.writeDouble(circle.centerZ()); b.writeDouble(circle.radius()); }
        b.writeBoolean(false);
    }
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T packet) {
        var b = buffer();
        try { codec.encode(b, packet); T decoded = codec.decode(b); check(!b.isReadable(), "packet consumes its complete encoded frame"); return decoded; }
        finally { b.release(); }
    }
    private static <T> void decodeRejects(StreamCodec<RegistryFriendlyByteBuf, T> codec, Consumer<RegistryFriendlyByteBuf> raw, String reason) {
        var b = buffer(); try { raw.accept(b); rejects(() -> codec.decode(b), reason); } finally { b.release(); }
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); } catch (RuntimeException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + reason);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
}
