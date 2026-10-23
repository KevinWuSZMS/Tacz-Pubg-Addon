package com.example.examplemod.lootspawn;

import com.example.examplemod.ExampleMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/** Keeps vanilla flight uninterrupted and converts only after genuine ground contact. */
public final class DroppedLootService {
    private DroppedLootService() {}

    public static void register(IEventBus gameBus) {
        DroppedLootCatalog.register(gameBus);
        gameBus.addListener(DroppedLootService::toss);
        gameBus.addListener(DroppedLootService::joined);
        gameBus.addListener(DroppedLootService::afterTick);
    }

    private static final String YAW_SET = "tacz_pubg_toss_yaw_set";

    private static void toss(ItemTossEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level
                && DroppedLootPolicy.isConvertible(event.getEntity().getItem(), level))
            orient(event.getEntity(), event.getPlayer());
    }

    private static void joined(EntityJoinLevelEvent event) {
        // Do not read blocks here: the chunk may not yet be FULL. Loaded items keep their yaw.
        if (!event.loadedFromDisk() && event.getLevel() instanceof ServerLevel
                && event.getEntity() instanceof ItemEntity item && !item.getPersistentData().getBoolean(YAW_SET)
                && item.getOwner() instanceof Player player
                && DroppedLootPolicy.isConvertible(item.getItem(), event.getLevel())) orient(item, player);
    }

    private static void orient(ItemEntity item, Player player) {
        if (item.getPersistentData().getBoolean(YAW_SET)) return;
        item.setYRot(DroppedLootPolicy.tossYaw(player.getYRot(), item.getRandom().nextDouble()));
        item.yRotO = item.getYRot();
        item.getPersistentData().putBoolean(YAW_SET, true);
    }

    private static void afterTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemEntity source)
                || !(source.level() instanceof ServerLevel level) || source.isRemoved()
                || !source.onGround() || !DroppedLootPolicy.isConvertible(source.getItem(), level)
                || !DroppedLootPolicy.legalQuantity(source.getItem().getCount())) return;

        BlockPos floor = source.getOnPos();
        if (!level.hasChunkAt(floor)) return;
        var shape = level.getBlockState(floor).getCollisionShape(level, floor, CollisionContext.of(source));
        if (shape.toAabbs().stream().noneMatch(box ->
                DroppedLootPolicy.supports(source.getBoundingBox(), box.move(floor)))) return;

        // There is no public delay getter in 1.21.1. Use its public save method,
        // which also preserves custom ItemEntity subclasses' own delay semantics.
        CompoundTag original = new CompoundTag();
        try {
            source.addAdditionalSaveData(original);
        } catch (RuntimeException error) {
            ExampleMod.LOGGER.warn("Cannot inspect TaCZ drop {}; original retained", source.getUUID(), error);
            return;
        }
        int delay = original.getShort("PickupDelay");
        if (!DroppedLootPolicy.legalDelay(delay)) return; // Fake/never-pickup displays stay native.

        ItemStack stack = source.getItem().copy();
        GroundLootEntity target = LootSpawnRegistration.GROUND_LOOT.get().create(level);
        if (target == null) return;
        try {
            target.initializeDrop(stack, floor, delay, source.getTarget());
            target.setPos(source.getX(), source.getBoundingBox().minY, source.getZ());
            target.setYRot(DroppedLootPolicy.yaw(source));
            target.yRotO = target.getYRot();
            // ItemEntity applies a landing bounce; our ground presentation stops in place.
            target.setDeltaMovement(Vec3.ZERO);
            GroundLootWorldPhysics.prepareSpawn(target);
            if (!target.isPhysicsBlocked()) {
                target.setOnGround(true);
                target.setPhysicsState(GroundLootEntity.RESTING);
            }
            target.setCustomName(source.getCustomName());
            target.setCustomNameVisible(source.isCustomNameVisible());
            target.getItem().save(level.registryAccess()); // Check persistence before changing ownership.
            if (!level.addFreshEntity(target)) return;
        } catch (RuntimeException error) {
            // If an event listener failed after insertion, roll back the new carrier.
            if (level.getEntity(target.getUUID()) == target) target.discard();
            ExampleMod.LOGGER.error("Cannot convert TaCZ drop {}; original retained", source.getUUID(), error);
            return;
        }

        try {
            // Send while the source still has its tracking audience. This visual-only
            // notice never authorizes pickup or creates a second server-side stack.
            PacketDistributor.sendToPlayersTrackingEntity(source, new GroundLootPayloads.DropLanded(
                    source.getId(), source.getUUID(), target.getId(), target.getUUID(),
                    stack.copyWithCount(1), target.position(), target.getYRot(), target.getDeltaMovement()));
        } catch (RuntimeException error) {
            ExampleMod.LOGGER.warn("Drop converted but visual handoff notification failed", error);
        }
        source.setItem(ItemStack.EMPTY);
        source.discard();
    }
}
