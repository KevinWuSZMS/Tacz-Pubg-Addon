package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.google.gson.JsonParser;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Server-safe numeric mesh footprints. No client models or GPU classes are loaded. */
public final class GroundLootFootprints {
    public static final double FLOOR_LIFT = .002;
    public static final float COLLISION_WIDTH = .22F;
    public static final float COLLISION_HEIGHT = .13F;
    private static final AABB COLLISION = new AABB(-COLLISION_WIDTH / 2.0, 0, -COLLISION_WIDTH / 2.0,
            COLLISION_WIDTH / 2.0, COLLISION_HEIGHT, COLLISION_WIDTH / 2.0);
    private static final AABB ICON = new AABB(-GroundLootVisualPolicy.ICON_SIZE / 2, 0, -GroundLootVisualPolicy.ICON_SIZE / 2,
            GroundLootVisualPolicy.ICON_SIZE / 2, .027, GroundLootVisualPolicy.ICON_SIZE / 2);
    /** Authored tacz:item/attachment GROUND scale is 0.5; retain it for uncached icon-only attachments. */
    private static final AABB ATTACHMENT_ICON = new AABB(-.25, 0, -.25, .25, .027, .25);
    private static final AABB GENERIC = new AABB(-.25, 0, -.25, .25, .40, .25);
    private static final AABB UNKNOWN_GUN = new AABB(-1.50, 0, -.65, 1.50, .80, .65);
    private static final Map<ResourceLocation, AABB> GUNS = load("guns");
    private static final Map<ResourceLocation, AABB> AMMOS = load("ammo");
    private static final Map<ResourceLocation, AABB> ATTACHMENTS = load("attachments");
    private static final Map<String, String> VISUALS = loadVisuals();

    private GroundLootFootprints() {}

    /** Centered X/Z and minY=0; height includes the renderer's 2 mm floor lift. */
    public static AABB forItem(ItemStack stack) {
        return forItem(stack, null);
    }
    public static AABB forItem(ItemStack stack, net.minecraft.world.level.Level level) {
        AABB authored = com.example.examplemod.modelscale.ModelScaleGeometry.baseGroundBounds(stack, level);
        if (authored != null) return com.example.examplemod.modelscale.ModelScaleService.scale(authored,
                com.example.examplemod.modelscale.ModelScaleService.dropped(level, stack)).move(0, FLOOR_LIFT, 0);
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun != null) return gunBounds(gun.getGunId(stack));
        if (stack.getItem() instanceof IAmmo ammo) return AMMOS.getOrDefault(ammo.getAmmoId(stack), ICON);
        if (stack.getItem() instanceof IAttachment attachment) return ATTACHMENTS.getOrDefault(attachment.getAttachmentId(stack), ATTACHMENT_ICON);
        return GENERIC;
    }

    public static AABB gunBounds(ResourceLocation gunId) {
        return GUNS.getOrDefault(gunId, UNKNOWN_GUN);
    }

    /** Original upright carrier: yaw, model size and attachments never enlarge physical collision. */
    public static AABB collisionBounds() { return COLLISION; }

    public static AABB ammoBounds(ResourceLocation ammoId) { return AMMOS.getOrDefault(ammoId, ICON); }
    public static AABB attachmentBounds(ResourceLocation attachmentId) { return ATTACHMENTS.getOrDefault(attachmentId, ATTACHMENT_ICON); }

    /** Remove placement clearance margins before aiming at the rendered model. */
    public static AABB selectionBounds(ItemStack stack) {
        return selectionBounds(stack, null);
    }
    public static AABB selectionBounds(ItemStack stack, net.minecraft.world.level.Level level) {
        AABB safe = forItem(stack, level);
        return switch (renderKind(stack)) {
            case "3d_full", "3d_thin" -> new AABB(safe.minX + .02, FLOOR_LIFT, safe.minZ + .02,
                    safe.maxX - .02, Math.max(FLOOR_LIFT, safe.maxY - .015), safe.maxZ - .02);
            case "icon_2d" -> new AABB(safe.minX, FLOOR_LIFT, safe.minZ, safe.maxX, safe.maxY, safe.maxZ);
            default -> safe;
        };
    }

    public static String renderKind(ItemStack stack) {
        String key;
        if (stack.getItem() instanceof IGun gun) key = "guns/" + gun.getGunId(stack);
        else if (stack.getItem() instanceof IAmmo ammo) key = "ammo/" + ammo.getAmmoId(stack);
        else if (stack.getItem() instanceof IAttachment attachment) key = "attachments/" + attachment.getAttachmentId(stack);
        else return "generic";
        return VISUALS.getOrDefault(key, "unknown");
    }

    public static Vec3 localPoint(Vec3 world, Vec3 origin, float yaw) {
        Vec3 p = world.subtract(origin);
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        return new Vec3(p.x * cos - p.z * sin, p.y, p.x * sin + p.z * cos);
    }

    public static Vec3 worldPoint(Vec3 local, Vec3 origin, float yaw) {
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        return new Vec3(local.x * cos + local.z * sin, local.y, -local.x * sin + local.z * cos).add(origin);
    }

    public static AABB worldBounds(AABB local, Vec3 origin, float yaw) {
        // Analytic rotated-rectangle bounds avoid eight vectors/boxes on every
        // entity culling call and placement candidate, including off-center meshes.
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        double centerX = (local.minX + local.maxX) / 2, centerZ = (local.minZ + local.maxZ) / 2;
        double x = origin.x + centerX * cos + centerZ * sin;
        double z = origin.z - centerX * sin + centerZ * cos;
        double halfX = (local.maxX - local.minX) / 2, halfZ = (local.maxZ - local.minZ) / 2;
        double extentX = Math.abs(cos) * halfX + Math.abs(sin) * halfZ;
        double extentZ = Math.abs(sin) * halfX + Math.abs(cos) * halfZ;
        return new AABB(x - extentX, origin.y + local.minY, z - extentZ,
                x + extentX, origin.y + local.maxY, z + extentZ);
    }

    /** A compact UI/help explanation, independent of client classes and a running game. */
    public static String visualDescription(ItemStack stack) {
        return switch (renderKind(stack)) {
                case "3d_thin" -> Texts.tr("message.tacz_pubg.ground_loot_footprints.thin_d_model_native_dropped_item_size_lying");
                case "icon_2d" -> Texts.tr("message.tacz_pubg.ground_loot_footprints.no_d_model_native_dropped_item_icon_size");
                case "3d_full" -> Texts.tr("message.tacz_pubg.ground_loot_footprints.full_d_model_normal_dropped_item_size_lying");
                case "generic" -> Texts.tr("message.tacz_pubg.ground_loot_footprints.ordinary_item_native_dropped_item_size_lying_flat");
                default -> Texts.tr("message.tacz_pubg.ground_loot_footprints.uncached_model_conservative_footprint");
            };
    }

    private static Map<String, String> loadVisuals() {
        Map<String, String> types = new HashMap<>();
        try (var stream = GroundLootFootprints.class.getResourceAsStream("/assets/tacz_pubg/ground_loot_footprints.json")) {
            if (stream != null) {
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.get("version").getAsInt() != GroundLootVisualPolicy.PROFILE_VERSION) return Map.of();
                json.getAsJsonObject("render_types").entrySet()
                        .forEach(entry -> types.put(entry.getKey(), entry.getValue().getAsString()));
            }
        } catch (Exception ignored) {}
        return Map.copyOf(types);
    }

    private static Map<ResourceLocation, AABB> load(String category) {
        Map<ResourceLocation, AABB> result = new HashMap<>();
        try (var stream = GroundLootFootprints.class.getResourceAsStream("/assets/tacz_pubg/ground_loot_footprints.json")) {
            if (stream == null) return Map.of();
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("version") || json.get("version").getAsInt() != GroundLootVisualPolicy.PROFILE_VERSION)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ground_loot_footprints.ground_loot_footprint_version_does_not_match_native_size_policy"));
            if (!json.has(category)) return Map.of();
            for (var entry : json.getAsJsonObject(category).entrySet()) {
                var size = entry.getValue().getAsJsonArray();
                double width = size.get(0).getAsDouble(), height = size.get(1).getAsDouble(), depth = size.get(2).getAsDouble();
                if (!Double.isFinite(width + height + depth) || width <= 0 || height <= 0 || depth <= 0) continue;
                result.put(ResourceLocation.parse(entry.getKey()), new AABB(-width / 2, 0, -depth / 2,
                        width / 2, height + FLOOR_LIFT, depth / 2));
            }
        } catch (Exception error) {
            ExampleMod.LOGGER.warn("Unable to load ground-loot footprints; using safe conservative bounds", error);
        }
        return Map.copyOf(result);
    }
}
