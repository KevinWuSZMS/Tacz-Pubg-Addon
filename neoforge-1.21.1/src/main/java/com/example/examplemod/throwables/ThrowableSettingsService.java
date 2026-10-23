package com.example.examplemod.throwables;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import me.xjqsh.lrtactical.item.index.ThrowableIndex;
import me.xjqsh.lrtactical.item.throwable.ThrowableData;
import me.xjqsh.lrtactical.resource.CommonAssetsManager;
import me.xjqsh.lrtactical.resource.manager.ThrowableIndexManager;
import me.xjqsh.lrtactical.init.ModRegistries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Supplies both LR's native indexes and PUBG mechanics from the same saved settings. */
public final class ThrowableSettingsService {
    private static volatile JsonObject client = new JsonObject();
    private static final Map<ThrowableIndex<?, ?>, Map<JsonObject, ThrowableIndex<?, ?>>> CACHE = new IdentityHashMap<>();
    private static final Map<JsonObject, Map<ThrowableKind, ThrowableSettingsModel.Profile>> PROFILES = new IdentityHashMap<>();
    private static final Map<JsonObject, Map<ThrowableKind, ThrowableIndex<?, ?>>> PUBG_INDEXES = new IdentityHashMap<>();
    private static final Map<ThrowableIndex<?, ?>, JsonObject> SOURCES = new WeakHashMap<>();
    private ThrowableSettingsService() {}
    public static void register(IEventBus bus) {
        bus.addListener((OnDatapackSyncEvent event) -> {
            clearCaches();
            ThrowableSettingsSavedData.get(event.getPlayerList().getServer()).verifyNative();
            if (event.getPlayer() != null) ThrowableSettingsPayloads.push(event.getPlayer());
            else for (var player : event.getPlayerList().getPlayers()) ThrowableSettingsPayloads.push(player);
        });
        bus.addListener((ServerStoppedEvent event) -> clearCaches());
    }
    public static void clearCaches() { synchronized (CACHE) { CACHE.clear(); PROFILES.clear(); PUBG_INDEXES.clear(); } }
    public static void acceptClient(JsonObject settings) { client = settings.deepCopy(); clearCaches(); }
    public static void clearClient() { client = new JsonObject(); clearCaches(); }
    private static JsonObject settings() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread() ? ThrowableSettingsSavedData.get(server).view() : client;
    }
    public static ThrowableSettingsModel.Profile profile(ThrowableKind kind) {
        JsonObject snapshot = settings();
        synchronized (CACHE) {
            return PROFILES.computeIfAbsent(snapshot, ignored -> new EnumMap<>(ThrowableKind.class)).computeIfAbsent(kind, ignored -> {
                var overrides = snapshot.getAsJsonObject("tacz_pubg:" + kind.id);
                return new ThrowableSettingsModel.Profile(overrides == null ? ThrowableSettingsModel.defaults(kind)
                        : ThrowableSettingsModel.merge(ThrowableSettingsModel.defaults(kind), overrides));
            });
        }
    }
    private static ThrowableIndex<?, ?> originalIndex(ResourceLocation id) {
        var provider = CommonAssetsManager.get();
        if (provider instanceof CommonAssetsManager assets && assets.throwableIndexManager != null)
            return assets.throwableIndexManager.getData(id);
        return me.xjqsh.lrtactical.resource.CommonNetworkCache.INSTANCE.throwableIndex.get(id);
    }
    public static ThrowableIndex<?, ?> nativeIndex(ResourceLocation id) { return overrideIndex(originalIndex(id)); }
    public static ThrowableData nativeData(ResourceLocation id) { var index = nativeIndex(id); return index == null ? null : index.getData(); }
    public static ThrowableIndex<?, ?> nativeIndex(ThrowableKind kind) {
        JsonObject snapshot = settings();
        synchronized (CACHE) {
            return PUBG_INDEXES.computeIfAbsent(snapshot, ignored -> new EnumMap<>(ThrowableKind.class))
                    .computeIfAbsent(kind, ignored -> buildPubgIndex(kind));
        }
    }
    private static ThrowableIndex<?, ?> buildPubgIndex(ThrowableKind kind) {
        var original = nativeIndex(LrThrowableBridge.id(kind));
        if (original == null) return null;
        return parsed(original, pubgDefinition(kind, profile(kind), original));
    }
    private static JsonObject pubgDefinition(ThrowableKind kind, ThrowableSettingsModel.Profile p, ThrowableIndex<?, ?> original) {
        JsonObject data = nativeJson(original).getAsJsonObject("data");
        JsonObject overrides = p.json();
        if (overrides.has("native_data")) data = ThrowableSettingsModel.merge(data, overrides.getAsJsonObject("native_data"));
        data.addProperty("prepare_time", p.prepareTicks()); data.addProperty("cooldown", p.cooldownTicks());
        data.addProperty("stack_size", p.stackSize()); data.addProperty("cookable", p.cookable());
        data.addProperty("initial_speed", p.speed());
        JsonObject entity = data.getAsJsonObject("entity");
        entity.addProperty("life_time", kind == ThrowableKind.SMOKE ? p.fieldTicks() : Math.max(1, p.fuseTicks()));
        entity.addProperty("gravity", p.gravity()); entity.addProperty("bounce_factor", p.bounce()); entity.addProperty("hit_damage", p.hitDamage());
        if (kind == ThrowableKind.STUN && data.has("stun")) data.getAsJsonObject("stun").addProperty("radius", p.radius());
        JsonObject definition = nativeJson(original); definition.add("data", data);
        return definition;
    }
    public static ThrowableData nativeData(ThrowableKind kind) { var index = nativeIndex(kind); return index == null ? null : index.getData(); }
    public static ThrowableIndex<?, ?> overrideIndex(ThrowableIndex<?, ?> original) {
        if (original == null) return null;
        JsonObject snapshot = settings();
        JsonObject override = snapshot.getAsJsonObject(original.getId().toString());
        if (override == null) return original;
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(original, ignored -> new IdentityHashMap<>()).computeIfAbsent(snapshot,
                    ignored -> parsed(original, ThrowableSettingsModel.merge(nativeJson(original), override)));
        }
    }
    private static ThrowableIndex<?, ?> parsed(ThrowableIndex<?, ?> original, JsonObject definition) {
        var result = ThrowableIndexManager.parse(definition, original.getId());
        rememberSource(result, definition);
        return result;
    }
    public static JsonObject catalog() {
        JsonObject result = new JsonObject();
        for (var index : CommonAssetsManager.get().getThrowableIndexes()) result.add(index.getId().toString(), nativeJson(index));
        for (var kind : ThrowableKind.values()) {
            JsonObject defaults = ThrowableSettingsModel.defaults(kind);
            var nativeIndex = nativeIndex(LrThrowableBridge.id(kind));
            if (nativeIndex != null) {
                JsonObject data = nativeJson(nativeIndex).getAsJsonObject("data");
                JsonObject extras = new JsonObject();
                if (data.has("stun") && kind == ThrowableKind.STUN) {
                    var stun = data.getAsJsonObject("stun").deepCopy(); stun.remove("radius"); extras.add("stun", stun);
                }
                JsonObject entity = data.getAsJsonObject("entity").deepCopy();
                for (String duplicate : new String[]{"gravity", "bounce_factor", "hit_damage", "life_time"}) entity.remove(duplicate);
                extras.add("entity", entity); extras.add("put_away_time", data.get("put_away_time"));
                defaults.add("native_data", extras);
            }
            defaults.keySet().removeIf(key -> !ThrowableSettingsModel.applicable(kind, key));
            result.add("tacz_pubg:" + kind.id, defaults);
        }
        return result;
    }
    public static void validateNative(JsonObject settings) {
        for (var entry : settings.entrySet()) {
            ResourceLocation id = ResourceLocation.parse(entry.getKey());
            if (id.getNamespace().equals("tacz_pubg")) {
                ThrowableKind kind = java.util.Arrays.stream(ThrowableKind.values()).filter(value -> id.getPath().equals(value.id)).findFirst().orElseThrow();
                var nativeIndex = originalIndex(LrThrowableBridge.id(kind));
                if (nativeIndex != null) {
                    var profile = new ThrowableSettingsModel.Profile(ThrowableSettingsModel.merge(ThrowableSettingsModel.defaults(kind), entry.getValue().getAsJsonObject()));
                    validateParsed(ThrowableIndexManager.parse(pubgDefinition(kind, profile, nativeIndex), nativeIndex.getId()));
                }
                continue;
            }
            var original = originalIndex(id);
            if (original == null) throw new IllegalArgumentException("Unknown throwable: " + id);
            validateParsed(ThrowableIndexManager.parse(ThrowableSettingsModel.merge(nativeJson(original), entry.getValue().getAsJsonObject()), id));
        }
    }
    private static void validateParsed(ThrowableIndex<?, ?> index) {
        if (index == null || index.getData() == null || index.getData().getEntityData() == null)
            throw new IllegalArgumentException("A throwable must retain its data and entity definition");
        if (index.getData().getStackSize() < 1 || index.getData().getStackSize() > 99)
            throw new IllegalArgumentException("Native throwable stack_size must be between 1 and 99");
    }
    public static JsonObject nativeJson(ThrowableIndex<?, ?> index) {
        synchronized (SOURCES) {
            JsonObject remembered = SOURCES.get(index);
            if (remembered != null) return remembered.deepCopy();
        }
        JsonObject value = new JsonObject();
        value.addProperty("type", ModRegistries.THROWABLE_TYPE_REGISTRY.getKey(index.getType()).toString());
        value.addProperty("base_item", BuiltInRegistries.ITEM.getKey(index.getBaseItem()).toString());
        value.addProperty("name", index.getDescriptionId());
        if (index.getTooltip() != null) value.addProperty("tooltip", index.getTooltip());
        value.add("data", properties(index.getData()));
        // LR's original JSON retains custom serializers, records and particle parameters exactly.
        var manager = CommonAssetsManager.INSTANCE;
        if (manager != null && manager.throwableIndexManager != null
                && manager.throwableIndexManager.getData(index.getId()) == index) {
            String raw = manager.throwableIndexManager.getCache().get(index.getId());
            if (raw != null) value = ThrowableSettingsModel.merge(value, com.google.gson.JsonParser.parseString(raw).getAsJsonObject());
        }
        rememberSource(index, value);
        return value.deepCopy();
    }
    public static void rememberSource(ThrowableIndex<?, ?> index, JsonObject definition) {
        if (index == null) return;
        synchronized (SOURCES) { SOURCES.put(index, definition.deepCopy()); }
    }
    public static void rememberNetworkSources(Map<ResourceLocation, String> definitions,
            Map<ResourceLocation, ThrowableIndex<?, ?>> indexes) {
        for (var entry : definitions.entrySet()) {
            var index = indexes.get(entry.getKey());
            if (index == null) continue;
            JsonObject schema = new JsonObject(); schema.add("data", properties(index.getData()));
            rememberSource(index, ThrowableSettingsModel.merge(schema,
                    com.google.gson.JsonParser.parseString(entry.getValue()).getAsJsonObject()));
        }
        clearCaches();
    }
    private static JsonElement properties(Object object) {
        if (object == null) return JsonNull.INSTANCE;
        if (object instanceof String value) return new JsonPrimitive(value);
        if (object instanceof Number value) return new JsonPrimitive(value);
        if (object instanceof Boolean value) return new JsonPrimitive(value);
        if (object instanceof ResourceLocation value) return new JsonPrimitive(value.toString());
        if (object instanceof ParticleOptions value) return new JsonPrimitive(particleDefinition(value));
        if (object instanceof Iterable<?> list) {
            var result = new com.google.gson.JsonArray(); for (Object value : list) result.add(properties(value)); return result;
        }
        JsonObject result = new JsonObject();
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass())
            for (var field : type.getDeclaredFields()) {
                SerializedName name = field.getAnnotation(SerializedName.class);
                if (name == null || Modifier.isStatic(field.getModifiers())) continue;
                try { field.setAccessible(true); result.add(name.value(), properties(field.get(object))); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static String particleDefinition(ParticleOptions value) {
        String id = BuiltInRegistries.PARTICLE_TYPE.getKey(value.getType()).toString();
        if (value instanceof net.minecraft.core.particles.SimpleParticleType) return id;
        var type = (net.minecraft.core.particles.ParticleType<ParticleOptions>) value.getType();
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var options = type.codec().codec().encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), value).getOrThrow();
        return id + options;
    }
}
