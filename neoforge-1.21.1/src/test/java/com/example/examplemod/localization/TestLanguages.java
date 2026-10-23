package com.example.examplemod.localization;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;

/** Offline language fixture; no Minecraft client/server/world is started. */
public final class TestLanguages {
    public static Map<String, String> catalog(String locale) {
        Map<String, String> result = new HashMap<>();
        try(var input = TestLanguages.class.getResourceAsStream("/assets/tacz_pubg/lang/" + locale + ".json")) {
            Language.loadFromJson(Objects.requireNonNull(input), result::put);
        } catch(Exception e) { throw new AssertionError(e); }
        return result;
    }
    public static Language language(Map<String, String> catalog) {
        return new Language() {
            public String getOrDefault(String key, String fallback) { return catalog.getOrDefault(key, fallback); }
            public boolean has(String key) { return catalog.containsKey(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        };
    }
    private static final Language CHINESE = language(catalog("zh_cn"));
    public static String chinese(String text) {
        Language previous = Language.getInstance();
        try { Language.inject(CHINESE); return Texts.resolve(text); }
        finally { Language.inject(previous); }
    }
}
