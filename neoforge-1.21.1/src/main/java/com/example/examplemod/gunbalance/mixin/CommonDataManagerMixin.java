package com.example.examplemod.gunbalance.mixin;

import java.util.Map;

import com.example.examplemod.gunbalance.GunBalanceService;
import com.google.gson.JsonElement;
import com.tacz.guns.resource.manager.CommonDataManager;
import com.tacz.guns.resource.network.DataType;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** TaCZ r7 has no public pre-parse gun-data event; this also updates its native network cache. */
@Mixin(value = CommonDataManager.class, remap = false)
public abstract class CommonDataManagerMixin {
    @Shadow @Final private DataType type;

    @ModifyVariable(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private Map<ResourceLocation, JsonElement> taczPubg$applyResearchBalance(
            Map<ResourceLocation, JsonElement> source, Map<ResourceLocation, JsonElement> original,
            ResourceManager manager, ProfilerFiller profiler) {
        return type == DataType.GUN_DATA ? GunBalanceService.transformLoadedData(source, manager) : source;
    }
}
