package com.example.examplemod.throwables.client;

import net.minecraft.network.chat.Component;

/** Native UI labels accept future LR fields without allocating a strict transport translation ID. */
public final class ThrowableSettingsFieldNames {
    public static final String PREFIX = "screen.tacz_pubg.throwable_settings.field.";
    private ThrowableSettingsFieldNames() {}

    public static Component label(String path) {
        String field = path.substring(path.lastIndexOf('.') + 1);
        // An extension is not required to register its property names in our localization index.
        // Keep its complete path as the fallback, and still allow a resource pack to name it.
        return Component.translatableWithFallback(PREFIX + field, path);
    }
}
