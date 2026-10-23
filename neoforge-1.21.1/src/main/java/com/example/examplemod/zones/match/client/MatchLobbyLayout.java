package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.List;

/** Responsive team seats use the same fixed-height rows as the native rule editor. */
public final class MatchLobbyLayout {
    private MatchLobbyLayout() {}

    public static int columns(int rowWidth) { return rowWidth >= 420 ? 4 : rowWidth >= 200 ? 2 : 1; }

    public static List<MatchConfigLayout.Rect> seats(int rowWidth, int count) {
        if (rowWidth < 40 || count < 1 || count > columns(rowWidth)) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_lobby_layout.check_slot_row_width_column_count"));
        int usable = rowWidth - (count - 1) * 4;
        List<MatchConfigLayout.Rect> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int left = usable * index / count + index * 4;
            int right = usable * (index + 1) / count + index * 4;
            result.add(new MatchConfigLayout.Rect(left, 4, right - left, 20));
        }
        return List.copyOf(result);
    }
    public static List<MatchConfigLayout.Rect> overview(MatchConfigLayout.Rect panel) {
        List<MatchConfigLayout.Rect> cells = new ArrayList<>(20);
        int usable = panel.width() - 16 - 3 * 4;
        for (int index = 0; index < 20; index++) {
            int column = index % 4, row = index / 4;
            int left = panel.x() + 8 + usable * column / 4 + column * 4;
            int right = panel.x() + 8 + usable * (column + 1) / 4 + column * 4;
            cells.add(new MatchConfigLayout.Rect(left, panel.y() + 72 + row * 24, right - left, 20));
        }
        return List.copyOf(cells);
    }
}
