package com.example.examplemod.throwables.client;

import com.example.examplemod.localization.TestLanguages;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.throwables.ThrowableKind;
import com.example.examplemod.throwables.ThrowableSettingsModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.xjqsh.lrtactical.item.throwable.EntityData;
import me.xjqsh.lrtactical.item.throwable.ThrowableData;
import me.xjqsh.lrtactical.item.throwable.area.EffectCloudThrowableData;
import me.xjqsh.lrtactical.item.throwable.explode.ExplodeThrowableData;
import me.xjqsh.lrtactical.item.throwable.flash.StunThrowableData;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Exercises the same labels as the editor without constructing a screen or starting a game. */
public final class ThrowableSettingsFieldNamesChecks {
    private static int checks;
    private static final Map<String, String> EN = TestLanguages.catalog("en_us"), ZH = TestLanguages.catalog("zh_cn");

    public static void main(String[] args) {
        Language previous = Language.getInstance();
        try {
            Set<Class<?>> inspected = new HashSet<>();
            for (Class<?> type : List.of(ThrowableData.class, EntityData.class, ExplodeThrowableData.class,
                    StunThrowableData.class, EffectCloudThrowableData.class)) inspectSchema(type, inspected);
            // EffectData uses a custom serializer rather than @SerializedName; include that schema too.
            for (var component : EffectCloudThrowableData.EffectData.class.getRecordComponents())
                known("data.cloud.effects.0." + component.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT));
            for (String wrapper : List.of("type", "base_item", "name", "tooltip", "data", "native_data")) known(wrapper);
            for (ThrowableKind kind : ThrowableKind.values())
                paths(ThrowableSettingsModel.defaults(kind), "", ThrowableSettingsFieldNamesChecks::known);

            // Regression for the exact reported crash, including all nested area-cloud fields.
            JsonElement cloud = JsonParser.parseString("""
                    {"data":{"cloud":{"area_cloud":true,"radius":5.5,"radius_per_tick":0.01,
                    "wait_time":20,"duration":200,"particles":"minecraft:effect","ignite":false,
                    "ignite_time":2,"extinguish_by_smoke":false,"effects":[{"type":"minecraft:slowness",
                    "duration":200,"amplifier":0,"visible":true,"show_icon":true}]},
                    "entity":{"tail_particles":[],"gravity":0.05}},"native_data":{}}
                    """);
            paths(cloud, "", ThrowableSettingsFieldNamesChecks::known);
            Component areaCloud = ThrowableSettingsFieldNames.label("data.cloud.area_cloud");
            Language.inject(TestLanguages.language(ZH));
            check(areaCloud.getString().equals(ZH.get(key("area_cloud"))), "Reported area_cloud path renders in Chinese without a transport ID");
            Language.inject(TestLanguages.language(EN));
            check(areaCloud.getString().equals(EN.get(key("area_cloud"))), "Existing labels follow a language switch lazily");

            for (String path : List.of("data.extension.future_field", "data.cloud.areaCloud", "native_data.plugin.filter:mode",
                    "data.plugin.options[0].extra", "data.plugin.扩展字段", "data.plugin.100%_custom", "data.plugin.%1$s")) {
                Component unknown = ThrowableSettingsFieldNames.label(path);
                for (var language : List.of(ZH, EN, Map.<String, String>of())) {
                    Language.inject(TestLanguages.language(language));
                    check(unknown.getString().equals(path), "Unknown extension preserves its complete path: " + path);
                }
            }
            var pack = new HashMap<>(EN); pack.put(key("future_field"), "Extension pack label");
            Language.inject(TestLanguages.language(pack));
            check(ThrowableSettingsFieldNames.label("data.plugin.future_field").getString().equals("Extension pack label"),
                    "Resource packs may name a future extension without registering a transport ID");
            Language.inject(TestLanguages.language(Map.of()));
            check(areaCloud.getString().equals("data.cloud.area_cloud"), "Missing entire language catalog still has a readable path fallback");
            boolean strict = false;
            try { Texts.tr(key("future_field")); } catch (IllegalArgumentException expected) { strict = true; }
            check(strict, "The repair does not relax strict translation validation for transport messages");
        } finally { Language.inject(previous); }
        System.out.println("ThrowableSettingsFieldNamesChecks: " + checks + " checks passed; no screen or game launched.");
    }

    private static void inspectSchema(Class<?> type, Set<Class<?>> inspected) {
        if (type == null || type == Object.class || !inspected.add(type)) return;
        for (var field : type.getDeclaredFields()) {
            SerializedName name = field.getAnnotation(SerializedName.class);
            if (name != null) known("data." + name.value());
        }
        for (var nested : type.getDeclaredClasses()) inspectSchema(nested, inspected);
        inspectSchema(type.getSuperclass(), inspected);
    }
    private static void paths(JsonElement value, String path, java.util.function.Consumer<String> visit) {
        if (value.isJsonObject() && !value.getAsJsonObject().isEmpty())
            for (var entry : value.getAsJsonObject().entrySet()) paths(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), visit);
        else if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            visit.accept(path);
            for (int index = 0; index < value.getAsJsonArray().size(); index++) paths(value.getAsJsonArray().get(index), path + "." + index, visit);
        } else visit.accept(path);
    }
    private static void known(String path) {
        String field = path.substring(path.lastIndexOf('.') + 1), translationKey = key(field);
        check(EN.containsKey(translationKey) && ZH.containsKey(translationKey), "Both bundled languages name LR/PUBG field: " + path);
        Component label = ThrowableSettingsFieldNames.label(path);
        check(label.getContents() instanceof TranslatableContents contents && contents.getKey().equals(translationKey),
                "Field uses a native translatable component: " + path);
        for (var catalog : List.of(EN, ZH)) {
            Language.inject(TestLanguages.language(catalog));
            String rendered = label.getString();
            check(rendered.equals(catalog.get(translationKey)), "Field resolves before drawing or clipping: " + path);
            check(rendered.indexOf('\ue000') < 0 && rendered.indexOf('\ue001') < 0, "No wire translation token reaches the renderer: " + path);
        }
    }
    private static String key(String field) { return ThrowableSettingsFieldNames.PREFIX + field; }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
