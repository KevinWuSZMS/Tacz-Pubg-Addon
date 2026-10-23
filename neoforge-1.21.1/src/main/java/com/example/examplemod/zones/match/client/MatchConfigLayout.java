package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.List;
import com.example.examplemod.configui.client.ConfigUiLayout;

/** Pure native form geometry, shared by actual widgets and checks without starting Minecraft. */
public final class MatchConfigLayout {
    public static final int ROW_HEIGHT = 28;
    public static final int WIDE_PHASE_MIN = 400;
    private static final int MARGIN = 8, GAP = 8;
    private MatchConfigLayout() {}

    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
    }
    public record FieldCell(Rect label, Rect control) {}
    public record Frame(Rect form, Rect preview, int messageY, int firstFooterY, int secondFooterY) {
        public boolean hasPreview() { return preview.width() > 0; }
    }

    public static Frame forViewport(int width, int height) {
        int available = Math.max(80, width - MARGIN * 2);
        boolean split = width >= 700 && height >= 340;
        int sidebar = split ? Math.clamp(width / 4, 180, 230) : 0;
        int formWidth = available - (split ? sidebar + GAP : 0);
        int contentHeight = Math.max(28, height - 76 - ConfigUiLayout.CONTENT_TOP);
        Rect form = new Rect(MARGIN, ConfigUiLayout.CONTENT_TOP, formWidth, contentHeight);
        Rect preview = new Rect(form.right() + GAP, form.y(), sidebar, contentHeight);
        return new Frame(form, preview, height - 67, height - 52, height - 28);
    }

    public static List<Rect> footer(int width, int height, int row, int count) {
        if (count < 1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.match_config_layout.footer_count"));
        int available = Math.max(count, width - MARGIN * 2 - (count - 1) * 4);
        int y = row == 0 ? height - 52 : height - 28;
        List<Rect> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int left = MARGIN + available * i / count + i * 4;
            int right = MARGIN + available * (i + 1) / count + i * 4;
            cells.add(new Rect(left, y, right - left, 20));
        }
        return List.copyOf(cells);
    }

    public static List<FieldCell> fields(int rowWidth, int count) {
        if (count < 1 || count > 2) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.match_config_layout.field_column_count"));
        int usable = rowWidth - (count - 1) * GAP;
        List<FieldCell> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int left = usable * i / count + i * GAP;
            int cellWidth = usable * (i + 1) / count - usable * i / count;
            int labelWidth = Math.min(132, Math.max(48, cellWidth / 2 - 4));
            result.add(new FieldCell(new Rect(left, 4, labelWidth, 20),
                    new Rect(left + labelWidth + 4, 4, cellWidth - labelWidth - 4, 20)));
        }
        return List.copyOf(result);
    }

    /** Index, wait, shrink, radius %, HP/s, random-center toggle, remove. */
    public static List<Rect> phaseColumns(int rowWidth) {
        if (rowWidth < WIDE_PHASE_MIN) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.match_config_layout.use_stacked_phase_fields_at_narrow_widths"));
        int numbers = rowWidth - 24 - 56 - 24 - 6 * 4;
        List<Rect> result = new ArrayList<>(7);
        int x = 0;
        result.add(new Rect(x, 4, 24, 20)); x += 28;
        for (int i = 0; i < 4; i++) {
            int cell = numbers * (i + 1) / 4 - numbers * i / 4;
            result.add(new Rect(x, 4, cell, 20)); x += cell + 4;
        }
        result.add(new Rect(x, 4, 56, 20)); x += 60;
        result.add(new Rect(x, 4, 24, 20));
        return List.copyOf(result);
    }
}
