package com.example.examplemod.configui.client;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.List;

/** Native coordinates shared by the four approved authoring tabs; no game state is needed. */
public final class ConfigUiLayout {
    public static final int TABS_Y = 34;
    public static final int TABS_HEIGHT = 22;
    public static final int CONTENT_TOP = 64;
    public static final int MARGIN = 8;
    public static final int TAB_GAP = 4;
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }
    private ConfigUiLayout() {}
    public static List<Rect> tabs(int width) {
        if (width < 160) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.config_ui_layout.configuration_viewport_is_narrower_than_supported_minecraft_gui"));
        int available = width - MARGIN * 2 - TAB_GAP * 3;
        int base = available / 4, remainder = available % 4, x = MARGIN;
        List<Rect> result = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            int w = base + (i < remainder ? 1 : 0);
            result.add(new Rect(x, TABS_Y, w, TABS_HEIGHT));
            x += w + TAB_GAP;
        }
        return List.copyOf(result);
    }
}
