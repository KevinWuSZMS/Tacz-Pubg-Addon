package com.example.examplemod.vitals;

import com.example.examplemod.localization.Texts;
import java.util.List;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

/** No food component: the server grants the completed treatment before it consumes the item. */
public class PubgMedicineItem extends Item {
    private final PubgMedicineModel.Kind kind;
    public PubgMedicineItem(Properties properties, PubgMedicineModel.Kind kind) { super(properties); this.kind = kind; }
    public final PubgMedicineModel.Kind kind() { return kind; }
    @Override public int getUseDuration(ItemStack stack, LivingEntity entity) {
        var settings = entity instanceof ServerPlayer player
                ? com.example.examplemod.pubgsettings.PubgSettingsService.server(player.server)
                : com.example.examplemod.pubgsettings.PubgSettingsService.client();
        String key = switch (kind) {
            case BANDAGE -> "bandage"; case FIRST_AID_KIT -> "first_aid"; case MED_KIT -> "med_kit";
            case ENERGY_DRINK -> "energy_drink"; case PAINKILLER -> "painkiller"; case ADRENALINE_SYRINGE -> "adrenaline";
        };
        return settings.integer("medicine." + key + "_use_ticks");
    }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return kind.isBoost() ? UseAnim.DRINK : UseAnim.NONE; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // Main-hand treatment is authorized by the shared interaction request, never vanilla right click.
        if (hand == InteractionHand.MAIN_HAND) return InteractionResultHolder.fail(player.getItemInHand(hand));
        return startTreatment(level, player, hand);
    }
    public InteractionResultHolder<ItemStack> startTreatment(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() || stack.getItem() != this || player.isUsingItem()) return InteractionResultHolder.fail(stack);
        // Reject in both logical sides: a server rejection alone leaves a predicted use animation/HUD.
        if (!kind.isBoost() && !kind.canUse(player.getHealth(), player.getMaxHealth(), 0)) {
            player.displayClientMessage(Texts.of("message.tacz_pubg.medicine.health_limit", (int) (kind.ceiling() * 100)), true);
            return InteractionResultHolder.fail(stack);
        }
        if (player instanceof ServerPlayer p && !PubgMedicineService.begin(p, this, hand)) {
            p.stopUsingItem();
            p.displayClientMessage(Texts.of(kind.isBoost()
                    ? "message.tacz_pubg.vitals.boost_unavailable" : "message.tacz_pubg.medicine.healing_unavailable"), true);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }
    @Override public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (entity instanceof ServerPlayer player) PubgMedicineService.validateUse(player);
    }
    @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        if (entity instanceof ServerPlayer player) PubgMedicineService.cancelUse(player);
    }
    @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer player && PubgMedicineService.finish(player, this, stack)) {
            CriteriaTriggers.CONSUME_ITEM.trigger(player, stack);
            player.awardStat(Stats.ITEM_USED.get(this));
            stack.consume(1, player);
            player.gameEvent(kind.isBoost() ? GameEvent.DRINK : GameEvent.ITEM_INTERACT_FINISH);
        }
        return stack;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Texts.of("screen.tacz_pubg.medicine.interaction_hint", Component.keybind("key.tacz_pubg.pickup_loot")));
        if (kind.isBoost()) {
            int useTicks = getUseDuration(stack, null);
            lines.add(Texts.of("screen.tacz_pubg.vitals.boost_item_hint", kind.boost(), String.format(java.util.Locale.ROOT, "%.1f", useTicks / 20.0)));
            lines.add(Texts.of("screen.tacz_pubg.medicine.boost_everywhere"));
        } else {
            String key = switch (kind) {
                case BANDAGE -> "screen.tacz_pubg.medicine.bandage_hint";
                case FIRST_AID_KIT -> "screen.tacz_pubg.medicine.first_aid_hint";
                default -> "screen.tacz_pubg.medicine.med_kit_hint";
            };
            lines.add(Texts.of(key));
        }
        lines.add(Texts.of("screen.tacz_pubg.medicine.cancel_hint"));
    }
}
