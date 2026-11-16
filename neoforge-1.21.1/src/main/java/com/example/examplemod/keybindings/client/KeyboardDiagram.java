package com.example.examplemod.keybindings.client;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ANSI 104 layout and mouse. Drawing and hit testing share the very same key geometry. */
public final class KeyboardDiagram {
    public static final int TEXT = 0xFFECF3F5;
    public static final int MUTED = 0xFFAABFC7;
    public static final int CYAN = 0xFF57E8FF;
    public static final int YELLOW = 0xFFE3C56E;
    public static final int RED = 0xFFF77D78;
    private static final float WIDTH = 1200, HEIGHT = 312, GAP = 4;
    private static final KeyState EMPTY = new KeyState(false, false, false);
    private static final List<KeyBox> KEYS = makeKeyboard();
    private static final List<String> SUPPLEMENT = List.of("ControlAny", "ShiftAny", "AltAny", "ScrollUp", "ScrollDown");
    private static final Map<String, String> EXTRA_LABELS = Map.of(
            "ControlAny", "Ctrl · 左右通用", "ShiftAny", "Shift · 左右通用", "AltAny", "Alt · 左右通用",
            "ScrollUp", "滚轮 ↑", "ScrollDown", "滚轮 ↓");
    private final List<KeyBox> renderedKeys = new ArrayList<>();
    private float left, top, scale = 1;
    private float layoutHeight = HEIGHT;

    public record KeyState(boolean bound, boolean conflict, boolean selected) {}
    private record KeyBox(String id, String label, float x, float y, float width, float height,
                          float tl, float tr, float br, float bl, float fontSize) {
        boolean contains(float px, float py) {
            if (px < x || py < y || px >= x + width || py >= y + height) return false;
            // Exclude transparent rounded corners, including the asymmetrical mouse buttons.
            return insideCorner(px - x, py - y, tl)
                    && insideCorner(x + width - px, py - y, tr)
                    && insideCorner(x + width - px, y + height - py, br)
                    && insideCorner(px - x, y + height - py, bl);
        }
        private static boolean insideCorner(float x, float y, float radius) {
            if (x >= radius || y >= radius) return true;
            // Same quadratic boundary as BindingVisuals.corners(), rather than a rectangular hit target.
            return Math.sqrt(x / radius) + Math.sqrt(y / radius) >= 1;
        }
    }

    public float height(float width) { return layoutHeight * width / WIDTH; }

    public Set<String> keyIds() {
        Set<String> result = new LinkedHashSet<>();
        for (KeyBox key : KEYS) result.add(key.id);
        result.addAll(SUPPLEMENT);
        return Collections.unmodifiableSet(result);
    }

    public void render(GuiGraphics graphics, BindingVisuals visuals, float x, float y, float width,
                       Map<String, KeyState> states, String hovered) {
        left = x; top = y; scale = width / WIDTH;
        renderedKeys.clear();
        renderedKeys.addAll(KEYS);
        List<String> supplements = new ArrayList<>(SUPPLEMENT);
        for (String id : states.keySet().stream().sorted().toList()) {
            if (KEYS.stream().noneMatch(k -> k.id.equals(id)) && !supplements.contains(id)) supplements.add(id);
        }
        int columns = Math.min(8, supplements.size());
        float extraWidth = Math.min(140, (WIDTH - (columns - 1) * 8) / columns);
        int extraRows = (supplements.size() + columns - 1) / columns;
        layoutHeight = HEIGHT + (extraRows - 1) * 33;
        for (int i = 0; i < supplements.size(); i++) {
            String id = supplements.get(i);
            renderedKeys.add(new KeyBox(id, EXTRA_LABELS.getOrDefault(id, id), (i % columns) * (extraWidth + 8), 285 + (i / columns) * 33,
                    extraWidth, 27, 5, 5, 5, 5, 11));
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        try {
            visuals.text(graphics, "键盘", 0, 0, 12, MUTED);
            visuals.text(graphics, "ANSI · 104", 628, 1, 10, 0xFF78919C);
            visuals.text(graphics, "导航区", 718, 0, 12, MUTED);
            visuals.text(graphics, "数字区", 876, 0, 12, MUTED);
            visuals.text(graphics, "鼠标", 1082, 0, 12, MUTED);
            // Outline, palm rest, and five independent button regions share the keyboard palette.
            visuals.cornerRect(graphics, 1083, 65, 106, 194, 52, 52, 43, 43,
                    0xFF2F3E43, 0xFF131D20, 0xFF829399);
            visuals.roundRect(graphics, 1128, 209, 16, 4, 2, 0xFF486068, 0);
            visuals.textCentered(graphics, "侧键 4 / 5", 1136, 266, 10, 0xFF9DB2BA);
            for (KeyBox key : renderedKeys) {
                drawKey(graphics, visuals, key, states.getOrDefault(key.id, EMPTY), key.id.equals(hovered));
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    public String hit(double x, double y) {
        float px = ((float) x - left) / scale, py = ((float) y - top) / scale;
        // Middle and side buttons sit above the body and left/right buttons.
        for (int i = renderedKeys.size() - 1; i >= 0; i--) {
            if (renderedKeys.get(i).contains(px, py)) return renderedKeys.get(i).id;
        }
        return null;
    }

    private static void drawKey(GuiGraphics graphics, BindingVisuals visuals, KeyBox key, KeyState state, boolean hovered) {
        int top = 0xFF13191D, bottom = 0xFF080D10, label = 0xFFD1D9DE;
        if (state.bound) { top = 0xFF806D35; bottom = 0xFF5B4C25; label = 0xFFFFF1BC; }
        if (state.conflict) { top = 0xFFA54D49; bottom = 0xFF703735; label = 0xFFFFF0EF; }
        if (state.selected) { top = 0xFF69EDFF; bottom = 0xFF25AABE; label = 0xFF06232C; }
        if (hovered) { top = brighten(top); bottom = brighten(bottom); }
        if (state.selected) {
            visuals.roundRect(graphics, key.x - 2, key.y - 2, key.width + 4, key.height + 4, 7, 0x1857E8FF, 0);
        }
        visuals.cornerRect(graphics, key.x, key.y, key.width, key.height, key.tl, key.tr, key.br, key.bl,
                top, bottom, hovered ? 0xFFFFFFFF : 0xC0FFFFFF);
        float size = key.fontSize;
        String text = visuals.ellipsize(key.label, size, key.width - 5);
        float center = key.x + key.width / 2;
        float textTop = key.y + (key.height - size * 1.3f) / 2;
        if (key.id.equals("Mouse1") || key.id.equals("Mouse2")) textTop += 18;
        visuals.textCentered(graphics, text, center, textTop, size, label);
        if (state.selected && state.conflict) {
            visuals.roundRect(graphics, key.x + key.width - 9, key.y + 3, 6, 6, 3, 0xFFEF6864, 0xFFFFFFFF);
        }
    }

    private static int brighten(int color) {
        int red = Math.min(255, (int) ((color >> 16 & 255) * 1.15f));
        int green = Math.min(255, (int) ((color >> 8 & 255) * 1.15f));
        int blue = Math.min(255, (int) ((color & 255) * 1.15f));
        return (color & 0xFF000000) | red << 16 | green << 8 | blue;
    }

    private static List<KeyBox> makeKeyboard() {
        List<KeyBox> keys = new ArrayList<>();
        // Main block: 15 standard units with staggered ANSI rows.
        row(keys, 65, new String[]{"Backquote|`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                "Minus|−", "Equal|=", "Backspace|⌫|2"});
        row(keys, 107, new String[]{"Tab|Tab|1.5", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
                "BracketLeft|[", "BracketRight|]", "Backslash|\\|1.5"});
        row(keys, 149, new String[]{"CapsLock|Caps|1.75", "A", "S", "D", "F", "G", "H", "J", "K", "L",
                "Semicolon|;", "Quote|'", "Enter|Enter|2.25"});
        row(keys, 191, new String[]{"ShiftLeft|Shift|2.25", "Z", "X", "C", "V", "B", "N", "M",
                "Comma|,", "Period|.", "Slash|/", "ShiftRight|Shift|2.75"});
        row(keys, 233, new String[]{"ControlLeft|Ctrl|1.25", "MetaLeft|Win|1.25", "AltLeft|Alt|1.25",
                "Space|空格|6.25", "AltRight|Alt|1.25", "MetaRight|Win|1.25", "ContextMenu|≡|1.25", "ControlRight|Ctrl|1.25"});
        add(keys, "Esc", "Esc", 0, 22, 43, 32, 11);
        for (int i = 0; i < 12; i++) {
            float x = 94 + i * 47 + (i / 4) * 20;
            add(keys, "F" + (i + 1), "F" + (i + 1), x, 22, 43, 32, 11);
        }
        String[] navTop = {"PrintScreen|PrtSc", "ScrollLock|ScrLk", "Pause|Pause"};
        for (int i = 0; i < navTop.length; i++) {
            String[] pair = navTop[i].split("\\|");
            add(keys, pair[0], pair[1], 718 + i * 48, 22, 44, 32, 10);
        }
        String[] nav = {"Insert|Ins", "Home|Home", "PageUp|PgUp", "Delete|Del", "End|End", "PageDown|PgDn"};
        for (int i = 0; i < nav.length; i++) {
            String[] pair = nav[i].split("\\|");
            add(keys, pair[0], pair[1], 718 + (i % 3) * 48, 65 + (i / 3) * 42, 44, 38, 10);
        }
        add(keys, "ArrowUp", "↑", 766, 191, 44, 38, 16);
        add(keys, "ArrowLeft", "←", 718, 233, 44, 38, 16);
        add(keys, "ArrowDown", "↓", 766, 233, 44, 38, 16);
        add(keys, "ArrowRight", "→", 814, 233, 44, 38, 16);
        String[] numTop = {"NumLock|Num", "NumpadDivide|/", "NumpadMultiply|*", "NumpadSubtract|−"};
        for (int i = 0; i < numTop.length; i++) {
            String[] pair = numTop[i].split("\\|");
            add(keys, pair[0], pair[1], 876 + i * 48, 65, 44, 38, 12);
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int digit = 7 - row * 3 + col;
            add(keys, "Numpad" + digit, "" + digit, 876 + col * 48, 107 + row * 42, 44, 38, 13);
        }
        add(keys, "NumpadAdd", "+", 1020, 107, 44, 80, 18);
        add(keys, "NumpadEnter", "Enter", 1020, 191, 44, 80, 10);
        add(keys, "Numpad0", "0", 876, 233, 92, 38, 13);
        add(keys, "NumpadDecimal", ".", 972, 233, 44, 38, 13);
        keys.add(new KeyBox("Mouse1", "左键", 1087, 69, 48, 86, 41, 5, 6, 6, 11));
        keys.add(new KeyBox("Mouse2", "右键", 1138, 69, 47, 86, 5, 40, 6, 6, 11));
        keys.add(new KeyBox("Mouse3", "中", 1125, 88, 22, 37, 7, 7, 7, 7, 10));
        add(keys, "Mouse4", "4", 1076, 150, 16, 31, 10);
        add(keys, "Mouse5", "5", 1076, 185, 16, 31, 10);
        return List.copyOf(keys);
    }

    private static void row(List<KeyBox> keys, float y, String[] definitions) {
        float x = 0;
        for (String definition : definitions) {
            String[] parts = definition.split("\\|");
            String id = parts[0], label = parts.length > 1 ? parts[1] : id;
            float units = parts.length > 2 ? Float.parseFloat(parts[2]) : 1;
            float width = units * 700 / 15 - GAP;
            add(keys, id, label, x, y, width, 38, label.length() > 3 ? 11 : 13);
            x += width + GAP;
        }
    }

    private static void add(List<KeyBox> keys, String id, String label, float x, float y, float w, float h, float font) {
        keys.add(new KeyBox(id, label, x, y, w, h, 5, 5, 5, 5, font));
    }
}
