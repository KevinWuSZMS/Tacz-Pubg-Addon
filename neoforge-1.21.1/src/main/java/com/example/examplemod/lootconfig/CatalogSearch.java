package com.example.examplemod.lootconfig;

import java.util.Locale;

/** Small, predictable subset of JEI's text-search conventions; no JEI internals. */
public final class CatalogSearch {
    private CatalogSearch() {}

    public static boolean matches(String query, String kind, String id, String translatedName) {
        String identifier = id.toLowerCase(Locale.ROOT);
        String name = translatedName.toLowerCase(Locale.ROOT);
        String type = kind.toLowerCase(Locale.ROOT);
        for (String raw : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (raw.isEmpty()) continue;
            boolean exclude = raw.startsWith("-") && raw.length() > 1;
            String token = exclude ? raw.substring(1) : raw;
            boolean match;
            if (token.startsWith("@")) {
                match = identifier.substring(0, identifier.indexOf(':')).contains(token.substring(1));
            } else if (token.startsWith("#")) {
                match = type.contains(token.substring(1));
            } else {
                match = identifier.contains(token) || name.contains(token);
            }
            if (exclude ? match : !match) return false;
        }
        return true;
    }
}
