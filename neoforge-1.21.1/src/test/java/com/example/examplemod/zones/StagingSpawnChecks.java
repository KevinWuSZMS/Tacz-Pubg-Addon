package com.example.examplemod.zones;

import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.client.xaero.editor.NativeMapEditModel;
import com.example.examplemod.zones.client.xaero.editor.NativeMapEditModel.Tool;
import com.example.examplemod.zones.client.xaero.editor.NativeMapProjection.Point;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Only staging serialization and local point/radius editing; no world, server or renderer is started. */
public final class StagingSpawnChecks {
    private static int checks;
    public static void main(String[] args) {
        var saved = map(new Circle(40, 50, 12));
        check(MapZoneModel.parse(MapZoneModel.toJson(saved)).equals(saved), "staging center and radius survive JSON");
        var legacy = JsonParser.parseString(MapZoneModel.toJson(saved)).getAsJsonObject();
        legacy.remove("stagingSpawn");
        check(MapZoneModel.parse(legacy.toString()).stagingSpawn() == null, "missing legacy field remains unset");
        legacy.add("stagingSpawn", JsonNull.INSTANCE);
        check(MapZoneModel.parse(legacy.toString()).stagingSpawn() == null, "explicit null remains unset");

        var editor = new NativeMapEditModel(map(null));
        editor.zoneTools(false);
        editor.tool(Tool.SPAWN);
        check(editor.begin(new Point(40, 50), 1), "ordinary map editing accepts staging placement");
        check(!editor.dragging() && editor.draft().stagingSpawn().centerX() == 40, "single click creates center without a drag");
        var first = editor.draft().stagingSpawn();
        editor.update(new Point(70, 80));
        check(editor.draft().stagingSpawn().equals(first), "mouse movement does not change radius or center");
        editor.stagingRadius(12);
        editor.begin(new Point(60, 50), 1);
        check(editor.draft().stagingSpawn().equals(new Circle(60, 50, 12)), "next click replaces the only center and keeps numeric radius");
        var beforeInvalid = editor.draft();
        rejects(() -> editor.stagingRadius(41));
        check(editor.draft().equals(beforeInvalid), "out-of-map radius is rejected without changing the draft");
        rejects(() -> editor.stagingRadius(0));
        rejects(() -> MapZoneModel.toJson(map(new Circle(40, 50, 41))));
        System.out.println("Staging spawn checks passed: " + checks);
    }
    private static MapDefinition map(Circle spawn) {
        return new MapDefinition(7, "Staging check", ResourceLocation.parse("minecraft:overworld"),
                0, 0, 100, 100, null, 1, List.of(), null, MapVisualStyle.DEFAULT, spawn);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid staging radius to be rejected");
    }
}
