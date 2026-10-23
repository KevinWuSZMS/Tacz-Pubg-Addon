package com.example.examplemod.gunbalance.mixin;

import java.util.List;

import com.example.examplemod.gunbalance.PubgScopeCompatibility;
import com.example.examplemod.gunbalance.client.ScopeAnchorGeometry;
import com.example.examplemod.gunbalance.client.UniversalScopeAnchor;
import com.example.examplemod.gunbalance.client.UniversalScopeAnchorAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps authored mounts unchanged; missing mounts share one path between model and ADS rendering. */
@Mixin(value = BedrockGunModel.class, remap = false)
public abstract class BedrockGunModelScopeMixin implements UniversalScopeAnchorAccess {
    @Shadow protected List<BedrockPart> scopePosPath;
    @Unique private List<BedrockPart> taczPubg$authoredScopePath;
    @Unique private List<BedrockPart> taczPubg$fallbackScopePath;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void taczPubg$rememberAuthoredScope(CallbackInfo callback) {
        taczPubg$authoredScopePath = scopePosPath;
    }

    @Override
    public void taczPubg$prepareScopeAnchor(ItemStack stack) {
        // Models are shared by multiple stacks. Always clear a previous stack's temporary mount,
        // including after an interrupted render or a profile change.
        taczPubg$restoreScopeAnchor();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null || taczPubg$authoredScopePath != null) return;
        ResourceLocation scopeId = gun.getAttachmentId(stack, AttachmentType.SCOPE);
        BedrockGunModel model = (BedrockGunModel) (Object) this;
        List<BedrockPart> iron = model.getIronSightPath();
        if (!ScopeAnchorGeometry.shouldUseFallback(false,
                scopeId != null && !DefaultAssets.isEmptyAttachmentId(scopeId),
                PubgScopeCompatibility.isUniversalScopeEnabled(gun.getGunId(stack), scopeId), iron != null && !iron.isEmpty())) return;
        if (taczPubg$fallbackScopePath == null) {
            taczPubg$fallbackScopePath = UniversalScopeAnchor.create(iron, model.getMuzzleFlashPosPath());
        }
        scopePosPath = taczPubg$fallbackScopePath;
    }

    @Override
    public void taczPubg$restoreScopeAnchor() {
        scopePosPath = taczPubg$authoredScopePath;
    }

    @Inject(method = {
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            "renderAccelerated(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V"},
            at = @At("HEAD"), remap = false)
    private void taczPubg$prepareRenderAnchor(PoseStack pose, ItemStack stack,
            ItemDisplayContext context, RenderType type, int light, int overlay, CallbackInfo callback) {
        taczPubg$prepareScopeAnchor(stack);
    }

    @Inject(method = {
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            "renderAccelerated(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V"},
            at = @At("RETURN"), remap = false)
    private void taczPubg$clearRenderAnchor(PoseStack pose, ItemStack stack,
            ItemDisplayContext context, RenderType type, int light, int overlay, CallbackInfo callback) {
        taczPubg$restoreScopeAnchor();
    }
}
