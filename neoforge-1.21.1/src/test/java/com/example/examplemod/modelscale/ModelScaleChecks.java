package com.example.examplemod.modelscale;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.AABB;

/** Style isolation, default semantics, strict input, durable revision and actual ground geometry. */
public final class ModelScaleChecks {
    private static int checks;
    public static void main(String[] args) {
        String item = "tacz_pubg:backpack_2", other = "tacz_pubg:med_kit";
        String playful = ModelScaleSettings.PLAYFUL, pixel = ModelScaleSettings.PIXEL;
        var initial = ModelScaleSettings.initial();
        check(initial.get(playful, item).equals(ModelScaleSettings.ONE), "Initial playful scale is one");
        check(initial.get(pixel, item).equals(ModelScaleSettings.ONE), "Initial pixel scale is one");
        var scales = initial.with(playful, item, .1, 5).with(pixel, item, 3, .5).with(pixel, other, 2, 1);
        check(scales.get(playful, item).dropped() == .1 && scales.get(playful, item).worn() == 5, "Contexts are independent and include both inclusive limits");
        check(scales.get(pixel, item).dropped() == 3 && scales.get(pixel, item).worn() == .5, "Styles do not overwrite one another");
        var savedDefault = scales.setDefault(playful);
        var edited = savedDefault.with(playful, item, 2, 2).with(pixel, item, 4, 4);
        var restored = edited.restoreDefault(playful);
        check(restored.get(playful, item).equals(scales.get(playful, item)), "Restore uses the custom style default");
        check(restored.get(pixel, item).dropped() == 4, "Restore leaves the other style's draft intact");
        check(restored.restoreDefault(pixel).get(pixel, item).equals(ModelScaleSettings.ONE), "Setting a playful default did not change the pixel default");
        check(restored.restoreInitial(playful).get(playful, item).equals(ModelScaleSettings.ONE), "Factory reset returns current style to one");
        check(restored.restoreInitial(playful).defaults().equals(restored.defaults()), "Factory reset keeps saved custom defaults");
        check(ModelScaleSettings.parse(restored.toJson()).equals(restored), "Both styles and custom defaults round trip without loss");
        for (double value : new double[]{0, .09, 5.01, Double.NaN, Double.POSITIVE_INFINITY})
            rejects(() -> initial.with(playful, item, value, 1), "Reject out-of-range or non-finite scale");
        rejects(() -> initial.with("unknown", item, 1, 1), "Reject unknown model style");
        rejects(() -> initial.with(playful, "minecraft:stone", 1, 1), "Reject unsupported item IDs");
        check(ModelScaleSettings.wearable("tacz_pubg:helmet_1") && ModelScaleSettings.wearable("tacz_pubg:parachute"), "Headgear and parachute packs expose worn scale");
        check(!ModelScaleSettings.wearable(other), "Medicine has no nonexistent worn renderer");
        helmetFitting(initial);

        var saved = new ModelScaleSavedData();
        check(saved.save(0, savedDefault), "First valid revision saves");
        check(!saved.save(0, edited) && saved.settings().equals(savedDefault), "Stale clients cannot overwrite another save");
        CompoundTag tag = saved.save(new CompoundTag(), RegistryAccess.EMPTY);
        var reloaded = ModelScaleSavedData.load(tag, RegistryAccess.EMPTY);
        check(reloaded.revision() == 1 && reloaded.settings().equals(savedDefault), "Settings, defaults and revision survive world reload");
        var malformed = new CompoundTag(); malformed.putInt("schema", 999); malformed.putString("keep", "source");
        var protectedData = ModelScaleSavedData.load(malformed, RegistryAccess.EMPTY);
        check(!protectedData.save(0, initial), "Unknown durable data is protected against writes");
        check(protectedData.save(new CompoundTag(), RegistryAccess.EMPTY).equals(malformed), "Unknown data retains its original evidence");

        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var packet = new ModelScalePayloads.State(3, restored, 1);
            ModelScalePayloads.State.CODEC.encode(buffer, packet);
            check(ModelScalePayloads.State.CODEC.decode(buffer).equals(packet) && buffer.readableBytes() == 0,
                    "The complete two-style default state survives network encoding");
        } finally { buffer.release(); }
        ModelScaleState.clear(); ModelScaleState.accept(4, restored); ModelScaleState.accept(3, initial);
        check(ModelScaleState.settings().equals(restored), "Old snapshots cannot revert applied model scales");
        ModelScaleState.clear(); check(ModelScaleState.revision() == -1, "Disconnect resets snapshot revision between worlds");

        AABB base = new AABB(-.4, .002, -.6, .4, .202, .6);
        AABB enlarged = ModelScaleService.groundBounds(base, 5, .002);
        near(enlarged.minY, .002, "Scaling retains the fixed ground contact height");
        near(enlarged.getYsize(), 1, "Scaling changes drawable height without sinking into the floor");
        near(enlarged.getXsize(), 4, "Pickup and culling width follow the actual scale");
        for (String id : ModelScaleSettings.ITEMS) for (boolean lowPoly : new boolean[]{false, true}) {
            AABB bounds = ModelScaleGeometry.load(id, lowPoly);
            check(bounds.minY == 0 && bounds.getXsize() > 0 && bounds.getYsize() > 0 && bounds.getZsize() > 0,
                    "Both styles have valid floor-anchored source geometry: " + id);
            near(bounds.minX + bounds.maxX, 0, "Source X is recentered before scaling");
            near(bounds.minZ + bounds.maxZ, 0, "Source Z is recentered before scaling");
        }
        System.out.println("ModelScaleChecks: " + checks + " checks passed; no game launched.");
    }
    private static void helmetFitting(ModelScaleSettings initial) {
        var legacy = initial.with(ModelScaleSettings.PLAYFUL, "tacz_pubg:helmet_2", 1.7, 2.2)
                .with(ModelScaleSettings.PIXEL, "tacz_pubg:helmet_1", 1.5, 4)
                .with(ModelScaleSettings.PIXEL, "tacz_pubg:helmet_2", .8, 2)
                .with(ModelScaleSettings.PIXEL, "tacz_pubg:backpack_3", 1.4, 2.7)
                .setDefault(ModelScaleSettings.PIXEL);
        for (int tier = 1; tier <= 3; tier++) {
            String id = "tacz_pubg:helmet_" + tier;
            check(initial.get(ModelScaleSettings.PIXEL, id).worn() == 3.1, "Pixel helmet factory wear multiplier is 3.1");
            check(initial.get(ModelScaleSettings.PLAYFUL, id).equals(ModelScaleSettings.ONE), "Playful helmet factory values are unchanged");
            check(initial.get(ModelScaleSettings.PIXEL, id).dropped() == 1, "Helmet item/drop scale is independent");
        }
        var oldTag = new CompoundTag(); oldTag.putInt("schema", 1); oldTag.putLong("revision", 7); oldTag.putString("settings", legacy.toJson());
        var migrated = ModelScaleSavedData.load(oldTag, RegistryAccess.EMPTY);
        check(migrated.revision() == 8 && migrated.isDirty(), "Existing worlds receive the pixel fitting once and mark it for persistence");
        var result = migrated.settings();
        for (int tier = 1; tier <= 3; tier++) {
            String id = "tacz_pubg:helmet_" + tier;
            check(result.get(ModelScaleSettings.PIXEL, id).worn() == 3.1, "Old pixel helmet wear override is updated");
            near(result.get(ModelScaleSettings.PIXEL, id).dropped(), legacy.get(ModelScaleSettings.PIXEL, id).dropped(), "Migration preserves dropped helmet scaling");
            check(result.get(ModelScaleSettings.PLAYFUL, id).equals(legacy.get(ModelScaleSettings.PLAYFUL, id)), "Migration cannot change any playful helmet setting");
        }
        check(result.get(ModelScaleSettings.PIXEL, "tacz_pubg:backpack_3").equals(legacy.get(ModelScaleSettings.PIXEL, "tacz_pubg:backpack_3")), "Migration leaves unrelated wearable values intact");
        check(result.defaults().equals(legacy.defaults()), "Previously saved personal defaults remain available");
        var persisted = migrated.save(new CompoundTag(), RegistryAccess.EMPTY);
        check(persisted.getInt("schema") == 2 && persisted.getCompound("previous_schema1").equals(oldTag), "Migration retains the exact old data values as evidence");
        var custom = result.with(ModelScaleSettings.PIXEL, "tacz_pubg:helmet_2", .8, 2.5);
        check(migrated.save(8, custom), "The new preset remains adjustable after migration");
        var reopened = ModelScaleSavedData.load(migrated.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
        check(reopened.revision() == 9 && reopened.settings().equals(custom), "Reopening a migrated world does not overwrite later custom values");
        var explicitOne = initial.with(ModelScaleSettings.PIXEL, "tacz_pubg:helmet_2", 1, 1);
        check(ModelScaleSettings.parse(explicitOne.toJson()).get(ModelScaleSettings.PIXEL, "tacz_pubg:helmet_2").worn() == 1,
                "An explicit multiplier of one is not sparsified away against the new factory value");
        check(custom.restoreInitial(ModelScaleSettings.PIXEL).get(ModelScaleSettings.PIXEL, "tacz_pubg:helmet_2").worn() == 3.1,
                "Factory reset uses the new pixel helmet baseline");
        for (double multiplier : new double[] {.1, 1, 3.1, 5}) {
            var placement = com.example.examplemod.armor.client.HelmetWearPlacement.class;
            near(com.example.examplemod.armor.client.HelmetWearPlacement.y(1, true, multiplier), -.25, "First-tier mounting height remains unchanged");
            near(com.example.examplemod.armor.client.HelmetWearPlacement.y(3, true, multiplier), -.25, "Third-tier mounting height remains unchanged");
            near(com.example.examplemod.armor.client.HelmetWearPlacement.y(2, false, multiplier), -.25, "Detailed/playful mounting height remains unchanged");
            double y = com.example.examplemod.armor.client.HelmetWearPlacement.y(2, true, multiplier);
            check(y < -.25, "Pixel second-tier head-local negative Y moves the worn helmet upwards");
        }
        double lift = -.25 - com.example.examplemod.armor.client.HelmetWearPlacement.y(2, true, 3.1);
        check(Math.abs(lift - .12) < .001, "The 3.1 preset receives the screenshot-calibrated 0.12-block lift");
        try {
            double[] tops = new double[3];
            for (int tier = 1; tier <= 3; tier++) {
                try (var input = ModelScaleChecks.class.getResourceAsStream("/assets/tacz_pubg/models/item/helmet_" + tier + ".json")) {
                    check(input != null, "Helmet source model exists");
                    var object = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    double maxY = -Double.MAX_VALUE;
                    for (var quad : object.getAsJsonArray("seamless_quads")) {
                        var vertices = quad.getAsJsonObject().getAsJsonArray("vertices");
                        for (int at = 1; at < vertices.size(); at += 5) maxY = Math.max(maxY, vertices.get(at).getAsDouble());
                    }
                    double fixed = object.getAsJsonObject("display").getAsJsonObject("fixed").getAsJsonArray("scale").get(1).getAsDouble();
                    tops[tier - 1] = -com.example.examplemod.armor.client.HelmetWearPlacement.y(tier, true, 3.1)
                            + .9 * 3.1 * (maxY - 8) * fixed / 16;
                }
            }
            check(Math.abs(tops[1] - (tops[0] + tops[2]) * .5) < .001, "Actual second-tier source crown aligns to the first/third crown mean");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-6, message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
