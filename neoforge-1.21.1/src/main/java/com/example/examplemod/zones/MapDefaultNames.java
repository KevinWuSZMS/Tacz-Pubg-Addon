package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;
import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

/** Default names follow the highest existing default suffix, including legacy unnamed maps. */
public final class MapDefaultNames {
    public record Existing(long id, String name) {}
    private MapDefaultNames() {}

    public static String next(List<Existing> maps) {
        String base = Texts.tr("screen.tacz_pubg.map_editor.default_name");
        Pattern defaults = Pattern.compile("^(?:" + Pattern.quote(base) + "|新地图|New Map)\\s*(\\d+)?$",
                Pattern.CASE_INSENSITIVE);
        BigInteger largest = BigInteger.ZERO;
        for (Existing map : maps) {
            var match = defaults.matcher(map.name().strip());
            if (!match.matches()) continue;
            BigInteger number = match.group(1) == null ? BigInteger.valueOf(map.id()) : new BigInteger(match.group(1));
            largest = largest.max(number);
        }
        return base + " " + largest.add(BigInteger.ONE);
    }
}
