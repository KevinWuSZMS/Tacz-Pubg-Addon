package com.example.examplemod.zones.match.flight;

import com.example.examplemod.ExampleMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;

/**
 * Leggings-slot flight marker without armor attributes or a humanoid armor layer.
 * The authored pack item and deployed canopy follow the selected detailed/pixel model style.
 */
public final class ParachuteItem extends Item implements Equipable {
    public static final int DARK_GREEN = 0x1F3D2B;
    /** The issued equipment stack is synchronized to every player tracking its wearer. */
    public static final String FLIGHT_STAGE_TAG = "flight_stage";

    public ParachuteItem(Properties properties) {
        super(properties
                .component(DataComponents.DYED_COLOR, new DyedItemColor(DARK_GREEN, false))
                .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.LEGS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide) com.example.examplemod.backpack.BackpackService.equipHand(player, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public boolean onDroppedByPlayer(ItemStack stack, Player player) {
        // Round-issued equipment is restored/removed by the flight service, never left as loot.
        return !stack.getOrDefault(DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().hasUUID("flight_pack");
    }

    public static ItemStack createStack() {
        return ExampleMod.PARACHUTE.get().getDefaultInstance();
    }

    public static boolean isParachute(ItemStack stack) {
        return stack.is(ExampleMod.PARACHUTE.get());
    }

    public static boolean isEquipped(LivingEntity entity) {
        return isParachute(entity.getItemBySlot(EquipmentSlot.LEGS));
    }
}
