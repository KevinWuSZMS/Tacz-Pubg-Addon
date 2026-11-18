package com.example.examplemod.inventory.client;

import com.example.examplemod.inventory.PubgInventoryPayloads;
import com.example.examplemod.inventory.PubgInventoryPayloads.Action;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.sound.SoundManager;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Presentation cache only. All moves and component changes are performed on the server. */
public final class PubgInventoryState {
    private static PubgInventoryPayloads.Snapshot snapshot;
    private static String lastMessage = "";
    private PubgInventoryState() {}
    public static void register(IEventBus bus) {
        PubgInventoryPayloads.setClient(p -> snapshot = p, PubgInventoryState::feedback);
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> { snapshot = null; lastMessage = ""; });
    }
    public static List<PubgInventoryPayloads.Nearby> nearby() { return snapshot == null ? List.of() : snapshot.nearby(); }
    public static List<ItemStack> inventory() { return snapshot == null ? List.of() : snapshot.inventory(); }
    public static ItemStack backpack() { return snapshot == null ? ItemStack.EMPTY : snapshot.backpack(); }
    public static String lastMessage() { return lastMessage; }
    public static long recent(ItemStack stack) {
        if (snapshot != null) for (var entry : snapshot.recent())
            if (ItemStack.isSameItemSameComponents(stack, entry.item())) return entry.order();
        return 0;
    }
    public static void requestSnapshot() { send(Action.REFRESH, false, -1, -1, ""); }
    public static void pickupGround(int entityId) { send(Action.PICKUP, true, entityId, -1, ""); }
    public static void rightClickInventory(int slot) { send(Action.RIGHT_CLICK, false, slot, -1, ""); }
    public static void moveInventory(int from, int to) { send(Action.MOVE, false, from, to, ""); }
    public static void equipGround(int entityId, int targetSlot) { send(Action.MOVE, true, entityId, targetSlot, ""); }
    public static void attachInventory(int from, int gunSlot, String type) { send(Action.ATTACH, false, from, gunSlot, type); }
    public static void attachGround(int entityId, int gunSlot, String type) { send(Action.ATTACH, true, entityId, gunSlot, type); }
    public static void unloadAttachment(int gunSlot, String type) { send(Action.UNLOAD, false, gunSlot, -1, type); }
    public static void transferAttachment(int gunSlot, String type, int target) { send(Action.UNLOAD, false, gunSlot, target, type); }
    public static void dropInventory(int slot) { send(Action.DROP, false, slot, -1, ""); }
    public static void removeBackpack() { send(Action.REMOVE_BACKPACK, false, 41, -1, ""); }
    private static void send(Action action, boolean ground, int source, int target, String type) {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().getConnection() == null) return;
        if (snapshot == null && action != Action.REFRESH) { requestSnapshot(); return; }
        PacketDistributor.sendToServer(new PubgInventoryPayloads.Command(action, snapshot == null ? -1 : snapshot.revision(), ground, source, target, type));
    }
    private static void feedback(PubgInventoryPayloads.Feedback result) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        lastMessage = result.message();
        if (!result.message().isBlank()) player.displayClientMessage(com.example.examplemod.localization.Texts.component(result.message()), true);
        if (!result.soundItem().isEmpty()) {
            SoundPlayManager.playerRefitSound(result.soundItem(), player, result.uninstall() ? SoundManager.UNINSTALL_SOUND : SoundManager.INSTALL_SOUND);
        }
        if (player.getInventory().selected == result.gunSlot()) AttachmentPropertyManager.postChangeEvent(player, player.getMainHandItem());
    }
}
