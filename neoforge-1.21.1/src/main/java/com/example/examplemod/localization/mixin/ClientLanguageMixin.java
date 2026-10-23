package com.example.examplemod.localization.mixin;

import com.example.examplemod.localization.ChineseLanguageFallback;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.ArrayList;
import java.util.List;

/** Insert the addon's Chinese fallback before a selected Chinese variant; pack overrides still apply. */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
    @Redirect(method = "loadFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ResourceManager;getResourceStack(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;"))
    private static List<Resource> taczPubg$chineseFallback(ResourceManager manager, ResourceLocation location) {
        List<Resource> selected = manager.getResourceStack(location);
        if (!ChineseLanguageFallback.needsFallback(location.getNamespace(), location.getPath())) return selected;
        List<Resource> combined = new ArrayList<>(manager.getResourceStack(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "lang/zh_cn.json")));
        combined.addAll(selected);
        return combined;
    }
}
