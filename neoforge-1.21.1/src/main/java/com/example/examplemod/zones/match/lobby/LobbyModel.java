package com.example.examplemod.zones.match.lobby;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A saved room owns one hundred explicit seats; a running match uses a separate frozen roster. */
public final class LobbyModel {
    public static final int MAX_PLAYERS = 100, MAX_BOTS = 100, MAX_ONLINE = 256, MAX_JSON_BYTES = 64 * 1024;
    public static final Set<String> DIFFICULTIES = Set.of("easy", "normal", "hard");
    public static final Set<String> LOADOUTS = Set.of("rifle", "smg", "scavenger");
    private static final Gson GSON = new Gson();
    private LobbyModel() {}
    public record Member(UUID id, String name, String kind, int team, int slot, String difficulty, String loadout) {}
    public record Room(long mapId, int teamSize, List<Member> members) {
        public Room { members = List.copyOf(Objects.requireNonNull(members)); }
    }
    public record OnlinePlayer(UUID id, String name, boolean eligible, String message) {
        public OnlinePlayer { Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(message); }
    }
    public record View(long revision, Room room, List<OnlinePlayer> online, String stage,
            int readyBots, int requestedBots, boolean botsAvailable, String message) {
        public View {
            online = List.copyOf(Objects.requireNonNull(online)); Objects.requireNonNull(room); Objects.requireNonNull(message);
            if (revision < 0 || online.size() > MAX_ONLINE || stage == null || !Set.of("idle", "spawning", "running", "failed").contains(stage)
                    || readyBots < 0 || requestedBots < readyBots || requestedBots > MAX_BOTS || message.length() > Texts.MAX_MESSAGE_LENGTH)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_model.check_lobby_status_fields"));
        }
    }
    public static Room defaults(long mapId, int teamSize) { return new Room(mapId, teamSize, List.of()); }
    public static boolean isBot(Member member) { return "bot".equals(member.kind()); }
    public static int seatIndex(Room room, Member member) { return (member.team() - 1) * room.teamSize() + member.slot() - 1; }
    public static List<String> validate(Room room) {
        if (room == null) return List.of(Texts.tr("message.tacz_pubg.lobby_model.select_lobby_first"));
        var errors = new ArrayList<String>();
        if (room.mapId() < 1 || room.mapId() == Long.MAX_VALUE) errors.add(Texts.tr("message.tacz_pubg.lobby_model.select_saved_map"));
        if (room.teamSize() < 1 || room.teamSize() > MAX_PLAYERS) errors.add(Texts.tr("message.tacz_pubg.lobby_model.players_per_team"));
        if (room.members().size() > MAX_PLAYERS) errors.add(Texts.tr("message.tacz_pubg.lobby_model.lobby_allows_most_participants"));
        Set<UUID> ids = new HashSet<>(); Set<Integer> seats = new HashSet<>();
        int bots = 0;
        for (Member m : room.members()) {
            if (m == null) { errors.add(Texts.tr("message.tacz_pubg.lobby_model.valid_member_required")); continue; }
            if (m.id() == null || !ids.add(m.id())) errors.add(Texts.tr("message.tacz_pubg.lobby_model.each_member_have_unique_identity"));
            if (m.name() == null || m.name().isBlank() || m.name().length() > 32 || m.name().codePoints().anyMatch(Character::isISOControl))
                errors.add(Texts.tr("message.tacz_pubg.lobby_model.member_names_contain_displayable_characters"));
            if (!"human".equals(m.kind()) && !"bot".equals(m.kind())) errors.add(Texts.tr("message.tacz_pubg.lobby_model.select_human_bot_member_type"));
            if (room.teamSize() < 1 || room.teamSize() > MAX_PLAYERS || m.team() < 1 || m.team() > MAX_PLAYERS || m.slot() < 1 || m.slot() > room.teamSize()
                    || seatIndex(room, m) < 0 || seatIndex(room, m) >= MAX_PLAYERS || !seats.add(seatIndex(room, m)))
                errors.add(Texts.tr("message.tacz_pubg.lobby_model.assign_each_member_distinct_valid_slot"));
            if (m.difficulty() == null || m.loadout() == null || !DIFFICULTIES.contains(m.difficulty()) || !LOADOUTS.contains(m.loadout())) errors.add(Texts.tr("message.tacz_pubg.lobby_model.select_valid_bot_difficulty_equipment"));
            if (isBot(m)) bots++;
        }
        if (bots > MAX_BOTS) errors.add(Texts.tr("message.tacz_pubg.lobby_model.lobby_allows_most_bots"));
        return List.copyOf(errors);
    }
    public static String toJson(Room room) {
        requireValid(room); String json = GSON.toJson(room);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_model.lobby_configuration_not_exceed_kib"));
        return json;
    }
    public static Room parse(String json) {
        JsonObject root = object(LootConfigModel.readJson(json, MAX_JSON_BYTES), Set.of("mapId", "teamSize", "members"));
        if (!root.get("members").isJsonArray() || root.getAsJsonArray("members").size() > MAX_PLAYERS)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_model.lobby_member_count"));
        List<Member> members = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("members")) {
            JsonObject row = object(e, Set.of("id", "name", "kind", "team", "slot", "difficulty", "loadout"));
            String id = string(row, "id"); UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_model.enter_full_member_uuid"));
            members.add(new Member(uuid, string(row, "name"), string(row, "kind"), integer(row, "team"), integer(row, "slot"),
                    string(row, "difficulty"), string(row, "loadout")));
        }
        long map;
        try { map = number(root, "mapId").getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_model.map_id_integer"), error); }
        Room room = new Room(map, integer(root, "teamSize"), members); requireValid(room); return room;
    }
    public static void requireValid(Room room) {
        var errors = validate(room); if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
    }
    private static JsonObject object(JsonElement value, Set<String> keys) {
        if (value == null || !value.isJsonObject() || !value.getAsJsonObject().keySet().equals(keys))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_model.check_lobby_field_names_completeness"));
        return value.getAsJsonObject();
    }
    private static String string(JsonObject root, String key) {
        var value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.lobby_model.string"));
        return value.getAsString();
    }
    private static JsonElement number(JsonObject root, String key) {
        var value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.lobby_model.number"));
        return value;
    }
    private static int integer(JsonObject root, String key) {
        try { return number(root, key).getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.lobby_model.integer"), error); }
    }
}
