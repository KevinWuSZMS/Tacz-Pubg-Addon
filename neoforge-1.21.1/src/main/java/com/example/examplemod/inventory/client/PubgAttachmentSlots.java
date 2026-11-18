package com.example.examplemod.inventory.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.gui.GunRefitScreen;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Read-only slot presentation using TaCZ r7's actual refit frame, type atlas and item renderer. */
public final class PubgAttachmentSlots {
    private PubgAttachmentSlots() { }

    /** A locked gun still exposes its supported slots for inspection; editing is checked separately. */
    public static List<AttachmentType> supportedTypes(ItemStack gun) {
        var api = IGun.getIGunOrNull(gun);
        if (api == null) return List.of();
        return Arrays.stream(AttachmentType.values())
                .filter(type -> type != AttachmentType.NONE && api.allowAttachmentType(gun, type)).toList();
    }

    public static boolean supports(ItemStack gun, AttachmentType type) {
        var api = IGun.getIGunOrNull(gun);
        return api != null && type != null && type != AttachmentType.NONE && api.allowAttachmentType(gun, type);
    }

    public static boolean isLocked(ItemStack gun) {
        var api = IGun.getIGunOrNull(gun);
        return api == null || api.hasAttachmentLock(gun);
    }

    public static boolean isBuiltIn(ItemStack gun, AttachmentType type) {
        var api = IGun.getIGunOrNull(gun);
        return supports(gun, type) && !DefaultAssets.isEmptyAttachmentId(api.getBuiltInAttachmentId(gun, type));
    }

    /** Only the installed stack is returned: a built-in attachment must never become an unload source. */
    public static ItemStack installed(ItemStack gun, AttachmentType type) {
        var level = Minecraft.getInstance().level;
        if (level == null || !supports(gun, type)) return ItemStack.EMPTY;
        return IGun.getIGunOrNull(gun).getAttachment(level.registryAccess(), gun, type).copy();
    }

    /** Show a supported built-in part when no removable attachment occupies the slot. */
    public static ItemStack displayed(ItemStack gun, AttachmentType type) {
        if (!supports(gun, type)) return ItemStack.EMPTY;
        ItemStack actual = installed(gun, type);
        return actual.isEmpty() ? IGun.getIGunOrNull(gun).getBuiltinAttachment(gun, type).copy() : actual;
    }

    public static boolean canRemove(ItemStack gun, AttachmentType type) {
        return supports(gun, type) && !isLocked(gun) && !isBuiltIn(gun, type) && !installed(gun, type).isEmpty();
    }

    public static boolean accepts(ItemStack gun, AttachmentType type, ItemStack attachment) {
        var api = IGun.getIGunOrNull(gun);
        var part = IAttachment.getIAttachmentOrNull(attachment);
        return gun.getCount() == 1 && supports(gun, type) && !isLocked(gun) && !isBuiltIn(gun, type)
                && part != null && part.getType(attachment) == type && api.allowAttachment(gun, attachment)
                && !ItemStack.isSameItemSameComponents(installed(gun, type), attachment);
    }

    /** UV mapping comes from the installed TaCZ screen, so gun-pack attachment types retain native icons. */
    public static int emptyIconU(ItemStack gun, AttachmentType type) {
        return supports(gun, type) ? GunRefitScreen.getSlotTextureXOffset(gun, type) : -1;
    }

    public static void draw(GuiGraphics graphics, ItemStack gun, AttachmentType type,
                            int x, int y, int size, boolean hover, boolean compatible) {
        if (size <= 0 || !supports(gun, type)) return;
        int nativeSize = GunRefitScreen.SLOT_SIZE;
        graphics.flush();
        graphics.pose().pushPose();
        float[] color = RenderSystem.getShaderColor().clone();
        try {
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(size / (float) nativeSize, size / (float) nativeSize, 1);
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            // These are exactly the frame crops used by TaCZ's GunAttachmentSlot.
            if (hover || compatible)
                graphics.blit(GunRefitScreen.SLOT_TEXTURE, 0, 0, 0, 0, nativeSize, nativeSize, nativeSize, nativeSize);
            else
                graphics.blit(GunRefitScreen.SLOT_TEXTURE, 1, 1, 1, 1, nativeSize - 2, nativeSize - 2, nativeSize, nativeSize);
            ItemStack part = displayed(gun, type);
            if (!part.isEmpty()) graphics.renderItem(part, 1, 1);
            else graphics.blit(GunRefitScreen.ICONS_TEXTURE, 2, 2, nativeSize - 4, nativeSize - 4,
                    emptyIconU(gun, type), 0, GunRefitScreen.ICON_UV_SIZE, GunRefitScreen.ICON_UV_SIZE,
                    GunRefitScreen.getSlotsTextureWidth(), GunRefitScreen.ICON_UV_SIZE);
            graphics.flush();
        } finally {
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            graphics.pose().popPose();
        }
    }
}
