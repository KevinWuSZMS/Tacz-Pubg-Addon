package com.example.examplemod.localization;

import java.util.Locale;

/** Language selection policy for this addon's resources only. */
public final class ChineseLanguageFallback {
    private ChineseLanguageFallback() {}
    public static boolean needsFallback(String namespace, String path) {
        if (!namespace.equals("tacz_pubg") || !path.startsWith("lang/") || !path.endsWith(".json")) return false;
        String code = path.substring(5, path.length() - 5).toLowerCase(Locale.ROOT).replace('-', '_');
        return !code.equals("zh_cn") && (code.equals("zh") || code.startsWith("zh_")
                || code.equals("lzh") || code.equals("yue") || code.startsWith("yue_"));
    }
}
