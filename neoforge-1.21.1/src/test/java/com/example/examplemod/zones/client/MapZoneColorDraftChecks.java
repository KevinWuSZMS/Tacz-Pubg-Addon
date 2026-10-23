package com.example.examplemod.zones.client;

import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Exercise the real authoring draft without constructing a Screen, Minecraft client or world. */
public final class MapZoneColorDraftChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        var style = new MapVisualStyle(0x010203, 0x1549D8, 0xEF3038, 0xEAF5FF, 0x93A9B5, 0xFFDC65,
                .38, .43, .34, .26);
        var map = new MapDefinition(3, "颜色草稿", ResourceLocation.parse("minecraft:overworld"),
                -50, -40, 50, 40, new Circle(0, 0, 15), 1, List.of(), new FlightPath(-40, 0, 40, 0), style);
        Class<?> rawClass = Class.forName("com.example.examplemod.zones.client.MapZoneConfigScreen$RawMap");
        Constructor<?> constructor = rawClass.getDeclaredConstructor(MapDefinition.class);
        constructor.setAccessible(true);
        Object draft = constructor.newInstance(map);
        Method model = rawClass.getDeclaredMethod("model"); model.setAccessible(true);
        Method values = rawClass.getDeclaredMethod("values"); values.setAccessible(true);
        Method defaults = rawClass.getDeclaredMethod("setVisualStyle", MapVisualStyle.class); defaults.setAccessible(true);
        check(model.invoke(draft).equals(map), "opening a map preserves its complete model");
        var originalValues = values.invoke(draft);
        check(field(rawClass, "safeRgb").get(draft).equals("010203"), "leading zeros remain editable");
        check(field(rawClass, "blueMapPercent").get(draft).equals("38"), "fraction shown as ordinary percent");

        for (String name : List.of("safeRgb", "blueRgb", "bombRgb", "lightningRgb", "borderRgb", "flightRgb")) {
            Field field = field(rawClass, name);
            String previous = (String) field.get(draft);
            for (String invalid : List.of("", "#", "#1549D", "1549D88", "GGGGGG", " 1549D8")) {
                field.set(draft, invalid);
                rejects(model, draft);
                check(field.get(draft).equals(invalid), "invalid RGB is retained in the actual draft");
                check(!values.invoke(draft).equals(originalValues), "invalid RGB remains a dirty unsaved change");
            }
            field.set(draft, "#aBc123");
            model.invoke(draft);
            check(field.get(draft).equals("#aBc123"), "validation does not rewrite entered case or prefix");
            field.set(draft, previous);
        }
        for (String name : List.of("blueMapPercent", "bombMapPercent", "blueWorldPercent", "blueScreenPercent")) {
            Field field = field(rawClass, name);
            String previous = (String) field.get(draft);
            for (String invalid : List.of("", "-1", "101", "NaN", "Infinity", "38%")) {
                field.set(draft, invalid);
                rejects(model, draft);
                check(field.get(draft).equals(invalid), "invalid opacity is retained");
            }
            field.set(draft, "0"); model.invoke(draft);
            field.set(draft, "100"); model.invoke(draft);
            field.set(draft, previous);
        }
        check(model.invoke(draft).equals(map), "returning to original text returns original model");
        check(values.invoke(draft).equals(originalValues), "complete draft fingerprint restores exactly");
        defaults.invoke(draft, MapVisualStyle.DEFAULT);
        var reset = (MapDefinition) model.invoke(draft);
        check(reset.visualStyle().equals(MapVisualStyle.DEFAULT), "default button changes style in draft");
        check(reset.name().equals(map.name()) && reset.id() == map.id() && reset.safeZone().equals(map.safeZone())
                && reset.flightPath().equals(map.flightPath()) && reset.minX() == map.minX() && reset.maxZ() == map.maxZ(),
                "default colors do not change map geometry, identity or zones");
        check(map.visualStyle().equals(style), "original immutable server model is untouched");
        System.out.println("Map zone color draft checks passed: " + checks);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static void rejects(Method method, Object draft) throws Exception {
        try { method.invoke(draft); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IllegalArgumentException) { checks++; return; }
            throw exception;
        }
        throw new AssertionError("invalid draft was accepted");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
