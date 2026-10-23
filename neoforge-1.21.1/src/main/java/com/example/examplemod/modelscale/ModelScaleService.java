package com.example.examplemod.modelscale;

import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.originalicons.OriginalIconState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public final class ModelScaleService {
    private ModelScaleService() {}
    public static String clientStyle() { return style(OriginalIconState.lowPolyModels()); }
    public static String style(boolean lowPoly) { return lowPoly ? ModelScaleSettings.PIXEL : ModelScaleSettings.PLAYFUL; }
    public static String style(Level level) {
        return level instanceof ServerLevel server ? style(LootConfigStore.current(server.getServer().overworld()).gunSettings().lowPolyModels())
                : clientStyle();
    }
    public static double dropped(Level level, ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!ModelScaleSettings.supported(id)) return 1;
        var settings = level instanceof ServerLevel server ? ModelScaleSavedData.get(server.getServer()).settings() : ModelScaleState.settings();
        return settings.get(style(level), id).dropped();
    }
    public static double worn(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return ModelScaleSettings.supported(id) ? ModelScaleState.settings().get(clientStyle(), id).worn() : 1;
    }
    public static AABB scale(AABB bounds, double multiplier) {
        return new AABB(bounds.minX * multiplier, bounds.minY * multiplier, bounds.minZ * multiplier,
                bounds.maxX * multiplier, bounds.maxY * multiplier, bounds.maxZ * multiplier);
    }
    /** Keep the fixed 2 mm floor lift independent of visual size. */
    public static AABB groundBounds(AABB bounds, double multiplier, double floorLift) {
        return scale(bounds.move(0, -floorLift, 0), multiplier).move(0, floorLift, 0);
    }
}
