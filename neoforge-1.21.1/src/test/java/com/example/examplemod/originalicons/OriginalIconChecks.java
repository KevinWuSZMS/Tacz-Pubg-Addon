package com.example.examplemod.originalicons;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.util.List;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class OriginalIconChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var defaults = new LootConfigModel.Document(LootConfigModel.General.defaults(), List.of());
        check(!defaults.gunSettings().usePubgOriginalIcons(), "original icons default off");
        check(!defaults.gunSettings().lowPolyModels(), "detailed geometry default");
        var changed = new LootConfigModel.Document(defaults.general(), defaults.items(),
                defaults.gunSettings().withOriginalIcons(true).withLowPolyModels(true));
        check(LootConfigModel.parse(LootConfigModel.toJson(changed)).equals(changed), "both presentation settings persist");
        var legacy = JsonParser.parseString(LootConfigModel.toJson(defaults)).getAsJsonObject();
        for (String key : List.of("usePubgOriginalIcons", "lowPolyModels", "inventory"))
            legacy.getAsJsonObject("gunSettings").remove(key);
        check(LootConfigModel.parse(legacy.toString()).equals(defaults), "old world defaults remain safe");
        check(changed.gunSettings().withValue(LootConfigModel.GunSettingCategory.SCOPES, "experimental").usePubgOriginalIcons(),
                "changing gun behavior retains independent icon choice");
        OriginalIconState.clear();
        check(!OriginalIconState.enabled() && !OriginalIconState.lowPolyModels(), "disconnect resets state");
        check(OriginalIconState.accept(10, true, true), "accept snapshot");
        check(!OriginalIconState.accept(9, false, false), "ignore queued stale snapshot");
        check(OriginalIconState.enabled() && OriginalIconState.lowPolyModels(), "stale packet cannot undo either setting");
        check(OriginalIconState.accept(11, false, false), "disable applies immediately");
        check(OriginalIconCatalog.all().size() >= 178, "all approved inventory mappings packaged");
        for (String id : List.of("bandage", "first_aid_kit", "med_kit", "energy_drink", "painkiller",
                "adrenaline_syringe", "backpack_1", "backpack_2", "backpack_3", "frag_grenade",
                "stun_grenade", "smoke_grenade", "molotov", "sticky_bomb", "c4", "blue_zone_grenade"))
            check(OriginalIconCatalog.find("item", "tacz_pubg:" + id) != null, "new item has authentic icon: " + id);
        check(OriginalIconCatalog.find("gun", "madeup:akm") == null, "no fuzzy cross-namespace replacement");
        check(OriginalIconCatalog.find("gun", "tacz:scar_h") == null, "SCAR-H does not become SCAR-L");
        try (var stream = OriginalIconChecks.class.getResourceAsStream("/assets/tacz_pubg/original_icons.json")) {
            var source = JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("icons");
            for (var entry : OriginalIconCatalog.all().entrySet()) {
                String resource = "/assets/" + entry.getValue().texture().replace(':', '/');
                try (var image = OriginalIconChecks.class.getResourceAsStream(resource)) {
                    check(image != null, "mapped texture exists inside JAR: " + entry.getKey());
                    byte[] bytes = image.readAllBytes();
                    var header = ByteBuffer.wrap(bytes);
                    check(header.getLong() == 0x89504E470D0A1A0AL, "valid PNG");
                    check(header.getInt(16) == entry.getValue().width() && header.getInt(20) == entry.getValue().height(),
                            "preserve source aspect ratio");
                    check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                            .equals(source.getAsJsonObject(entry.getKey()).get("sha256").getAsString()), "authentic pixels unchanged");
                }
            }
        }
        System.out.println("Original icon checks passed: " + checks);
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
