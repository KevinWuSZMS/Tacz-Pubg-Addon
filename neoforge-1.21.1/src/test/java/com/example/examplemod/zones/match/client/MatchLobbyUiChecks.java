package com.example.examplemod.zones.match.client;

import com.example.examplemod.zones.match.lobby.LobbyModel;
import com.example.examplemod.zones.match.lobby.LobbyModel.Member;
import com.example.examplemod.zones.match.lobby.LobbyModel.Room;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Native lobby geometry and draft changes run without a game process. */
public final class MatchLobbyUiChecks {
    private static int checks;
    public static void main(String[] args) {
        for (int viewport : new int[]{240, 280, 320, 426, 480, 640, 700, 854, 960, 1280}) {
            var frame = MatchConfigLayout.forViewport(viewport, 480);
            int width = frame.form().width() - 14;
            for (int teamSize = 1; teamSize <= 20; teamSize++) {
                int columns = MatchLobbyLayout.columns(width);
                int visible = 0;
                for (int offset = 0; offset < teamSize; offset += columns) {
                    int count = Math.min(columns, teamSize - offset), lastRight = -4;
                    for (var cell : MatchLobbyLayout.seats(width, count)) {
                        check(cell.x() >= lastRight + 4 && cell.right() <= width, "team seats fit the scroll row");
                        check(cell.width() >= 40 && cell.height() == 20, "native seats remain actionable");
                        check(cell.bottom() < MatchConfigLayout.ROW_HEIGHT, "seat occupies one reachable row");
                        lastRight = cell.right(); visible++;
                    }
                }
                check(visible == teamSize, "every custom team seat is represented exactly once");
            }
        }
        for (int width : new int[]{700, 854, 960, 1280}) {
            var panel = MatchConfigLayout.forViewport(width, 480).preview();
            var overview = MatchLobbyLayout.overview(panel);
            check(overview.size() == 20, "overview represents all twenty seats");
            for (var cell : overview) {
                check(cell.x() >= panel.x() + 8 && cell.right() <= panel.right() - 8, "overview columns fit sidebar");
                check(cell.width() >= 34 && cell.height() == 20, "overview hit targets remain distinct");
                check(cell.y() >= panel.y() && cell.bottom() < panel.bottom(), "overview remains within native panel");
            }
        }
        UUID humanId = id("human"), botId = id("bot");
        Member human = new Member(humanId, "Player", "human", 1, 1, "normal", "rifle");
        Member bot = new Member(botId, "BOT 01", "bot", 5, 4, "easy", "scavenger");
        Room saved = new Room(7, 4, List.of(human, bot));
        MatchLobbyDraft draft = new MatchLobbyDraft(saved);
        check(draft.room().equals(saved), "server room round-trips through independent lobby draft");
        draft.teamSize(2);
        check(draft.member(humanId).team() == 1 && draft.member(humanId).slot() == 1, "human retains linear seat on team-size change");
        check(draft.member(botId).team() == 10 && draft.member(botId).slot() == 2, "last occupied seat retains its position after regrouping");
        check(draft.position(draft.member(botId)) == 20 && LobbyModel.validate(draft.room()).isEmpty(), "twentieth seat stays valid in smaller teams");
        check(saved.teamSize() == 4 && saved.members().getLast().team() == 5, "editing lobby never mutates acknowledged saved baseline");
        draft.teamSize(3);
        check(draft.member(botId).team() == 7 && draft.member(botId).slot() == 2, "partial last team uses existing twenty seats");
        draft.move(botId, 2);
        check(draft.member(botId).team() == 1 && draft.member(botId).slot() == 2 && draft.firstVacant(20) == 3, "moving into empty seat updates team and first vacancy");
        Room beforeFailure = draft.room();
        boolean rejected = false;
        try { draft.move(botId, 1); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && draft.room().equals(beforeFailure), "occupied-seat move preserves complete draft on failure");
        draft.botSettings(botId, "hard", "smg");
        check(draft.member(botId).difficulty().equals("hard") && draft.member(botId).loadout().equals("smg"), "bot difficulty and equipment update independently");
        draft.botSettings(humanId, "easy", "scavenger");
        check(draft.member(humanId).equals(human), "bot settings retain human member data");
        draft.removeBots();
        check(draft.size() == 1 && draft.bots() == 0 && draft.member(humanId).equals(human), "clear-bots operation preserves assigned human");
        for (int seat = 2; seat <= 20; seat++) draft.add(new Member(id("bot-" + seat), "BOT " + seat, "bot", 1, 1, "normal", "rifle"), seat);
        check(draft.size() == 20 && draft.bots() == 19 && draft.firstVacant(20) == 0, "one human and nineteen bots exhaust twenty seats");
        check(LobbyModel.validate(draft.room()).isEmpty(), "filled lobby satisfies shared room rules");
        Room full = draft.room(); rejected = false;
        try { draft.add(new Member(id("overflow"), "extra", "bot", 1, 1, "normal", "rifle"), 1); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && draft.room().equals(full), "additional bot retains complete full-room draft");
        draft.remove(humanId);
        draft.add(new Member(id("final-bot"), "BOT 20", "bot", 1, 1, "normal", "rifle"), 1);
        check(draft.size() == 20 && draft.bots() == 20 && LobbyModel.validate(draft.room()).isEmpty(), "twenty-bot room fits with all participant slots consumed");
        draft.teamSize(20); draft.teamSize(1);
        check(draft.size() == 20 && draft.room().members().stream().allMatch(m -> m.slot() == 1), "custom maximum team regroups to twenty solo seats");
        check(LobbyModel.parse(LobbyModel.toJson(draft.room())).equals(draft.room()), "edited lobby survives shared save representation");
        MatchLobbyDraft hundred = new MatchLobbyDraft(new Room(8, 4, List.of()));
        for (int seat = 1; seat <= 100; seat++) hundred.add(new Member(id("hundred-" + seat), "BOT " + seat, "bot", 1, 1, "normal", "scavenger"), seat);
        check(hundred.size() == 100 && hundred.firstVacant(100) == 0, "all hundred independently configured slots can be occupied");
        check(hundred.member(id("hundred-100")).team() == 25 && hundred.member(id("hundred-100")).slot() == 4, "hundredth member occupies final squad seat");
        hundred.teamSize(2);
        check(hundred.member(id("hundred-100")).team() == 50 && hundred.member(id("hundred-100")).slot() == 2, "switching to duo preserves hundredth linear seat");
        hundred.remove(id("hundred-99"));
        check(hundred.firstVacant(100) == 99 && hundred.firstVacant(20) == 0, "capacity search respects independent total without losing later seats");
        Room beforeOverflow = hundred.room(); rejected = false;
        try { hundred.move(id("hundred-100"), 101); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && hundred.room().equals(beforeOverflow), "moving beyond hundred preserves every member");
        check(LobbyModel.validate(hundred.room()).isEmpty(), "hundred-seat client draft satisfies authoritative server validation");
        int removed = hundred.applyRules(4, 5);
        check(removed == 94 && hundred.size() == 5, "switching from 99 occupied seats to five removes all overflow");
        check(hundred.member(id("hundred-100")) == null && hundred.member(id("hundred-5")).team() == 2
                && hundred.member(id("hundred-5")).slot() == 1, "smaller preset retains first five linear seats after regrouping");
        check(hundred.room().members().stream().allMatch(m -> hundred.position(m) <= 5), "no invisible overflow participant remains");
        check(hundred.applyRules(2, 100) == 0 && hundred.size() == 5, "raising capacity does not resurrect removed members");
        check(hundred.applyRules(100, 100) == 0 && hundred.room().members().stream().allMatch(m -> m.team() == 1),
                "valid server presets with large custom teams load without throwing on the client");
        var pruned = hundred.room();
        try { hundred.applyRules(4, 0); throw new AssertionError("invalid capacity accepted"); }
        catch (IllegalArgumentException expected) { check(hundred.room().equals(pruned), "invalid capacity leaves grouping and roster untouched"); }
        System.out.println("MatchLobbyUiChecks: " + checks + " responsive lobby and independent draft checks passed; no game launched.");
    }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
