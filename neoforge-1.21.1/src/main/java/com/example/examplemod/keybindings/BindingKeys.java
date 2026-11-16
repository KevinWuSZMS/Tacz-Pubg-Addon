package com.example.examplemod.keybindings;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Stable device IDs shared by the diagram, stored profiles, and physical input router. */
public final class BindingKeys {
    private static final Map<String, Integer> CODES = new LinkedHashMap<>();
    private static final Map<Integer, String> IDS = new LinkedHashMap<>();
    static {
        for (char c = 'A'; c <= 'Z'; c++) add(String.valueOf(c), c);
        for (char c = '0'; c <= '9'; c++) add(String.valueOf(c), c);
        for (int i = 1; i <= 25; i++) add("F" + i, GLFW.GLFW_KEY_F1 + i - 1);
        for (int i = 0; i <= 9; i++) add("Numpad" + i, GLFW.GLFW_KEY_KP_0 + i);
        add("Esc", GLFW.GLFW_KEY_ESCAPE); add("Space", GLFW.GLFW_KEY_SPACE);
        add("Backquote", GLFW.GLFW_KEY_GRAVE_ACCENT); add("Minus", GLFW.GLFW_KEY_MINUS);
        add("Equal", GLFW.GLFW_KEY_EQUAL); add("Backspace", GLFW.GLFW_KEY_BACKSPACE);
        add("Tab", GLFW.GLFW_KEY_TAB); add("BracketLeft", GLFW.GLFW_KEY_LEFT_BRACKET);
        add("BracketRight", GLFW.GLFW_KEY_RIGHT_BRACKET); add("Backslash", GLFW.GLFW_KEY_BACKSLASH);
        add("CapsLock", GLFW.GLFW_KEY_CAPS_LOCK); add("Semicolon", GLFW.GLFW_KEY_SEMICOLON);
        add("Quote", GLFW.GLFW_KEY_APOSTROPHE); add("Enter", GLFW.GLFW_KEY_ENTER);
        add("ShiftLeft", GLFW.GLFW_KEY_LEFT_SHIFT); add("ShiftRight", GLFW.GLFW_KEY_RIGHT_SHIFT);
        add("ControlLeft", GLFW.GLFW_KEY_LEFT_CONTROL); add("ControlRight", GLFW.GLFW_KEY_RIGHT_CONTROL);
        add("AltLeft", GLFW.GLFW_KEY_LEFT_ALT); add("AltRight", GLFW.GLFW_KEY_RIGHT_ALT);
        add("MetaLeft", GLFW.GLFW_KEY_LEFT_SUPER); add("MetaRight", GLFW.GLFW_KEY_RIGHT_SUPER);
        add("ContextMenu", GLFW.GLFW_KEY_MENU); add("Comma", GLFW.GLFW_KEY_COMMA);
        add("Period", GLFW.GLFW_KEY_PERIOD); add("Slash", GLFW.GLFW_KEY_SLASH);
        add("PrintScreen", GLFW.GLFW_KEY_PRINT_SCREEN); add("ScrollLock", GLFW.GLFW_KEY_SCROLL_LOCK);
        add("Pause", GLFW.GLFW_KEY_PAUSE); add("Insert", GLFW.GLFW_KEY_INSERT);
        add("Home", GLFW.GLFW_KEY_HOME); add("PageUp", GLFW.GLFW_KEY_PAGE_UP);
        add("Delete", GLFW.GLFW_KEY_DELETE); add("End", GLFW.GLFW_KEY_END);
        add("PageDown", GLFW.GLFW_KEY_PAGE_DOWN); add("ArrowUp", GLFW.GLFW_KEY_UP);
        add("ArrowDown", GLFW.GLFW_KEY_DOWN); add("ArrowLeft", GLFW.GLFW_KEY_LEFT);
        add("ArrowRight", GLFW.GLFW_KEY_RIGHT); add("NumLock", GLFW.GLFW_KEY_NUM_LOCK);
        add("NumpadDivide", GLFW.GLFW_KEY_KP_DIVIDE); add("NumpadMultiply", GLFW.GLFW_KEY_KP_MULTIPLY);
        add("NumpadSubtract", GLFW.GLFW_KEY_KP_SUBTRACT); add("NumpadAdd", GLFW.GLFW_KEY_KP_ADD);
        add("NumpadEnter", GLFW.GLFW_KEY_KP_ENTER); add("NumpadDecimal", GLFW.GLFW_KEY_KP_DECIMAL);
        add("NumpadEqual", GLFW.GLFW_KEY_KP_EQUAL);
    }
    private BindingKeys() {}
    private static void add(String id, int code) { CODES.put(id, code); IDS.put(code, id); }

    public static String fromKey(InputConstants.Key key) {
        if (key == null || key.equals(InputConstants.UNKNOWN)) return null;
        return switch (key.getType()) {
            case MOUSE -> fromMouse(key.getValue());
            case SCANCODE -> "ScanCode:" + key.getValue();
            case KEYSYM -> IDS.getOrDefault(key.getValue(), "KeyCode:" + key.getValue());
        };
    }

    public static String fromKeyboard(int keysym, int scancode) {
        return fromKey(InputConstants.getKey(keysym, scancode));
    }
    public static String fromMouse(int button) { return "Mouse" + (button + 1); }

    public static InputConstants.Key toKey(String id) {
        if (id == null) return InputConstants.UNKNOWN;
        Integer code = CODES.get(id);
        if (code != null) return InputConstants.Type.KEYSYM.getOrCreate(code);
        try {
            if (id.startsWith("Mouse")) return InputConstants.Type.MOUSE.getOrCreate(Integer.parseInt(id.substring(5)) - 1);
            if (id.startsWith("KeyCode:")) return InputConstants.Type.KEYSYM.getOrCreate(Integer.parseInt(id.substring(8)));
            if (id.startsWith("ScanCode:")) return InputConstants.Type.SCANCODE.getOrCreate(Integer.parseInt(id.substring(9)));
        } catch (NumberFormatException ignored) { }
        return InputConstants.UNKNOWN;
    }

    public static Set<String> aliases(String id) {
        return switch (id) {
            case "ControlAny" -> Set.of("ControlLeft", "ControlRight");
            case "ShiftAny" -> Set.of("ShiftLeft", "ShiftRight");
            case "AltAny" -> Set.of("AltLeft", "AltRight");
            default -> Set.of(id);
        };
    }

    public static InputBinding fromMapping(KeyMapping mapping, boolean defaults) {
        String primary = fromKey(defaults ? mapping.getDefaultKey() : mapping.getKey());
        if (primary == null) return InputBinding.empty();
        KeyModifier modifier = defaults ? mapping.getDefaultKeyModifier() : mapping.getKeyModifier();
        var keys = new ArrayList<String>();
        switch (modifier) {
            case CONTROL -> keys.add("ControlAny");
            case SHIFT -> keys.add("ShiftAny");
            case ALT -> keys.add("AltAny");
            default -> { }
        }
        keys.add(primary);
        return new InputBinding(keys, InputBinding.Mode.CHORD);
    }

    public static String label(String id) {
        return switch (id) {
            case "ControlAny" -> "Ctrl"; case "ShiftAny" -> "Shift"; case "AltAny" -> "Alt";
            case "ControlLeft" -> "左 Ctrl"; case "ControlRight" -> "右 Ctrl";
            case "ShiftLeft" -> "左 Shift"; case "ShiftRight" -> "右 Shift";
            case "AltLeft" -> "左 Alt"; case "AltRight" -> "右 Alt";
            case "MetaLeft" -> "左 Win"; case "MetaRight" -> "右 Win";
            case "ContextMenu" -> "Menu"; case "Space" -> "Space";
            case "Backquote" -> "`"; case "Minus" -> "−"; case "Equal" -> "=";
            case "BracketLeft" -> "["; case "BracketRight" -> "]"; case "Backslash" -> "\\";
            case "Semicolon" -> ";"; case "Quote" -> "'"; case "Comma" -> ",";
            case "Period" -> "."; case "Slash" -> "/";
            case "ArrowUp" -> "↑"; case "ArrowDown" -> "↓"; case "ArrowLeft" -> "←"; case "ArrowRight" -> "→";
            case "Mouse1" -> "鼠标左键"; case "Mouse2" -> "鼠标右键"; case "Mouse3" -> "鼠标中键";
            case "Mouse4" -> "侧键 4"; case "Mouse5" -> "侧键 5";
            case "ScrollUp" -> "滚轮上"; case "ScrollDown" -> "滚轮下";
            case "NumpadDivide" -> "Num /"; case "NumpadMultiply" -> "Num *";
            case "NumpadSubtract" -> "Num −"; case "NumpadAdd" -> "Num +";
            case "NumpadEnter" -> "Num Enter"; case "NumpadDecimal" -> "Num .";
            default -> id.startsWith("Numpad") ? "Num " + id.substring(6) : id;
        };
    }
}
