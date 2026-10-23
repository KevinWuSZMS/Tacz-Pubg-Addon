package com.example.examplemod.gunbalance.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.examplemod.gunbalance.GunBalanceService;
import com.example.examplemod.gunbalance.PubgScopeCompatibility;
import com.example.examplemod.gunbalance.PubgMagazineCompatibility;
import com.google.gson.JsonElement;
import com.tacz.guns.resource.manager.AttachmentsTagManager;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AttachmentsTagManager.class, remap = false)
public abstract class AttachmentsTagManagerMixin {
    @Shadow @Final private Map<ResourceLocation, Set<String>> tags;
    @Shadow @Final private Map<ResourceLocation, Set<String>> allow_attachments;
    @Shadow protected Map<ResourceLocation, String> networkCache;

    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL"), remap = false)
    private void taczPubg$applyAttachmentCompatibility(Map<ResourceLocation, List<JsonElement>> source,
            ResourceManager manager, ProfilerFiller profiler, CallbackInfo callback) {
        if (GunBalanceService.isScopeExperimentLoading()) {
            networkCache = PubgScopeCompatibility.extendNativeTags(manager, tags, allow_attachments, networkCache);
        }
        networkCache = PubgMagazineCompatibility.applyNativeTags(manager, tags, allow_attachments,
                networkCache, GunBalanceService.isMagazineExperimentLoading());
    }
}
