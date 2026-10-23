package com.example.examplemod.visibility;

import com.tacz.guns.entity.TargetMinecart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public final class VisibilityEntities {
    private VisibilityEntities() {}
    public static boolean managed(Entity entity) { return kinds(entity) != 0; }
    public static int distance(Entity entity, VisibilityPolicy.Settings settings) {
        int kinds = kinds(entity);
        return VisibilityPolicy.distanceForKinds(settings, (kinds & 1) != 0, (kinds & 2) != 0);
    }
    private static int kinds(Entity entity) {
        int kinds = entity instanceof Player ? 1 : entity instanceof TargetMinecart ? 2 : 0;
        if (!entity.isVehicle()) return kinds;
        for (var passenger : entity.getIndirectPassengers()) {
            if (passenger instanceof Player) kinds |= 1;
            if (passenger instanceof TargetMinecart) kinds |= 2;
            if (kinds == 3) break;
        }
        return kinds;
    }
}
