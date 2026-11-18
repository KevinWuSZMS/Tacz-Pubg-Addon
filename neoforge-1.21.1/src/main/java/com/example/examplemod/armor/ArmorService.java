package com.example.examplemod.armor;

import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.throwables.PubgThrowableEntity;
import com.example.examplemod.throwables.ThrowableKind;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.GunDamageSourcePart;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Bridges TaCZ's head flag and its two damage parts without a second vanilla armor reduction. */
public final class ArmorService {
    private record Protection(ServerPlayer player, ItemStack stack, EquipmentSlot slot, float factor, boolean wears) {}
    private static final Map<DamageSource, Protection> SOURCES = new WeakHashMap<>();
    private static final Map<DamageSource, Float> ACCEPTED = new WeakHashMap<>();
    private ArmorService() {}
    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, ArmorService::gun);
        bus.addListener(EventPriority.LOWEST, ArmorService::incoming);
        bus.addListener(EventPriority.LOWEST, ArmorService::beforeHealth);
        bus.addListener(EventPriority.LOWEST, ArmorService::afterHealth);
    }
    private static Protection protection(ServerPlayer player, EquipmentSlot slot, boolean wears) {
        ItemStack stack = player.getItemBySlot(slot);
        float factor = stack.getItem() instanceof PubgArmorItem armor
                ? ArmorRules.multiplier(armor.tier(), armor.helmet(), stack.getDamageValue() >= stack.getMaxDamage()) : 1;
        return new Protection(player, stack, slot, factor, wears);
    }
    private static void gun(EntityHurtByGunEvent.Pre event) {
        if (event.isCanceled() || !event.getLogicalSide().isServer()
                || !(event.getHurtEntity() instanceof ServerPlayer player) || !InventoryConfig.active(player)) return;
        // Snapshot once: the last durability point protects the entire bullet,
        // including TaCZ's normal and armor-piercing sub-hits.
        Protection protection = protection(player, event.isHeadShot() ? EquipmentSlot.HEAD : EquipmentSlot.CHEST, true);
        for (GunDamageSourcePart part : new GunDamageSourcePart[]{GunDamageSourcePart.NON_ARMOR_PIERCING, GunDamageSourcePart.ARMOR_PIERCING}) {
            DamageSource source = event.getDamageSource(part);
            if (source != null) SOURCES.put(source, protection);
        }
    }
    private static void incoming(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player) || !InventoryConfig.active(player)) return;
        Protection protection = SOURCES.get(event.getSource());
        if (protection == null || protection.player != player) {
            DamageSource source = event.getSource();
            if (source.getDirectEntity() instanceof PubgThrowableEntity throwable && throwable.kind() == ThrowableKind.FRAG) {
                // PC 6.2: vest reduces frag damage without consuming its health.
                protection = protection(player, EquipmentSlot.CHEST, false);
            } else if (!source.is(DamageTypeTags.IS_EXPLOSION) && !source.is(DamageTypeTags.BYPASSES_ARMOR)
                    && source.getEntity() != null) {
                // Vanilla arrows and melee lack TaCZ's head-hit classification.
                protection = protection(player, EquipmentSlot.CHEST, true);
            } else return; // Fall, fire, blue zone, C4 and other unclassified damage retain their own behavior.
            SOURCES.put(source, protection);
        }
        if (protection.player != player) return;
        // All native armor attributes are bypassed only for this active-round hit;
        // resistance effects and absorption remain part of the normal damage pipeline.
        event.addReductionModifier(DamageContainer.Reduction.ARMOR, (container, reduction) -> 0);
    }
    private static void beforeHealth(LivingDamageEvent.Pre event) {
        Protection protection = SOURCES.get(event.getSource());
        if (protection == null || event.getEntity() != protection.player || !InventoryConfig.active(protection.player)) return;
        float amount = event.getNewDamage();
        if (!(amount > 0) || !Float.isFinite(amount)) return;
        event.setNewDamage(amount * protection.factor);
        // Record this accepted part, not the full bullet, so AP splitting never
        // doubles armor wear. Absorption happens after this stage.
        if (protection.wears && protection.stack.getItem() instanceof PubgArmorItem) ACCEPTED.put(event.getSource(), amount);
    }
    private static void afterHealth(LivingDamageEvent.Post event) {
        Protection protection = SOURCES.remove(event.getSource());
        Float amount = ACCEPTED.remove(event.getSource());
        if (protection == null || event.getEntity() != protection.player || !protection.wears
                || !(protection.stack.getItem() instanceof PubgArmorItem armor)) return;
        // Consume the accepted amount captured immediately before health/absorption.
        // Canceled and fully blocked attacks never supply a damage part here.
        int wear = amount == null ? 0 : ArmorRules.wear(amount, protection.factor);
        if (wear == 0 || protection.stack.isEmpty()) return;
        ItemStack stack = protection.stack;
        var result = ArmorRules.afterWear(armor.helmet(), stack.getMaxDamage(), stack.getDamageValue(), wear);
        stack.setDamageValue(result.damage());
        if (result.removeItem()) {
            protection.player.onEquippedItemBroken(armor, protection.slot);
            stack.shrink(1);
        }
        // Official Update 7.2: broken vests remain equipped with their capacity and 20% protection.
        protection.player.getInventory().setChanged();
        protection.player.containerMenu.broadcastChanges();
    }
}
