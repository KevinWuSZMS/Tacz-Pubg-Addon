package com.example.examplemod.zones.match.client;

import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Exercises the editor's actual raw forms and layout helpers; no Screen, Minecraft or world is constructed. */
public final class MatchConfigFormChecks {
    private static int checks;
    private static final Class<?> RAW = rawClass();

    public static void main(String[] args) throws Exception {
        guidedRules();
        independentDropRules();
        equipmentLimit();
        Settings base = MatchConfigModel.defaults(7);
        var customFlow = new MatchConfigModel.FlowRules(17, 35, 200, 12, 24, 80, 4, .75, 15, 45, 4);
        Settings custom = new Settings(7, "自定义对局", 3, 20, 2, 45, 1500, 90,
                false, false, false, true, base.phases(), null, customFlow);
        Object raw = raw(custom);
        check(custom.equals(model(raw)), "all basic settings and phases round-trip through the real form");

        set(raw, "minimum", "");
        List<String> unfinished = values(raw);
        check(unfinished.contains(""), "a temporary empty field remains in the draft");
        check(unfinished.equals(values(raw)), "reading a draft does not validate or normalize it");
        check(rejectsModel(raw), "saving an unfinished integer fails without replacing the draft");
        check(unfinished.equals(values(raw)), "failed parsing preserves the original input");
        set(raw, "minimum", "3");

        Method replace = RAW.getDeclaredMethod("replaceTiming", Settings.class);
        replace.setAccessible(true);
        replace.invoke(raw, MatchConfigModel.rankedTimingPreset(7));
        Settings ranked = model(raw);
        check(ranked.mapId() == custom.mapId() && ranked.name().equals(custom.name())
                && ranked.minPlayers() == custom.minPlayers() && ranked.maxPlayers() == custom.maxPlayers()
                && ranked.teamSize() == custom.teamSize() && ranked.preparationSeconds() == 45
                && ranked.maxMatchSeconds() == 1500 && ranked.disconnectGraceSeconds() == 90
                && !ranked.blueEnabled() && !ranked.autoEnd() && !ranked.spectateEliminated() && ranked.friendlyFire(),
                "a timing preset preserves every basic draft setting");
        check(MatchConfigModel.totalPhaseSeconds(ranked) == 1810, "ranked timing is 30:10 without preparation");
        check(ranked.phases().getFirst().waitSeconds() == 180 && ranked.flowRules().firstCircleDelaySeconds() == 90,
                "timing preset replaces the independent delay and announced wait together");
        check(ranked.flowRules().equals(customFlow.withFirstCircleDelay(90)),
                "timing preset preserves custom bombing, flight and exposure fields");
        set(raw, "bombWarning", "");
        check(values(raw).contains("") && rejectsModel(raw), "unfinished flow input stays in the draft");
        set(raw, "bombWarning", "12");
        set(raw, "lateCenterBias", "37.5");
        check(model(raw).flowRules().lateCenterBias() == .375, "center bias percentage survives form conversion");
        set(raw, "lateCenterBias", "75");
        check(!MatchConfigModel.validate(ranked).isEmpty(), "short battle limit is rejected only when validating save");
        check(values(raw).contains("1500"), "a preset never silently raises the battle limit");
        set(raw, "limit", "2400");
        check(MatchConfigModel.validate(model(raw)).isEmpty(), "the author can fix the cross-page limit afterward");

        @SuppressWarnings("unchecked") List<Object> phases = (List<Object>) field(RAW, "phases").get(raw);
        Object first = phases.getFirst();
        Class<?> phaseClass = first.getClass();
        Field ratio = field(phaseClass, "ratio");
        ratio.set(first, "12.5");
        check(model(raw).phases().getFirst().radiusRatio() == .125, "percent input converts to the stored fraction");
        ratio.set(first, "NaN");
        check(rejectsModel(raw), "nonfinite numeric input cannot be saved");
        check("NaN".equals(ratio.get(first)), "invalid numeric text remains available for correction");
        ratio.set(first, "101");
        check(!MatchConfigModel.validate(model(raw)).isEmpty(), "a finite out-of-range ratio is rejected by the shared model");
        check("101".equals(ratio.get(first)), "range failures do not normalize percentages");

        for (int width : new int[]{280, 320, 480, 960}) for (int height : new int[]{240, 270, 300, 360, 480, 720}) {
            var frame = MatchConfigLayout.forViewport(width, height);
            check(frame.form().y() == 64 && frame.form().bottom() < frame.messageY(), "scroll viewport stays below tabs and above footer");
            int rowWidth = frame.form().width() - 14;
            int columns = rowWidth >= 460 ? 2 : 1;
            for (var cell : MatchConfigLayout.fields(rowWidth, columns)) {
                check(cell.control().width() >= 40 && cell.control().right() <= rowWidth, "all form inputs fit the scroll row");
                check(cell.control().bottom() < MatchConfigLayout.ROW_HEIGHT, "native fixed-height row exposes complete input");
            }
        }
        System.out.println("MatchConfigFormChecks: " + checks + " raw-draft, preset, percentage and responsive layout checks passed; no game launched.");
    }

    private static void equipmentLimit() throws Exception {
        Object raw = raw(MatchConfigModel.defaults(7));
        List<String> baseline = values(raw);
        check(model(raw).equipmentPerCategoryLimit() == 1, "equipment form defaults to one per category");
        set(raw, "equipmentLimit", "0");
        check(!values(raw).equals(baseline) && model(raw).equipmentPerCategoryLimit() == 0,
                "unlimited equipment is an explicit saved draft change");
        set(raw, "equipmentLimit", "2");
        Method replace = RAW.getDeclaredMethod("replaceTiming", Settings.class);
        replace.setAccessible(true);
        replace.invoke(raw, MatchConfigModel.rankedTimingPreset(7));
        check(model(raw).equipmentPerCategoryLimit() == 2, "timing presets preserve equipment rules");
        set(raw, "equipmentLimit", "");
        check(rejectsModel(raw) && values(raw).contains(""), "unfinished equipment limit is retained for correction");
        set(raw, "equipmentLimit", "65");
        check(rejectsModel(raw), "equipment form enforces its range");
    }

    private static void independentDropRules() throws Exception {
        Object raw = raw(MatchConfigModel.defaults(7));
        List<String> baseline = values(raw);
        var selection = new MatchRuleDraft();
        selection.acknowledge("drop-rules", baseline);
        field(RAW, "allowBlockDrops").setBoolean(raw, true);
        check(selection.dirty(values(raw)), "changing only block drops exposes save");
        check(model(raw).allowBlockDrops() && !model(raw).allowMobDrops(),
                "form can save block drops enabled with mob drops disabled");
        check(MatchConfigModel.parse(MatchConfigModel.toJson(model(raw))).equals(model(raw)),
                "independent form drop choices survive server serialization");
        field(RAW, "allowBlockDrops").setBoolean(raw, false);
        check(!selection.dirty(values(raw)), "restoring block drop value clears dirty state");
        field(RAW, "allowMobDrops").setBoolean(raw, true);
        check(selection.dirty(values(raw)), "changing only mob drops exposes save");
        check(!model(raw).allowBlockDrops() && model(raw).allowMobDrops(),
                "form can save mob drops enabled with block drops disabled");
        Object reloaded = raw(MatchConfigModel.parse(MatchConfigModel.toJson(model(raw))));
        check(values(reloaded).equals(values(raw)), "loading a saved form preserves both drop switches");
    }

    private static void guidedRules() throws Exception {
        var selection = new MatchRuleDraft();
        var baseline = List.of("name", "10", "4");
        check(!selection.selected() && !selection.dirty(baseline), "details and save stay hidden until selection or creation");
        check(selection.initialChoice(7, 7, "Last round", List.of("Last round")).equals("Last round"),
                "an untouched map draft accepts the server's last-played selection");
        check(selection.initialChoice(7, 8, "Other map", List.of("Other map")).isEmpty(),
                "a response for another map cannot select its preset in the current draft");
        check(selection.initialChoice(7, 7, "Deleted", List.of("Last round")).isEmpty(),
                "a deleted last-played rule leaves the selection list available");
        selection.acknowledge("rule", baseline);
        check(selection.initialChoice(7, 7, "Last round", List.of("Last round")).isEmpty(),
                "an explicit selection takes priority over remembered defaults");
        check(selection.selected() && !selection.dirty(baseline), "loaded rule exposes details without an unsaved rule");
        check(selection.dirty(List.of("name", "5", "4")), "any rule-field change exposes save");
        check(!selection.dirty(baseline), "undoing edits hides save again");
        selection.create(); check(selection.selected() && selection.dirty(baseline), "new rule is immediately saveable");
        check(selection.initialChoice(7, 7, "Last round", List.of("Last round")).isEmpty(),
                "a new unsaved rule draft also takes priority over the last-played default");
        selection.acknowledge("new", baseline); check(!selection.dirty(baseline), "server acknowledgement hides save");
        selection.clear(); check(!selection.selected(), "deleting selection hides its details");
        var previous = net.minecraft.locale.Language.getInstance();
        try {
            for (String locale : List.of("zh_cn", "en_us")) {
                net.minecraft.locale.Language.inject(com.example.examplemod.localization.TestLanguages.language(
                        com.example.examplemod.localization.TestLanguages.catalog(locale)));
                String prefix = locale.equals("zh_cn") ? "自定义规则" : "Custom Rule ";
                check(MatchRuleDraft.nextName(List.of(prefix + "1", prefix + "3")).equals(prefix + "4"), "placeholder follows largest existing suffix in " + locale);
            }
        } finally { net.minecraft.locale.Language.inject(previous); }
        // Exercise the real EditBox scroll state with a deterministic offline font, without rendering.
        var font = new net.minecraft.client.gui.Font(id -> null, false) {
            @Override public String plainSubstrByWidth(String text, int width) { return plainSubstrByWidth(text, width, false); }
            @Override public String plainSubstrByWidth(String text, int width, boolean reverse) {
                int count = Math.min(text.length(), Math.max(0, width / 8));
                return reverse ? text.substring(text.length() - count) : text.substring(0, count);
            }
        };
        var display = net.minecraft.client.gui.components.EditBox.class.getDeclaredField("displayPos"); display.setAccessible(true);
        for (String name : List.of("短局建议", "Short Match Suggestion", "Custom Rule 123")) {
            var box = new net.minecraft.client.gui.components.EditBox(font, 20, 20, net.minecraft.network.chat.Component.empty());
            box.setValue(name);
            check(display.getInt(box) > 0, "fixture reproduces text scrolled to the suffix at temporary narrow width");
            MatchConfigScreen.initializeEditBox(box, name, 80); box.setWidth(240);
            check(box.getValue().equals(name) && display.getInt(box) == 0 && box.getCursorPosition() == 0,
                    "initial layout shows complete name from its first character: " + name);
        }
    }

    private static Class<?> rawClass() {
        try { return Class.forName("com.example.examplemod.zones.match.client.MatchConfigScreen$RawSettings"); }
        catch (ClassNotFoundException error) { throw new AssertionError(error); }
    }
    private static Object raw(Settings settings) throws Exception {
        Constructor<?> constructor = RAW.getDeclaredConstructor(Settings.class);
        constructor.setAccessible(true);
        return constructor.newInstance(settings);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void set(Object raw, String name, String value) throws Exception { field(RAW, name).set(raw, value); }
    private static Settings model(Object raw) throws Exception {
        Method method = RAW.getDeclaredMethod("model"); method.setAccessible(true);
        return (Settings) method.invoke(raw);
    }
    @SuppressWarnings("unchecked") private static List<String> values(Object raw) throws Exception {
        Method method = RAW.getDeclaredMethod("values"); method.setAccessible(true);
        return (List<String>) method.invoke(raw);
    }
    private static boolean rejectsModel(Object raw) throws Exception {
        try { model(raw); return false; }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof IllegalArgumentException) return true;
            throw error;
        }
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
