package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.match.lobby.LobbyModel;
import com.example.examplemod.zones.match.lobby.LobbyModel.Member;
import com.example.examplemod.zones.match.lobby.LobbyModel.Room;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Independent room draft. Rule form input and server polling never replace these edits. */
final class MatchLobbyDraft {
    private final long mapId;
    private int teamSize;
    private final List<Member> members = new ArrayList<>();

    MatchLobbyDraft(Room room) { mapId = room.mapId(); teamSize = room.teamSize(); members.addAll(room.members()); }
    Room room() { return new Room(mapId, teamSize, List.copyOf(members)); }
    int teamSize() { return teamSize; }
    int size() { return members.size(); }
    long bots() { return members.stream().filter(LobbyModel::isBot).count(); }
    Member member(UUID id) { return members.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null); }
    Member at(int position) { return members.stream().filter(m -> position(m) == position).findFirst().orElse(null); }
    int position(Member member) { return (member.team() - 1) * teamSize + member.slot(); }
    int firstVacant(int capacity) {
        for (int position = 1; position <= Math.clamp(capacity, 1, 100); position++) if (at(position) == null) return position;
        return 0;
    }
    void teamSize(int value) {
        if (value < 1 || value > LobbyModel.MAX_PLAYERS) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_lobby_draft.players_per_team"));
        List<Integer> positions = members.stream().map(this::position).toList();
        teamSize = value;
        for (int i = 0; i < members.size(); i++) members.set(i, seat(members.get(i), positions.get(i)));
    }
    /** Keep the first capacity seats; changing team size must not move surviving participants. */
    int applyRules(int size, int capacity) {
        if (capacity < 1 || capacity > 100) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.match_lobby_draft.invalid_capacity"));
        teamSize(size);
        int before = members.size();
        members.removeIf(member -> position(member) > capacity);
        return before - members.size();
    }
    void add(Member value, int position) {
        if (size() >= 100 || position < 1 || position > 100 || at(position) != null || member(value.id()) != null)
            throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_lobby_draft.choose_available_slots_each_member_occupies_one_slot"));
        members.add(seat(value, position));
    }
    void move(UUID id, int position) {
        Member current = member(id);
        if (current == null || position < 1 || position > 100 || at(position) != null)
            throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_lobby_draft.select_member_empty_slot"));
        replace(seat(current, position));
    }
    void botSettings(UUID id, String difficulty, String loadout) {
        Member current = member(id);
        if (current == null || !LobbyModel.isBot(current)) return;
        replace(new Member(current.id(), current.name(), current.kind(), current.team(), current.slot(), difficulty, loadout));
    }
    void remove(UUID id) { members.removeIf(member -> member.id().equals(id)); }
    void removeBots() { members.removeIf(LobbyModel::isBot); }
    private Member seat(Member value, int position) {
        return new Member(value.id(), value.name(), value.kind(), (position - 1) / teamSize + 1,
                (position - 1) % teamSize + 1, value.difficulty(), value.loadout());
    }
    private void replace(Member value) {
        for (int i = 0; i < members.size(); i++) if (members.get(i).id().equals(value.id())) { members.set(i, value); return; }
    }
}
