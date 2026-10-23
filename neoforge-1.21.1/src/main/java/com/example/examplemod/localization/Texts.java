package com.example.examplemod.localization;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Native translatable components, with a locale-neutral bridge for legacy String error/status APIs.
 * tr() NEVER translates: tokens retain keys and arguments across saves and custom packets.
 * component() reconstructs native components; resolve() is exclusively a client presentation boundary.
 * New component-based APIs should use of() directly. Language JSON is the source of display text.
 */
public final class Texts {
    private static final char START = '\ue000', END = '\ue001';
    public static final int MAX_MESSAGE_LENGTH = 32767;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<String, String> INDEX = read("localization-index.json");
    private static final Map<String, String> IDS = reverse(INDEX);
    private static final Map<String, String> ENGLISH = read("lang/en_us.json");
    private static final Map<String, String> CHINESE = read("lang/zh_cn.json");
    private Texts() {}

    private static Map<String, String> read(String file) {
        try (var input = Objects.requireNonNull(Texts.class.getResourceAsStream("/assets/tacz_pubg/" + file));
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Map<String, String> result = new HashMap<>();
            JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsString()));
            return Map.copyOf(result);
        } catch (Exception e) { throw new IllegalStateException("Cannot load localization catalog " + file, e); }
    }
    private static Map<String, String> reverse(Map<String, String> input) {
        Map<String, String> result = new HashMap<>();
        input.forEach((id, key) -> { if (result.put(key, id) != null) throw new IllegalStateException("Duplicate translation key: " + key); });
        return Map.copyOf(result);
    }

    /** Use for new UI/chat code. Preserve native arguments; stringify IDs and other display values. */
    public static MutableComponent of(String key, Object... args) {
        Object[] nativeArgs = args.clone();
        for (int i = 0; i < nativeArgs.length; i++) {
            Object arg = nativeArgs[i];
            if (arg instanceof String text && text.indexOf(START) >= 0) nativeArgs[i] = component(text);
            else if (!(arg instanceof Component) && !TranslatableContents.isAllowedPrimitiveArgument(arg))
                nativeArgs[i] = String.valueOf(arg);
        }
        return Component.translatableWithFallback(key, ENGLISH.getOrDefault(key, key), nativeArgs);
    }

    /** Compatibility representation for String-valued validators, exception messages and saved statuses. */
    public static String tr(String key, Object... args) {
        String id = IDS.get(key);
        if (id == null) throw new IllegalArgumentException("Unregistered translation key: " + key);
        if (args.length == 0) return "" + START + id + END;
        JsonArray values = new JsonArray();
        for (Object arg : args) {
            if (arg instanceof Component c) {
                JsonObject value = new JsonObject();
                value.add("component", ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow());
                values.add(value);
            } else values.add(String.valueOf(arg));
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.toJson(values).getBytes(StandardCharsets.UTF_8));
        return "" + START + id + ':' + encoded + END;
    }

    /** Reconstruct nested native translations without selecting any language. Ordinary strings stay literal. */
    public static MutableComponent component(String text) { return component(text == null ? "" : text, 0); }
    private static MutableComponent component(String text, int depth) {
        if (depth >= 16 || text.length() > 1_048_576 || text.indexOf(START) < 0) return Component.literal(text);
        MutableComponent result = Component.empty();
        int cursor = 0, count = 0;
        while (cursor < text.length()) {
            int start = text.indexOf(START, cursor);
            if (start < 0 || ++count > 256) { result.append(Component.literal(text.substring(cursor))); break; }
            result.append(Component.literal(text.substring(cursor, start)));
            int end = text.indexOf(END, start + 1);
            if (end < 0) { result.append(Component.literal(text.substring(start))); break; }
            String token = text.substring(start + 1, end);
            int colon = token.indexOf(':');
            String id = colon < 0 ? token : token.substring(0, colon), key = INDEX.get(id);
            MutableComponent decoded = null;
            if (key != null) try {
                List<Object> args = new ArrayList<>();
                if (colon >= 0) {
                    byte[] bytes = Base64.getUrlDecoder().decode(token.substring(colon + 1));
                    JsonArray values = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray();
                    if (values.size() > 64) throw new IllegalArgumentException("Too many translation arguments");
                    for (JsonElement value : values) {
                        if (value.isJsonObject()) args.add(ComponentSerialization.CODEC.parse(JsonOps.INSTANCE,
                                value.getAsJsonObject().get("component")).getOrThrow());
                        else args.add(component(value.getAsString(), depth + 1));
                    }
                }
                decoded = of(key, args.toArray());
            } catch (RuntimeException ignored) { /* Corrupt/unknown legacy text remains literal, never crashes a screen. */ }
            result.append(decoded == null ? Component.literal(text.substring(start, end + 1)) : decoded);
            cursor = end + 1;
        }
        return result;
    }

    /** Only call when drawing, measuring, editing or searching text on the client. */
    public static String resolve(String text) { return component(text).getString(); }
    public static Component resolve(Component text) { return text; }
    public static FormattedText resolve(FormattedText text) { return text; }
    public static FormattedCharSequence resolve(FormattedCharSequence text) { return text; }

    /** Input aliases accept both bundled languages and the current pack translation, independent of UI labels. */
    public static boolean matches(String key, String input) {
        return input.equals(tr(key)) || input.equalsIgnoreCase(ENGLISH.getOrDefault(key, key))
                || input.equals(CHINESE.getOrDefault(key, key)) || input.equals(of(key).getString());
    }

    /** Preserve complete tokens when limiting an existing String protocol; never cut a token in half. */
    public static String limit(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Math.max(0, limit - 1), start = text.lastIndexOf(START, end);
        if (start >= 0 && text.indexOf(END, start) >= end) end = start;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
