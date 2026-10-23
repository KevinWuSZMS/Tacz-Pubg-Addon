package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.ZoneSettings.BombingProperties;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Saved rules for the next match. Runtime sessions own a separate immutable copy. */
public final class MatchConfigModel {
    public static final int MAX_PHASES = 16;
    public static final int MAX_PLAYERS = 100;
    public static final int MAX_SECONDS = 86_400;
    public static final int MAX_JSON_BYTES = 24 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> SETTINGS_KEYS = Set.of("mapId", "name", "minPlayers", "maxPlayers", "teamSize",
            "preparationSeconds", "maxMatchSeconds", "disconnectGraceSeconds", "blueEnabled", "autoEnd",
            "spectateEliminated", "friendlyFire", "phases");
    private static final Set<String> PHASE_KEYS = Set.of("waitSeconds", "shrinkSeconds", "radiusRatio", "damagePerSecond", "randomCenter");

    private MatchConfigModel() {}

    public record Phase(int waitSeconds, int shrinkSeconds, double radiusRatio, double damagePerSecond,
            boolean randomCenter) {}

    /** Independent match pacing; durations other than the published first-circle delay are configurable adaptations. */
    public record FlowRules(int firstCircleDelaySeconds, int flightSeconds, int bombingFirstSeconds,
            int bombingWarningSeconds, int bombingDurationSeconds, int bombingCooldownSeconds,
            int bombingLastPhase, double lateCenterBias, int exposureGraceSeconds, int exposureRampSeconds,
            double exposureMaxMultiplier) {
        public FlowRules {
            if (firstCircleDelaySeconds < 0 || firstCircleDelaySeconds > MAX_SECONDS
                    || flightSeconds < 0 || flightSeconds > MAX_SECONDS
                    || bombingFirstSeconds < 0 || bombingFirstSeconds > MAX_SECONDS
                    || bombingWarningSeconds < 0 || bombingWarningSeconds > MAX_SECONDS
                    || bombingDurationSeconds < 1 || bombingDurationSeconds > MAX_SECONDS
                    || bombingCooldownSeconds < 0 || bombingCooldownSeconds > MAX_SECONDS
                    || bombingLastPhase < 1 || bombingLastPhase > MAX_PHASES
                    || !Double.isFinite(lateCenterBias) || lateCenterBias < 0 || lateCenterBias > 1
                    || exposureGraceSeconds < 0 || exposureGraceSeconds > MAX_SECONDS
                    || exposureRampSeconds < 0 || exposureRampSeconds > MAX_SECONDS
                    || !Double.isFinite(exposureMaxMultiplier) || exposureMaxMultiplier < 1 || exposureMaxMultiplier > 100)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.invalid_flow_rules"));
        }
        public static FlowRules defaults() { return new FlowRules(90, 60, 150, 10, 20, 60, 2, .35, 10, 30, 3); }
        public static FlowRules legacy() { return defaults().withFirstCircleDelay(0); }
        public FlowRules withFirstCircleDelay(int seconds) {
            return new FlowRules(seconds, flightSeconds, bombingFirstSeconds, bombingWarningSeconds,
                    bombingDurationSeconds, bombingCooldownSeconds, bombingLastPhase, lateCenterBias,
                    exposureGraceSeconds, exposureRampSeconds, exposureMaxMultiplier);
        }
    }

    public record BombingRules(boolean enabled, double radiusRatio, double roundsPerMinute, BombingProperties projectile) {
        public BombingRules {
            if (!Double.isFinite(radiusRatio) || radiusRatio <= 0 || radiusRatio > 1
                    || !Double.isFinite(roundsPerMinute) || roundsPerMinute < 0 || roundsPerMinute > 6000
                    || roundsPerMinute != Math.rint(roundsPerMinute))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.bombardment_radius_ratio_missiles_per_minute_integer"));
            Objects.requireNonNull(projectile, Texts.tr("message.tacz_pubg.match_config_model.bombardment_projectile_parameters_required"));
        }
        public static BombingRules defaults() { return new BombingRules(true, .12, 60, BombingProperties.defaults()); }
    }

    /** Flight duration includes jump-lock lead-in plus route length / speed; FlowRules.flightSeconds is legacy data only. */
    public record FlightRules(boolean enabled, boolean randomRoute, double speedBlocksPerSecond,
            int heightAboveGround, int jumpDelaySeconds, int autoDeployHeight) {
        public FlightRules {
            if (!Double.isFinite(speedBlocksPerSecond) || speedBlocksPerSecond < 1 || speedBlocksPerSecond > 160
                    || heightAboveGround < 16 || heightAboveGround > 2000
                    || jumpDelaySeconds < 0 || jumpDelaySeconds > 60
                    || autoDeployHeight < minimumAutoDeployHeight(heightAboveGround) || autoDeployHeight > heightAboveGround)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.invalid_flight_rules"));
        }
        public static int minimumAutoDeployHeight(int flightHeight) { return (flightHeight + 9) / 10; }
        public static FlightRules defaults() { return new FlightRules(true, true, 40, 512, 3, 52); }
    }

    public record Settings(long mapId, String name, int minPlayers, int maxPlayers, int teamSize,
            int preparationSeconds, int maxMatchSeconds, int disconnectGraceSeconds, boolean blueEnabled,
            boolean autoEnd, boolean spectateEliminated, boolean friendlyFire, List<Phase> phases,
            BombingRules bombingRules, FlowRules flowRules, boolean allowBlockDrops, boolean allowMobDrops,
            FlightRules flightRules, int equipmentPerCategoryLimit) {
        /** Older integrations and saved rules start with one helmet, vest and backpack each. */
        public Settings(long mapId, String name, int minPlayers, int maxPlayers, int teamSize,
                int preparationSeconds, int maxMatchSeconds, int disconnectGraceSeconds, boolean blueEnabled,
                boolean autoEnd, boolean spectateEliminated, boolean friendlyFire, List<Phase> phases,
                BombingRules bombingRules, FlowRules flowRules, boolean allowBlockDrops, boolean allowMobDrops,
                FlightRules flightRules) {
            this(mapId, name, minPlayers, maxPlayers, teamSize, preparationSeconds, maxMatchSeconds,
                    disconnectGraceSeconds, blueEnabled, autoEnd, spectateEliminated, friendlyFire, phases,
                    bombingRules, flowRules, allowBlockDrops, allowMobDrops, flightRules, 1);
        }
        public Settings(long mapId, String name, int minPlayers, int maxPlayers, int teamSize,
                int preparationSeconds, int maxMatchSeconds, int disconnectGraceSeconds, boolean blueEnabled,
                boolean autoEnd, boolean spectateEliminated, boolean friendlyFire, List<Phase> phases) {
            this(mapId, name, minPlayers, maxPlayers, teamSize, preparationSeconds, maxMatchSeconds,
                    disconnectGraceSeconds, blueEnabled, autoEnd, spectateEliminated, friendlyFire, phases, null);
        }
        public Settings(long mapId, String name, int minPlayers, int maxPlayers, int teamSize,
                int preparationSeconds, int maxMatchSeconds, int disconnectGraceSeconds, boolean blueEnabled,
                boolean autoEnd, boolean spectateEliminated, boolean friendlyFire, List<Phase> phases, BombingRules bombingRules) {
            this(mapId, name, minPlayers, maxPlayers, teamSize, preparationSeconds, maxMatchSeconds,
                    disconnectGraceSeconds, blueEnabled, autoEnd, spectateEliminated, friendlyFire, phases, bombingRules,
                    FlowRules.legacy());
        }
        /** Existing integrations get the same safe defaults as older saved rules. */
        public Settings(long mapId, String name, int minPlayers, int maxPlayers, int teamSize,
                int preparationSeconds, int maxMatchSeconds, int disconnectGraceSeconds, boolean blueEnabled,
                boolean autoEnd, boolean spectateEliminated, boolean friendlyFire, List<Phase> phases,
                BombingRules bombingRules, FlowRules flowRules) {
            this(mapId, name, minPlayers, maxPlayers, teamSize, preparationSeconds, maxMatchSeconds,
                    disconnectGraceSeconds, blueEnabled, autoEnd, spectateEliminated, friendlyFire, phases,
                    bombingRules, flowRules, false, false, FlightRules.defaults());
        }
        public Settings {
            phases = List.copyOf(Objects.requireNonNull(phases, "phases"));
            Objects.requireNonNull(flowRules, "flowRules");
            Objects.requireNonNull(flightRules, "flightRules");
            if (equipmentPerCategoryLimit < 0 || equipmentPerCategoryLimit > 64)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.equipment.invalid_limit"));
        }
    }

    public static Settings forMap(Settings s, long mapId) {
        return new Settings(mapId, s.name(), s.minPlayers(), s.maxPlayers(), s.teamSize(), s.preparationSeconds(),
                s.maxMatchSeconds(), s.disconnectGraceSeconds(), s.blueEnabled(), s.autoEnd(), s.spectateEliminated(),
                s.friendlyFire(), s.phases(), s.bombingRules(), s.flowRules(), s.allowBlockDrops(), s.allowMobDrops(),
                s.flightRules(), s.equipmentPerCategoryLimit());
    }

    /** Normal-match timing reference; radius and damage retain the existing Minecraft adaptation. */
    public static Settings defaults(long mapId) {
        return normalTimingPreset(mapId);
    }

    public static Settings normalTimingPreset(long mapId) {
        int[] waits = {240, 80, 80, 80, 80, 60, 40, 30, 30};
        int[] moves = {270, 100, 100, 100, 80, 60, 60, 60, 90};
        return preset(mapId, Texts.tr("screen.tacz_pubg.match_config.pubg_normal_timing_reference"), waits, moves, 2400);
    }

    /** Original short timetable, with its opening 90 seconds now represented separately. */
    public static Settings shortTimingPreset(long mapId) {
        int[] waits = {30, 90, 60, 45, 30, 20, 15, 10, 0};
        int[] moves = {120, 90, 75, 60, 45, 30, 25, 20, 15};
        return preset(mapId, Texts.tr("screen.tacz_pubg.match_config.short_match_suggestion"), waits, moves, 2400);
    }

    /** PUBG PC 42.1 ranked timing chart, with its independent 90-second initial delay. */
    public static Settings rankedTimingPreset(long mapId) {
        int[] waits = {180, 60, 60, 60, 60, 60, 60, 60, 10};
        int[] moves = {240, 120, 120, 120, 120, 120, 90, 60, 120};
        return preset(mapId, Texts.tr("screen.tacz_pubg.match_config.pubg_ranked_timing_reference"), waits, moves, 2400);
    }

    private static Settings preset(long mapId, String name, int[] waits, int[] moves, int maximum) {
        double[] ratios = {.65, .55, .60, .60, .65, .65, .65, .65, .001};
        double[] damage = {.2, .3, .4, .6, 1, 1.4, 1.8, 2.2, 2.8};
        List<Phase> phases = new ArrayList<>();
        for (int i = 0; i < waits.length; i++) phases.add(new Phase(waits[i], moves[i], ratios[i], damage[i], i < 8));
        return new Settings(mapId, name, 1, 20, 1, 30, maximum, 60, true, true, true, false,
                phases, BombingRules.defaults(), FlowRules.defaults());
    }

    public static long totalPhaseSeconds(Settings settings) {
        return settings.flowRules().firstCircleDelaySeconds()
                + settings.phases().stream().mapToLong(p -> (long) p.waitSeconds() + p.shrinkSeconds()).sum();
    }

    public static List<String> validate(Settings settings) {
        if (settings == null) return List.of(Texts.tr("message.tacz_pubg.match_config_model.select_match_configuration_first"));
        List<String> errors = new ArrayList<>();
        if (settings.mapId() <= 0 || settings.mapId() == Long.MAX_VALUE) errors.add(Texts.tr("message.tacz_pubg.match_config_model.valid_saved_map_id_required"));
        if (settings.name() == null || settings.name().isBlank() || settings.name().length() > 80
                || settings.name().codePoints().anyMatch(Character::isISOControl)) errors.add(Texts.tr("message.tacz_pubg.match_config_model.enter_displayable_characters_as_match_name"));
        range(errors, Texts.tr("screen.tacz_pubg.match_config.minimum_participants.model"), settings.minPlayers(), 1, MAX_PLAYERS);
        range(errors, Texts.tr("screen.tacz_pubg.match_config.maximum_participants"), settings.maxPlayers(), 1, MAX_PLAYERS);
        if (settings.minPlayers() > settings.maxPlayers()) errors.add(Texts.tr("message.tacz_pubg.match_config_model.set_minimum_participants_no_greater_than_maximum_participants"));
        range(errors, Texts.tr("message.tacz_pubg.match_config_model.maximum_players_per_team"), settings.teamSize(), 1, MAX_PLAYERS);
        if (settings.teamSize() > settings.maxPlayers()) errors.add(Texts.tr("message.tacz_pubg.match_config_model.team_size_not_exceed_maximum_participants"));
        range(errors, Texts.tr("screen.tacz_pubg.match_config.preparation_time"), settings.preparationSeconds(), 0, 3600);
        range(errors, Texts.tr("screen.tacz_pubg.match_config.combat_time_limit"), settings.maxMatchSeconds(), 1, MAX_SECONDS);
        range(errors, Texts.tr("screen.tacz_pubg.match_config.disconnect_grace"), settings.disconnectGraceSeconds(), 0, 3600);
        if (settings.phases().isEmpty() || settings.phases().size() > MAX_PHASES) errors.add(Texts.tr("message.tacz_pubg.match_config_model.number_zone_phases", MAX_PHASES));
        for (int i = 0; i < settings.phases().size(); i++) {
            Phase p = settings.phases().get(i);
            String label = Texts.tr("message.tacz_pubg.match_config_model.zone", (i + 1));
            range(errors, label + Texts.tr("message.tacz_pubg.match_config_model.wait_seconds"), p.waitSeconds(), 0, MAX_SECONDS);
            range(errors, label + Texts.tr("message.tacz_pubg.match_config_model.shrink_seconds"), p.shrinkSeconds(), 1, MAX_SECONDS);
            if (!Double.isFinite(p.radiusRatio()) || p.radiusRatio() <= 0 || p.radiusRatio() > 1)
                errors.add(label + Texts.tr("message.tacz_pubg.match_config_model.valid_radius_ratio"));
            range(errors, label + Texts.tr("message.tacz_pubg.match_config_model.blue_zone_damage_hp_s"), p.damagePerSecond(), 0, MapZoneModel.MAX_DAMAGE);
        }
        if (totalPhaseSeconds(settings) > MAX_SECONDS) errors.add(Texts.tr("message.tacz_pubg.match_config_model.combined_zone_phase_duration_hours"));
        // Disabled blue zones still consume the authored timetable; the explicit time limit always applies.
        if (totalPhaseSeconds(settings) > settings.maxMatchSeconds()) errors.add(Texts.tr("message.tacz_pubg.match_config_model.combat_time_limit_cover_combined_duration_all_zone"));
        return List.copyOf(errors);
    }

    public static String toJson(Settings settings) {
        requireValid(settings);
        String json = GSON.toJson(settings);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.match_configuration_exceeds_kib"));
        return json;
    }

    public static Settings parse(String json) {
        JsonObject root = object(readJson(json), Texts.tr("message.tacz_pubg.match_config_model.match_configuration"));
        JsonElement rawBombing = root.remove("bombingRules");
        JsonElement rawFlow = root.remove("flowRules");
        JsonElement rawFlight = root.remove("flightRules");
        int equipmentLimit = root.has("equipmentPerCategoryLimit") ? integer(root, "equipmentPerCategoryLimit") : 1;
        root.remove("equipmentPerCategoryLimit");
        // The former switch controlled every item entity. Only use it as a fallback for the two natural-drop rules.
        boolean legacyDrops = root.has("allowEntityDrops") && bool(root, "allowEntityDrops");
        boolean allowBlockDrops = root.has("allowBlockDrops") ? bool(root, "allowBlockDrops") : legacyDrops;
        boolean allowMobDrops = root.has("allowMobDrops") ? bool(root, "allowMobDrops") : legacyDrops;
        root.remove("allowEntityDrops");
        root.remove("allowBlockDrops");
        root.remove("allowMobDrops");
        exactKeys(root, SETTINGS_KEYS, Texts.tr("message.tacz_pubg.match_config_model.match_configuration"));
        BombingRules bombingRules = rawBombing == null || rawBombing.isJsonNull() ? null : parseBombing(rawBombing);
        JsonElement rawPhases = root.get("phases");
        if (!rawPhases.isJsonArray() || rawPhases.getAsJsonArray().size() > MAX_PHASES)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.zone_phase_array_contain_entries"));
        List<Phase> phases = new ArrayList<>();
        for (JsonElement element : rawPhases.getAsJsonArray()) {
            JsonObject p = object(element, Texts.tr("message.tacz_pubg.match_config_model.zone_phase"));
            exactKeys(p, PHASE_KEYS, Texts.tr("message.tacz_pubg.match_config_model.zone_phase"));
            phases.add(new Phase(integer(p, "waitSeconds"), integer(p, "shrinkSeconds"), number(p, "radiusRatio"),
                    number(p, "damagePerSecond"), bool(p, "randomCenter")));
        }
        FlowRules flow;
        if (rawFlow == null) {
            // Older rules folded initial looting time into the first wait. Preserve the total authored timetable.
            int delay = phases.isEmpty() ? 0 : Math.min(90, Math.max(0, phases.getFirst().waitSeconds()));
            flow = FlowRules.defaults().withFirstCircleDelay(delay);
            if (!phases.isEmpty()) {
                Phase first = phases.getFirst();
                phases.set(0, new Phase(first.waitSeconds() - delay, first.shrinkSeconds(),
                        first.radiusRatio(), first.damagePerSecond(), first.randomCenter()));
            }
        } else flow = parseFlow(rawFlow);
        long id;
        try { numeric(root, "mapId"); id = root.get("mapId").getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_model.map_id_integer"), error); }
        Settings settings = new Settings(id, string(root, "name"), integer(root, "minPlayers"), integer(root, "maxPlayers"),
                integer(root, "teamSize"), integer(root, "preparationSeconds"), integer(root, "maxMatchSeconds"),
                integer(root, "disconnectGraceSeconds"), bool(root, "blueEnabled"), bool(root, "autoEnd"),
                bool(root, "spectateEliminated"), bool(root, "friendlyFire"), phases, bombingRules, flow,
                allowBlockDrops, allowMobDrops, rawFlight == null ? FlightRules.defaults() : parseFlight(rawFlight), equipmentLimit);
        requireValid(settings);
        return settings;
    }

    private static FlowRules parseFlow(JsonElement element) {
        JsonObject f = object(element, Texts.tr("screen.tacz_pubg.match_config.flow_rules"));
        exactKeys(f, Set.of("firstCircleDelaySeconds", "flightSeconds", "bombingFirstSeconds",
                "bombingWarningSeconds", "bombingDurationSeconds", "bombingCooldownSeconds", "bombingLastPhase",
                "lateCenterBias", "exposureGraceSeconds", "exposureRampSeconds", "exposureMaxMultiplier"),
                Texts.tr("screen.tacz_pubg.match_config.flow_rules"));
        return new FlowRules(integer(f, "firstCircleDelaySeconds"), integer(f, "flightSeconds"),
                integer(f, "bombingFirstSeconds"), integer(f, "bombingWarningSeconds"),
                integer(f, "bombingDurationSeconds"), integer(f, "bombingCooldownSeconds"),
                integer(f, "bombingLastPhase"), number(f, "lateCenterBias"), integer(f, "exposureGraceSeconds"),
                integer(f, "exposureRampSeconds"), number(f, "exposureMaxMultiplier"));
    }

    private static FlightRules parseFlight(JsonElement element) {
        JsonObject f = object(element, Texts.tr("screen.tacz_pubg.match_config.flight_rules"));
        exactKeys(f, Set.of("enabled", "randomRoute", "speedBlocksPerSecond", "heightAboveGround",
                "jumpDelaySeconds", "autoDeployHeight"), Texts.tr("screen.tacz_pubg.match_config.flight_rules"));
        int height = integer(f, "heightAboveGround"), deploy = integer(f, "autoDeployHeight");
        // Keep previously valid saved rules readable when the new proportional minimum rises.
        if (height >= 16 && height <= 2000 && deploy >= 8 && deploy <= 128 && deploy < height)
            deploy = Math.max(deploy, FlightRules.minimumAutoDeployHeight(height));
        return new FlightRules(bool(f, "enabled"), bool(f, "randomRoute"), number(f, "speedBlocksPerSecond"),
                height, integer(f, "jumpDelaySeconds"), deploy);
    }

    private static BombingRules parseBombing(JsonElement element) {
        JsonObject b = object(element, Texts.tr("message.tacz_pubg.match_config_model.bombardment_rules"));
        exactKeys(b, Set.of("enabled", "radiusRatio", "roundsPerMinute", "projectile"), Texts.tr("message.tacz_pubg.match_config_model.bombardment_rules"));
        JsonObject p = object(b.get("projectile"), Texts.tr("message.tacz_pubg.match_config_model.bombardment_projectile_parameters"));
        exactKeys(p, Set.of("ammoId", "damage", "damageRadius", "edgeDamageMultiplier", "destroyBlocks", "spawnHeight", "passThroughBlocks"), Texts.tr("message.tacz_pubg.match_config_model.bombardment_projectile_parameters"));
        if (!p.get("passThroughBlocks").isJsonArray()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.pass_through_blocks_array"));
        List<String> blocks = new ArrayList<>();
        for (JsonElement v : p.getAsJsonArray("passThroughBlocks")) {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.pass_through_blocks_id_strings"));
            blocks.add(v.getAsString());
        }
        return new BombingRules(bool(b, "enabled"), number(b, "radiusRatio"), number(b, "roundsPerMinute"),
                new BombingProperties(string(p, "ammoId"), number(p, "damage"), number(p, "damageRadius"),
                        number(p, "edgeDamageMultiplier"), bool(p, "destroyBlocks"), integer(p, "spawnHeight"), blocks));
    }

    private static void requireValid(Settings settings) {
        List<String> errors = validate(settings);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
    }

    private static JsonElement readJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.match_configuration_exceeds_kib_empty"));
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.unexpected_content_after_json"));
            return result;
        } catch (IOException | NumberFormatException error) { throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.invalid_match_json_format"), error); }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 6) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.match_json_nesting_too_deep"));
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.duplicate_json_field", key));
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) {
                    if (array.size() >= 256) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.match_json_array_exceeds_entries"));
                    array.add(read(reader, depth + 1));
                }
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_model.enter_valid_json_value"));
        };
    }

    private static JsonObject object(JsonElement element, String label) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.match_config_model.object"));
        return element.getAsJsonObject();
    }
    private static void exactKeys(JsonObject root, Set<String> keys, String label) {
        if (!root.keySet().equals(keys)) throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.match_config_model.check_field_names_completeness"));
        for (String key : keys) if (root.get(key).isJsonNull()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.add_missing_field", key));
    }
    private static void numeric(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.lobby_model.number"));
    }
    private static double number(JsonObject root, String key) {
        numeric(root, key); double value = root.get(key).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.match_config_model.finite_number"));
        return value;
    }
    private static int integer(JsonObject root, String key) {
        numeric(root, key);
        try { return root.get(key).getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.lobby_model.integer"), error); }
    }
    private static boolean bool(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.match_config_model.boolean"));
        return value.getAsBoolean();
    }
    private static String string(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.lobby_model.string"));
        return value.getAsString();
    }
    private static void range(List<String> errors, String name, double value, double low, double high) {
        if (!Double.isFinite(value) || value < low || value > high) errors.add(name + Texts.tr("message.tacz_pubg.match_config_model.range") + low + "–" + high);
    }
}
