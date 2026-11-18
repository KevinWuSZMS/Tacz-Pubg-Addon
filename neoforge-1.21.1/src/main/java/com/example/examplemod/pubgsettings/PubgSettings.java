package com.example.examplemod.pubgsettings;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-owned, typed tuning values. Missing entries retain explicit versioned defaults. */
public record PubgSettings(Map<String, Double> values) {
    public static final int MAX_JSON = 32767;
    public enum Kind { NUMBER, INTEGER, BOOLEAN, COLOR }
    public record Field(String key, double initial, double minimum, double maximum, Kind kind) {
        public String group() { return key.substring(0, key.indexOf('.')); }
        public void validate(double value) {
            if (!Double.isFinite(value) || value < minimum || value > maximum
                    || kind != Kind.NUMBER && value != Math.rint(value))
                throw new IllegalArgumentException(key + ": " + minimum + ".." + maximum);
        }
    }
    private static final Map<String, Field> FIELDS = fieldsByKey();
    public PubgSettings {
        if (values == null || values.size() > FIELDS.size()) throw new IllegalArgumentException("Invalid settings count");
        var copy = new LinkedHashMap<String, Double>();
        values.forEach((key, value) -> {
            Field field = field(key);
            if (value == null) throw new IllegalArgumentException("Missing setting value: " + key);
            field.validate(value);
            if (Double.compare(value, field.initial) != 0) copy.put(key, value);
        });
        values = Collections.unmodifiableMap(copy);
        double red = values.getOrDefault("hud.health_red_threshold", FIELDS.get("hud.health_red_threshold").initial);
        double amber = values.getOrDefault("hud.health_apricot_threshold", FIELDS.get("hud.health_apricot_threshold").initial);
        if (red >= amber) throw new IllegalArgumentException("Health thresholds must increase");
    }
    public static PubgSettings initial() { return new PubgSettings(Map.of()); }
    public static Field field(String key) {
        Field field = FIELDS.get(key);
        if (field == null) throw new IllegalArgumentException("Unknown battle setting: " + key);
        return field;
    }
    public static List<Field> fields() { return List.copyOf(FIELDS.values()); }
    public double number(String key) { return values.getOrDefault(key, field(key).initial); }
    public int integer(String key) { return (int) Math.round(number(key)); }
    public boolean enabled(String key) { return number(key) == 1; }
    /** ARGB, including the authored alpha; callers must not blindly replace that alpha. */
    public int color(String key) { return (int) (long) number(key); }
    public int teamColor(int team) {
        return team <= 0 || team > 100 ? 0xFFB7BEC4 : color(String.format(java.util.Locale.ROOT, "team.color_%03d", team));
    }
    public PubgSettings with(String key, double value) {
        var copy = new LinkedHashMap<>(values); copy.put(key, value); return new PubgSettings(copy);
    }
    public String toJson() {
        var root = new JsonObject(); values.forEach(root::addProperty); return root.toString();
    }
    /** Strict flat-object codec, with migration of the two retired medicine cadence fields. */
    public static PubgSettings parse(String text) {
        if (text == null || text.length() > MAX_JSON) throw new IllegalArgumentException("Battle settings exceed size limit");
        var values = new LinkedHashMap<String, Double>();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false); reader.beginObject();
            var seen = new HashSet<String>();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key) || reader.peek() != JsonToken.NUMBER)
                    throw new IllegalArgumentException("Duplicate or nonnumeric battle setting: " + key);
                double value = reader.nextDouble();
                if (!retiredMedicineField(key, value)) {
                    // Five distinct recovery pulses need at least five game ticks.
                    if (key.equals("medicine.bandage_recovery_ticks") && value == 4) value = 5;
                    field(key); values.put(key, value);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing battle settings data");
            return new PubgSettings(values);
        } catch (java.io.IOException | IllegalStateException error) {
            throw new IllegalArgumentException("Invalid battle settings JSON", error);
        }
    }
    private static boolean retiredMedicineField(String key, double value) {
        // Old configurable cadence has been replaced by five recovery steps over each item's duration.
        // Validate the historical range before dropping these keys; unrelated unknown fields still fail.
        Field legacy = switch (key) {
            case "medicine.recovery_step_ticks" -> new Field(key, 4, 1, 20, Kind.INTEGER);
            case "medicine.first_aid_step_percent" -> new Field(key, 15, .1, 75, Kind.NUMBER);
            default -> null;
        };
        if (legacy == null) return false;
        legacy.validate(value);
        return true;
    }
    private static Map<String, Field> fieldsByKey() {
        var f = new LinkedHashMap<String, Field>();
        b(f,"hud.align_hotbar",true); n(f,"hud.bar_width",182,180,420,true); n(f,"hud.equipment_icon_size",18,12,28,true);
        n(f,"hud.row_height",24,18,40,true); n(f,"hud.equipment_gap",2,0,10,true);
        n(f,"hud.health_height",10,6,18,true); n(f,"hud.boost_height",2,1,6,true);
        n(f,"hud.bar_gap",2,0,8,true); n(f,"hud.bottom_margin",28,8,120,true);
        n(f,"hud.health_red_threshold",.25,.05,.5,false); n(f,"hud.health_apricot_threshold",.4,.1,.75,false);
        c(f,"hud.health_red",0xFFE65F57L); c(f,"hud.health_apricot",0xFFFFDC9FL);
        c(f,"hud.health_white",0xF2E8E9E7L); c(f,"hud.health_full",0xA5B0B2B0L);
        c(f,"hud.health_reference",0x65A6A8AAL); c(f,"hud.health_treatment",0x6F9DA1A3L);
        c(f,"hud.health_next_step",0xACBCBEC0L); c(f,"hud.boost_color",0xFFF09A27L); c(f,"hud.panel_color",0x66212427L);
        n(f,"hud.icon_inactive_alpha",.4,.1,1,false); n(f,"hud.weapon_stack_width",96,64,180,true);
        n(f,"hud.weapon_stack_row_height",23,16,40,true); n(f,"hud.weapon_stack_gap",3,0,10,true);
        n(f,"hud.fuse_radius",10,5,24,true); n(f,"hud.fuse_offset_x",20,8,60,true);
        n(f,"hud.fuse_line_width",2,1,5,false); n(f,"hud.fuse_warning_fraction",.25,.05,.75,false);
        b(f,"hud.hide_hotbar",false); c(f,"hud.fuse_color",0xFFF2F0E8L);
        c(f,"hud.fuse_warning_color",0xFFFF6558L); c(f,"hud.fuse_track_color",0x554C5053L);
        n(f,"medicine.bandage_recovery_ticks",58,5,200,true);
        n(f,"medicine.first_aid_recovery_ticks",40,5,200,true);
        n(f,"medicine.circle_radius",20,12,50,true); n(f,"medicine.circle_line_width",3,1,8,false);
        n(f,"medicine.circle_offset_y",0,-120,120,true);
        c(f,"medicine.circle_background",0x90433F38L); c(f,"medicine.circle_track",0x88443F39L); c(f,"medicine.circle_progress",0xFFF2F0E8L);
        n(f,"medicine.bandage_use_ticks",60,20,1200,true); n(f,"medicine.first_aid_use_ticks",120,20,1200,true);
        n(f,"medicine.med_kit_use_ticks",120,20,1200,true); n(f,"medicine.energy_drink_use_ticks",80,20,1200,true);
        n(f,"medicine.painkiller_use_ticks",120,20,1200,true); n(f,"medicine.adrenaline_use_ticks",120,20,1200,true);
        b(f,"dbno.enabled",true); n(f,"dbno.health",100,1,1000,false); n(f,"dbno.protection_ticks",30,0,200,true);
        n(f,"dbno.bleed_first_seconds",90,1,600,false); n(f,"dbno.bleed_second_seconds",30,1,600,false);
        n(f,"dbno.bleed_third_seconds",30,1,600,false); n(f,"dbno.bleed_fourth_seconds",10,1,600,false);
        n(f,"dbno.bleed_fifth_seconds",7,1,600,false); n(f,"dbno.bleed_sixth_seconds",5,1,600,false);
        n(f,"dbno.bleed_seventh_seconds",4,1,600,false); n(f,"dbno.bleed_eighth_seconds",3,1,600,false);
        n(f,"dbno.bleed_ninth_seconds",2,1,600,false); n(f,"dbno.stationary_seconds",90,1,600,false);
        n(f,"dbno.crawl_multiplier",.18,.01,1,false); n(f,"dbno.swim_multiplier",.5,.01,1,false);
        n(f,"dbno.damage_multiplier",.45,.01,2,false); n(f,"dbno.frag_multiplier",.8,.01,2,false);
        n(f,"dbno.sniper_multiplier",1.35,.01,3,false); n(f,"dbno.crossbow_multiplier",1.125,.01,3,false);
        n(f,"dbno.underwater_seconds",10,1,60,false);
        n(f,"rescue.distance",3,1,8,false); n(f,"rescue.ticks",200,20,1200,true);
        n(f,"rescue.health_percent",10,1,100,false); n(f,"rescue.move_tolerance",.35,.05,2,false);
        n(f,"rescue.aim_dot",.75,.1,1,false);
        b(f,"rescue.pause_bleeding",true);
        n(f,"combat.assist_window_ticks",1200,20,12000,true); n(f,"combat.assist_min_damage",1,0,100,false);
        n(f,"combat.message_rows",3,1,6,true); n(f,"combat.message_seconds",6,.5,30,false);
        n(f,"combat.fade_in_seconds",.18,0,2,false); n(f,"combat.fade_out_seconds",.8,0,5,false);
        n(f,"combat.move_seconds",.18,0,2,false); n(f,"combat.counter_seconds",10,.5,60,false);
        n(f,"combat.feed_rows",6,1,12,true); n(f,"combat.feed_seconds",6,.5,30,false);
        n(f,"combat.center_y",.72,.3,.9,false); n(f,"combat.message_scale",.85,.5,1.5,false);
        n(f,"combat.counter_scale",1.4,.7,2.5,false); n(f,"combat.feed_scale",.75,.5,1.5,false);
        n(f,"combat.feed_width",235,120,420,true); n(f,"combat.feed_top",33,20,150,true);
        n(f,"combat.feed_row_height",13,10,24,true); n(f,"combat.right_margin",8,0,48,true);
        n(f,"combat.top_number_scale",1.5,1,2.5,false); n(f,"combat.top_height",22,16,36,true);
        n(f,"combat.center_row_height",14,10,30,true);
        c(f,"combat.kill_color",0xFFF35B42L); c(f,"combat.assist_color",0xFFFFB526L);
        c(f,"combat.own_color",0xFF57BDD8L); c(f,"combat.text_color",0xFFF1F1EEL);
        c(f,"combat.older_color",0xFFADB3B8L);
        n(f,"lean.body_penetration_tolerance",.06,0,.15,false);
        n(f,"map.entity_recording_prompt_side_length",500,1,60000000,true);
        n(f,"map.headerGap",0,0,24,true); n(f,"map.headerHeight",18,12,40,true);
        n(f,"map.gaugeHeight",3,1,8,true); n(f,"map.headerBackgroundOpacity",.18,0,1,false);
        c(f,"map.blueColor",0xFF2378E5L); c(f,"map.safeColor",0xFFF5F5F5L); c(f,"map.playerColor",0xFFFFFFFFL);
        c(f,"map.dangerColor",0xFFFF7777L); c(f,"map.warningColor",0xFFEC4444L); c(f,"map.arrowColor",0xFFB9DAFFL);
        n(f,"map.arrowSpacing",8,4,32,true); n(f,"map.arrowSpeed",16,0,80,false); n(f,"map.arrowOpacity",.45,0,1,false);
        b(f,"map.navigationEnabled",true); n(f,"map.navigationWidth",1,.5,4,false);
        c(f,"map.navigationColor",0xFFFFFFFFL); n(f,"map.navigationOpacity",.75,0,1,false);
        b(f,"map.showSafeDistance",true); n(f,"map.defaultMargin",8,0,48,true);
        for (int team = 1; team <= 100; team++) c(f,String.format(java.util.Locale.ROOT,"team.color_%03d",team),
                Integer.toUnsignedLong(com.example.examplemod.combat.TeamPalette.color(team)));
        return Collections.unmodifiableMap(f);
    }
    private static void n(Map<String, Field> f,String key,double value,double low,double high,boolean integer) {
        if (f.put(key,new Field(key,value,low,high,integer?Kind.INTEGER:Kind.NUMBER)) != null)
            throw new IllegalStateException("Duplicate setting " + key);
    }
    private static void c(Map<String, Field> f,String key,long value) { f.put(key,new Field(key,value,0,4294967295.0,Kind.COLOR)); }
    private static void b(Map<String, Field> f,String key,boolean value) { f.put(key,new Field(key,value?1:0,0,1,Kind.BOOLEAN)); }
}
