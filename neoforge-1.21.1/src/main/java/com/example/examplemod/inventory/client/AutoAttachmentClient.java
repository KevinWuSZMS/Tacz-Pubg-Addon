package com.example.examplemod.inventory.client;

import com.example.examplemod.inventory.InventoryConfig;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Native slot synchronization arrives before this tick; refresh only when attachment data changes. */
@EventBusSubscriber(modid = "tacz_pubg", value = Dist.CLIENT)
public final class AutoAttachmentClient {
    private static LocalPlayer previousPlayer;
    private static ItemStack previousGun = ItemStack.EMPTY;

    private AutoAttachmentClient() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        if (player == null || !InventoryConfig.active(player)
                || !InventoryConfig.current(player).autoAttachmentEquip()
                || !(player.getMainHandItem().getItem() instanceof IGun gun)) {
            previousPlayer = null;
            previousGun = ItemStack.EMPTY;
            return;
        }
        ItemStack held = player.getMainHandItem();
        boolean changed = previousPlayer != player || previousGun.isEmpty()
                || !(previousGun.getItem() instanceof IGun previous)
                || !gun.getGunId(held).equals(previous.getGunId(previousGun));
        if (!changed) for (AttachmentType type : AttachmentType.values()) {
            if (type != AttachmentType.NONE
                    && !Objects.equals(gun.getAttachmentTag(held, type), gun.getAttachmentTag(previousGun, type))) {
                changed = true;
                break;
            }
        }
        if (changed) {
            // TaCZ's ordinary draw detection compares gun/display IDs, not attachment NBT; its
            // refit refresh packet only updates cache with the refit screen open. Auto-pickup has
            // neither trigger. Do not play draw or send another equipment/consumption packet.
            AttachmentPropertyManager.postChangeEvent(player, held);
            previousPlayer = player;
            previousGun = held.copy();
        }
    }
}
