package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** Selection and acknowledged baseline of the rule library, independent of the map's applied rules. */
final class MatchRuleDraft {
    private static final Pattern GENERATED_NAME = Pattern.compile("(?:自定义规则\\s*|Custom Rule\\s*)([0-9]+)", Pattern.CASE_INSENSITIVE);
    private boolean selected;
    private String savedName = "";
    private List<String> baseline;

    boolean selected() { return selected; }
    String savedName() { return savedName; }
    boolean dirty(List<String> values) { return selected && (baseline == null || !baseline.equals(values)); }
    void create() { selected = true; savedName = ""; baseline = null; }
    void acknowledge(String name, List<String> values) {
        selected = true; savedName = name; baseline = List.copyOf(values);
    }
    void clear() { selected = false; savedName = ""; baseline = null; }
    String initialChoice(long mapId, long responseMapId, String preferred, Collection<String> available) {
        if (selected || mapId <= 0 || mapId != responseMapId || preferred == null || preferred.isEmpty()) return "";
        return available.contains(preferred) ? preferred : "";
    }
    static String nextName(Collection<String> names) {
        BigInteger largest = BigInteger.ZERO;
        for (String name : names) {
            var match = GENERATED_NAME.matcher(Texts.resolve(name).strip());
            if (match.matches()) largest = largest.max(new BigInteger(match.group(1)));
        }
        return Texts.of("screen.tacz_pubg.match_config.custom_rule_name", largest.add(BigInteger.ONE).toString()).getString();
    }
}
