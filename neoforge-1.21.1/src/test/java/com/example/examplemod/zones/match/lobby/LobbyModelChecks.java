package com.example.examplemod.zones.match.lobby;

import com.example.examplemod.zones.match.lobby.LobbyModel.Member;
import com.example.examplemod.zones.match.lobby.LobbyModel.Room;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Strict room codec, one hundred-seat rules and exact saved-NBT protection, entirely in memory. */
public final class LobbyModelChecks {
    private static int checks;
    private LobbyModelChecks() {}
    public static void main(String[] args) {
        seatsAndOwnership();
        strictCodec();
        revisionAndIsolation();
        exactNbtProtection();
        System.out.println("LobbyModelChecks: " + checks + " room codec, seat, CAS and NBT-protection checks passed; no game launched.");
    }

    private static void seatsAndOwnership() {
        Room empty = LobbyModel.defaults(7, 4);
        check(empty.mapId() == 7 && empty.teamSize() == 4 && empty.members().isEmpty(), "default room owns its saved map and team size");
        check(LobbyModel.validate(empty).isEmpty(), "authoring permits an empty next-match room");
        check(!LobbyModel.validate(null).isEmpty(), "missing room returns a validation error");
        for (int teamSize = 1; teamSize <= 100; teamSize++) {
            Room full = room(7, teamSize, 100);
            check(LobbyModel.validate(full).isEmpty(), "one hundred bot seats are valid at every authored team size");
            for (int seat = 0; seat < 100; seat++) {
                Member member = full.members().get(seat);
                check(LobbyModel.seatIndex(full, member) == seat, "team and slot resolve to the independently expected linear seat");
                check(LobbyModel.isBot(member), "all one hundred configured bots keep their actor kind");
            }
            check(LobbyModel.parse(LobbyModel.toJson(full)).equals(full), "full room round-trips at each team size");
        }
        Member human = new Member(id("human"), "测试玩家", "human", 1, 1, "normal", "rifle");
        ArrayList<Member> source = new ArrayList<>(List.of(human));
        Room owned = new Room(1, 4, source); source.clear();
        check(owned.members().equals(List.of(human)) && !LobbyModel.isBot(human), "room constructor owns its member list and preserves human kind");
        rejects(() -> owned.members().clear(), "room members are immutable");
        for (long map : new long[]{-1, 0, Long.MAX_VALUE}) invalid(new Room(map, 1, List.of()), "invalid map identifier");
        for (int teamSize : new int[]{-1, 0, 101}) invalid(new Room(1, teamSize, List.of()), "team size stays within one hundred");
        invalid(room(1, 1, 101), "one hundred-first participant exceeds the room cap");
        Room mixed = new Room(1, 4, List.of(human, member(2, 4, "bot", "hard", "smg")));
        check(LobbyModel.validate(mixed).isEmpty(), "human and bot share a common seat limit");
        check(LobbyModel.validate(new Room(1, 3, List.of(new Member(id("tail"), "tail", "bot", 34, 1, "normal", "rifle")))).isEmpty(),
                "partially filled last team exposes its twentieth seat");
        invalid(new Room(1, 3, List.of(new Member(id("tail"), "tail", "bot", 34, 2, "normal", "rifle"))), "last-team slot one hundred-one is rejected");
        invalid(new Room(1, 4, List.of(human, new Member(human.id(), "copy", "bot", 1, 2, "normal", "rifle"))), "duplicate actor IDs are rejected across kinds");
        invalid(new Room(1, 4, List.of(human, new Member(id("other"), "other", "bot", 1, 1, "normal", "rifle"))), "occupied seat is rejected across kinds");
        for (int team : new int[]{0, -1, 26}) invalid(new Room(1, 4, List.of(new Member(id("team"), "team", "bot", team, 1, "normal", "rifle"))), "invalid team coordinate");
        for (int slot : new int[]{0, -1, 5}) invalid(new Room(1, 4, List.of(new Member(id("slot"), "slot", "bot", 1, slot, "normal", "rifle"))), "invalid slot coordinate");
        for (String name : List.of("", " ", "x".repeat(33), "bad\nname"))
            invalid(new Room(1, 1, List.of(new Member(id("name"), name, "bot", 1, 1, "normal", "rifle"))), "display name validation");
        invalid(new Room(1, 1, List.of(new Member(null, "id", "bot", 1, 1, "normal", "rifle"))), "member identity is required");
        for (String kind : List.of("player", "fake", "")) invalid(new Room(1, 1, List.of(new Member(id("kind"), "kind", kind, 1, 1, "normal", "rifle"))), "unknown actor kind");
        invalid(new Room(1, 1, List.of(member(1, 1, "bot", "expert", "rifle"))), "unknown difficulty");
        invalid(new Room(1, 1, List.of(member(1, 1, "bot", "normal", "launcher"))), "unknown equipment preset");
        for (String difficulty : List.of("easy", "normal", "hard")) for (String loadout : List.of("rifle", "smg", "scavenger"))
            check(LobbyModel.validate(new Room(1, 1, List.of(member(1, 1, "bot", difficulty, loadout)))).isEmpty(), "every advertised bot setting is accepted");
    }

    private static void strictCodec() {
        String json = LobbyModel.toJson(room(7, 4, 2));
        check(LobbyModel.parse(json).equals(room(7, 4, 2)), "complete lobby codec preserves actor IDs and settings");
        rejects(() -> LobbyModel.parse(json + "{}"), "trailing JSON is rejected");
        rejects(() -> LobbyModel.parse(json.replace("\"mapId\":", "\"mapId\":1,\"mapId\":")), "duplicate root key is rejected");
        rejects(() -> LobbyModel.parse(json.replace("\"name\":", "\"name\":\"duplicate\",\"name\":")), "duplicate member key is rejected");
        for (String value : new String[]{"[]", "null", "true", "\"room\"", "", "{broken"}) rejects(() -> LobbyModel.parse(value), "root shape is strict");
        rejects(() -> LobbyModel.parse(null), "missing JSON is rejected");
        rejects(() -> LobbyModel.parse(" ".repeat(LobbyModel.MAX_JSON_BYTES + 1)), "input byte cap is enforced");
        rejectsJson(json, root -> root.addProperty("unknown", 1), "unknown room key");
        rejectsJson(json, root -> root.remove("teamSize"), "missing room key");
        rejectsJson(json, root -> root.add("members", JsonNull.INSTANCE), "null member array");
        rejectsJson(json, root -> root.add("members", new JsonObject()), "object-shaped member array");
        rejectsJson(json, root -> root.addProperty("mapId", "7"), "numeric string map identifier");
        rejectsJson(json, root -> root.addProperty("mapId", 7.5), "fractional map identifier");
        rejectsJson(json, root -> root.addProperty("mapId", 1e30), "overflowing map identifier");
        rejectsJson(json, root -> root.addProperty("teamSize", "4"), "numeric string team size");
        rejectsJson(json, root -> root.addProperty("teamSize", 1.5), "fractional team size");
        rejectsJson(json, root -> root.addProperty("teamSize", true), "boolean team size");
        for (String field : List.of("id", "name", "kind", "difficulty", "loadout")) {
            rejectsJson(json, root -> first(root).add(field, JsonNull.INSTANCE), "null string field " + field);
            rejectsJson(json, root -> first(root).addProperty(field, 2), "numeric string field " + field);
            rejectsJson(json, root -> first(root).remove(field), "missing member field " + field);
        }
        rejectsJson(json, root -> first(root).addProperty("extra", true), "unknown member field");
        rejectsJson(json, root -> first(root).addProperty("id", "invalid"), "invalid UUID");
        rejectsJson(json, root -> first(root).addProperty("id", "1-1-1-1-1"), "noncanonical shorthand UUID");
        rejectsJson(json, root -> first(root).addProperty("id", id("uppercase").toString().toUpperCase(java.util.Locale.ROOT)), "uppercase noncanonical UUID");
        rejectsJson(json, root -> first(root).addProperty("team", 1.25), "fractional team coordinate");
        rejectsJson(json, root -> first(root).addProperty("slot", "1"), "numeric string slot");
        rejectsJson(json, root -> root.getAsJsonArray("members").set(0, new JsonArray()), "array-shaped member");
        rejectsJson(json, root -> root.getAsJsonArray("members").set(0, JsonNull.INSTANCE), "null member");
        rejectsJson(json, root -> root.getAsJsonArray("members").add(first(root).deepCopy()), "duplicate decoded member identity and seat");
        rejectsJson(json, root -> {
            JsonArray members = root.getAsJsonArray("members");
            while (members.size() < 21) members.add(first(root).deepCopy());
        }, "one hundred-first decoded member");
    }

    private static void revisionAndIsolation() {
        LobbySavedData data = new LobbySavedData();
        check(data.revision() == 0 && !data.hasRoom(1) && data.loadError().isEmpty(), "new saved data has no implicit authored room");
        check(data.room(1, 2).equals(LobbyModel.defaults(1, 2)) && data.revision() == 0 && !data.hasRoom(1), "reading map defaults preserves storage");
        Room first = room(1, 4, 20), second = room(2, 2, 4);
        data.saveRoom(0, first);
        check(data.revision() == 1 && data.hasRoom(1) && data.room(1, 1).equals(first), "first save commits one room with its authored team size");
        CompoundTag baseline = data.save(new CompoundTag(), null);
        rejects(() -> data.saveRoom(0, second), "stale revision is rejected across maps");
        check(data.revision() == 1 && data.save(new CompoundTag(), null).equals(baseline), "CAS failure preserves every serialized field");
        rejects(() -> data.saveRoom(1, room(2, 1, 101)), "invalid room is rejected before storage mutation");
        check(data.revision() == 1 && data.save(new CompoundTag(), null).equals(baseline), "validation failure retains acknowledged data");
        data.saveRoom(1, second);
        check(data.revision() == 2 && data.room(1, 1).equals(first) && data.room(2, 1).equals(second), "per-map rooms retain separate membership and team settings");
        CompoundTag stored = data.save(new CompoundTag(), null);
        LobbySavedData loaded = LobbySavedData.load(stored, null);
        check(loaded.loadError().isEmpty() && loaded.revision() == 2 && loaded.room(1, 1).equals(first) && loaded.room(2, 1).equals(second), "NBT restores complete room configuration and CAS revision");
        check(loaded.save(new CompoundTag(), null).equals(stored), "valid NBT round-trip keeps exact persisted fields");
        loaded.saveRoom(2, LobbyModel.defaults(1, 1));
        check(data.room(1, 1).equals(first) && loaded.room(2, 1).equals(second), "loaded state owns its later modifications");
        for (int map = 3; map <= 256; map++) data.saveRoom(data.revision(), LobbyModel.defaults(map, 1));
        check(data.revision() == 256 && data.hasRoom(256), "two hundred fifty-six map rooms are permitted");
        CompoundTag atCap = data.save(new CompoundTag(), null);
        rejects(() -> data.saveRoom(data.revision(), LobbyModel.defaults(257, 1)), "new map room beyond global cap is rejected");
        check(data.save(new CompoundTag(), null).equals(atCap), "map-cap rejection preserves all earlier rooms");
        data.saveRoom(data.revision(), LobbyModel.defaults(1, 2));
        check(data.revision() == 257 && data.room(1, 1).teamSize() == 2, "existing map room remains editable at room-cap boundary");
        CompoundTag maxRevision = stored.copy(); maxRevision.putLong("revision", Long.MAX_VALUE);
        LobbySavedData saturated = LobbySavedData.load(maxRevision, null);
        rejects(() -> saturated.saveRoom(Long.MAX_VALUE, LobbyModel.defaults(1, 1)), "revision overflow is rejected");
        check(saturated.revision() == Long.MAX_VALUE && saturated.save(new CompoundTag(), null).equals(maxRevision), "overflow rejection preserves maximum revision and NBT");
    }

    private static void exactNbtProtection() {
        LobbySavedData blank = LobbySavedData.load(new CompoundTag(), null);
        check(blank.loadError().isEmpty() && blank.revision() == 0 && !blank.hasRoom(1), "empty NBT retains new-save defaults");
        LobbySavedData data = new LobbySavedData(); data.saveRoom(0, room(1, 4, 3));
        CompoundTag baseline = data.save(new CompoundTag(), null);
        corrupt(baseline, tag -> tag.putInt("version", 2), "future NBT version");
        corrupt(baseline, tag -> tag.remove("version"), "missing NBT version");
        corrupt(baseline, tag -> tag.putString("version", "1"), "wrong version tag type");
        corrupt(baseline, tag -> tag.remove("revision"), "missing revision");
        corrupt(baseline, tag -> tag.putInt("revision", 1), "int revision instead of long");
        corrupt(baseline, tag -> tag.putLong("revision", -1), "negative NBT revision");
        corrupt(baseline, tag -> tag.putString("extra", "untouched"), "unknown root field");
        corrupt(baseline, tag -> tag.putString("rooms", "wrong"), "wrong rooms tag type");
        corrupt(baseline, tag -> { ListTag rooms = new ListTag(); rooms.add(IntTag.valueOf(1)); tag.put("rooms", rooms); }, "integer room list");
        corrupt(baseline, tag -> { ListTag rooms = new ListTag(); rooms.add(new CompoundTag()); tag.put("rooms", rooms); }, "compound room list");
        corrupt(baseline, tag -> { ListTag rooms = new ListTag(); rooms.add(StringTag.valueOf("{}")); tag.put("rooms", rooms); }, "invalid room JSON");
        corrupt(baseline, tag -> {
            ListTag rooms = tag.getList("rooms", 8); rooms.add(StringTag.valueOf(LobbyModel.toJson(LobbyModel.defaults(1, 1))));
        }, "duplicate saved map room");
        corrupt(baseline, tag -> {
            ListTag rooms = new ListTag(); rooms.add(StringTag.valueOf(LobbyModel.toJson(room(1, 1, 2)))); rooms.add(StringTag.valueOf("{}")); tag.put("rooms", rooms);
        }, "valid prefix before malformed second room");
        corrupt(baseline, tag -> {
            ListTag rooms = new ListTag();
            for (int map = 1; map <= 257; map++) rooms.add(StringTag.valueOf(LobbyModel.toJson(LobbyModel.defaults(map, 1))));
            tag.put("rooms", rooms);
        }, "too many saved room entries");
        CompoundTag minimal = baseline.copy(); minimal.put("rooms", new ListTag());
        check(LobbySavedData.load(minimal, null).loadError().isEmpty(), "empty typed room list is a valid persisted authoring state");
    }

    private static void corrupt(CompoundTag baseline, Consumer<CompoundTag> mutation, String reason) {
        CompoundTag broken = baseline.copy(); mutation.accept(broken); CompoundTag exact = broken.copy();
        LobbySavedData data = LobbySavedData.load(broken, null);
        check(!data.loadError().isEmpty(), reason + " yields a load error");
        check(data.save(new CompoundTag(), null).equals(exact), reason + " preserves exact original NBT");
        rejects(() -> data.room(1, 1), reason + " keeps protected data from silently becoming defaults");
        rejects(() -> data.saveRoom(data.revision(), LobbyModel.defaults(1, 1)), reason + " guards the saved state from replacement");
        broken.putString("outsideMutation", "after load");
        check(data.save(new CompoundTag(), null).equals(exact), reason + " protects against later mutation of caller NBT");
        CompoundTag returned = data.save(new CompoundTag(), null); returned.putString("outsideMutation", "after save");
        check(data.save(new CompoundTag(), null).equals(exact), reason + " owns every returned protected-NBT copy");
    }
    private static Room room(long map, int teamSize, int count) {
        List<Member> members = new ArrayList<>();
        for (int index = 0; index < count; index++) members.add(new Member(id("bot-" + index), "BOT " + (index + 1), "bot",
                index / teamSize + 1, index % teamSize + 1, "normal", "rifle"));
        return new Room(map, teamSize, members);
    }
    private static Member member(int position, int teamSize, String kind, String difficulty, String loadout) {
        return new Member(id(kind + "-" + position), "member " + position, kind, (position - 1) / teamSize + 1, (position - 1) % teamSize + 1, difficulty, loadout);
    }
    private static UUID id(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject first(JsonObject root) { return root.getAsJsonArray("members").get(0).getAsJsonObject(); }
    private static void invalid(Room room, String reason) {
        check(!LobbyModel.validate(room).isEmpty(), reason + " fails model validation");
        rejects(() -> LobbyModel.toJson(room), reason + " fails serialization");
    }
    private static void rejectsJson(String json, Consumer<JsonObject> mutation, String reason) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject(); mutation.accept(root); rejects(() -> LobbyModel.parse(root.toString()), reason);
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + reason);
    }
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}
