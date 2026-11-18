package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import java.util.function.Predicate;
import java.util.UUID;

/** Persistent loot with the original small collision box, fixed yaw and authoritative gravity. */
public final class GroundLootEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> ITEM =
            SynchedEntityData.defineId(GroundLootEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> QUANTITY =
            SynchedEntityData.defineId(GroundLootEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> PHYSICS_STATE =
            SynchedEntityData.defineId(GroundLootEntity.class, EntityDataSerializers.BYTE);
    public static final byte FALLING = 0, RESTING = 1, BLOCKED = 2;
    private static final String SYSTEM = "tacz_pubg_ground_loot";
    private BlockPos anchor = BlockPos.ZERO;
    private long generation;
    private boolean validAnchor;
    private String origin = DroppedLootPolicy.RESPAWN;
    private int pickupDelay;
    private UUID pickupTarget;
    private int clientLerpSteps;
    private Vec3 clientTarget;
    private static Predicate<GroundLootEntity> clientHighlight = ignored -> false;

    public static void setClientHighlight(Predicate<GroundLootEntity> handler) {
        clientHighlight = handler;
    }

    @Override
    public boolean isCurrentlyGlowing() {
        return super.isCurrentlyGlowing() || level().isClientSide && clientHighlight.test(this);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return getItem().isEmpty() ? super.getBoundingBoxForCulling()
                : GroundLootFootprints.worldBounds(GroundLootFootprints.forItem(getItem(), level()), position(), getYRot());
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        // Use the full model enclosure rather than the historical tiny carrier's
        // ~12-block distance cutoff.
        double size = getBoundingBoxForCulling().getSize();
        if (!Double.isFinite(size) || size <= 0) size = 1;
        double range = size * 64 * Entity.getViewScale();
        return distance < range * range;
    }

    public GroundLootEntity(EntityType<? extends GroundLootEntity> type, Level level) {
        super(type, level);
        noPhysics = false;
        setNoGravity(false);
        setInvulnerable(true);
    }

    public void initialize(ItemStack item, BlockPos floor, long generation) {
        if (item.isEmpty() || item.getCount() > item.getMaxStackSize()) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ground_loot_entity.ground_loot_nonempty_item_stack_valid_count"));
        }
        initialize(item, item.getCount(), floor, generation);
    }

    /** A logical pile can hold one magazine even when it exceeds inventory stack limits. */
    public void initialize(ItemStack item, int quantity, BlockPos floor, long generation) {
        if (item.isEmpty() || quantity < 1 || quantity > LootPileQuantities.MAX_QUANTITY) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ground_loot_entity.ground_loot_count_between", LootPileQuantities.MAX_QUANTITY));
        }
        entityData.set(ITEM, item.copyWithCount(1));
        entityData.set(QUANTITY, quantity);
        anchor = floor.immutable();
        this.generation = generation;
        validAnchor = true;
        origin = DroppedLootPolicy.RESPAWN;
        pickupDelay = 0;
        pickupTarget = null;
        setPhysicsState(FALLING);
    }

    public void initializeDrop(ItemStack item, BlockPos floor, int delay, UUID target) {
        if (!DroppedLootPolicy.legalDelay(delay)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ground_loot_entity.invalid_dropped_item_pickup_delay"));
        initialize(item, item.getCount(), floor, 0);
        origin = DroppedLootPolicy.DROP;
        pickupDelay = delay;
        pickupTarget = target;
    }

    public boolean isRespawnManaged() {
        return validAnchor && DroppedLootPolicy.RESPAWN.equals(origin);
    }

    public boolean canBePickedUpBy(Player player) {
        return isResting() && pickupDelay == 0 && mayBeListedFor(player);
    }

    /** An explicit inventory transfer need not wait for the world pickup animation/delay.
     * The inventory authority separately validates visibility, distance and the snapshot. */
    public boolean canBePickedUpFromInventory(Player player) {
        return !isPhysicsBlocked() && mayBeListedFor(player);
    }

    /** A visible pile can still be falling or waiting for its original pickup delay. */
    public boolean mayBeListedFor(Player player) {
        return pickupTarget == null || pickupTarget.equals(player.getUUID());
    }

    public boolean isResting() { return entityData.get(PHYSICS_STATE) == RESTING; }
    public boolean isPhysicsBlocked() { return entityData.get(PHYSICS_STATE) == BLOCKED; }
    public void setPhysicsState(byte value) {
        if (value == BLOCKED && !level().isClientSide && entityData.get(PHYSICS_STATE) != BLOCKED)
            ExampleMod.LOGGER.warn("Ground loot carrier {} at {} is obstructed; item retained until obstruction is removed",
                    getUUID(), position());
        entityData.set(PHYSICS_STATE, value);
    }

    @Override protected double getDefaultGravity() { return GroundLootPhysics.GRAVITY; }

    // Entity.makeBoundingBox uses the original registered .22 x .13 dimensions.
    // Visual culling and aiming bounds above are deliberately independent.
    public void refreshCollisionBounds() { setBoundingBox(makeBoundingBox()); }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        if (!level().isClientSide) { super.lerpTo(x, y, z, yaw, pitch, steps); return; }
        clientTarget = new Vec3(x, y, z);
        clientLerpSteps = 1;
        setYRot(yaw);
    }

    @Override
    public Component getName() {
        if (getCustomName() != null) return getCustomName();
        return getItem().isEmpty() ? super.getName() : getItem().getHoverName();
    }

    /** Canonical count-one visual; the real quantity is stored separately. */
    public ItemStack getItem() {
        return entityData.get(ITEM);
    }

    public int getQuantity() {
        return entityData.get(QUANTITY);
    }

    /** Server inventory transactions consume a real carrier only after all destination checks pass. */
    public boolean consumeForInventory(Player player, ItemStack expected, int amount) {
        if (level().isClientSide || isRemoved() || !validAnchor || player.level() != level()
                || !canBePickedUpFromInventory(player) || amount <= 0 || amount > getQuantity()
                || !ItemStack.isSameItemSameComponents(getItem(), expected)) return false;
        int remaining = getQuantity() - amount;
        entityData.set(QUANTITY, remaining);
        if (remaining == 0) discard();
        return true;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public long generation() {
        return generation;
    }

    public boolean isManagedLoot() {
        return validAnchor;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ITEM, ItemStack.EMPTY);
        builder.define(QUANTITY, 0);
        builder.define(PHYSICS_STATE, FALLING);
    }

    @Override
    public void tick() {
        // Do not run baseTick's burn/portal/despawn hooks; motion is our own swept solver.
        if (level().isClientSide) {
            if (clientLerpSteps > 0 && clientTarget != null) {
                setPos(position().lerp(clientTarget, 1.0 / clientLerpSteps));
                clientLerpSteps--;
            }
            return;
        }
        if (!validAnchor || getItem().isEmpty() || getQuantity() <= 0) { discard(); return; }
        if (pickupDelay > 0) pickupDelay--;
        noPhysics = false;
        setNoGravity(false);
        GroundLootWorldPhysics.tick(this);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        validAnchor = SYSTEM.equals(tag.getString("System")) && tag.contains("Anchor", Tag.TAG_LONG);
        anchor = BlockPos.of(tag.getLong("Anchor"));
        generation = tag.getLong("Generation");
        ItemStack item = ItemStack.parseOptional(registryAccess(), tag.getCompound("Item"));
        int quantity = 0;
        try {
            if (item.isEmpty()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ground_loot_entity.empty_loot_record"));
            origin = DroppedLootPolicy.restoredOrigin(tag.contains("Origin", Tag.TAG_STRING), tag.getString("Origin"));
            pickupDelay = tag.getInt("PickupDelay");
            if (!DroppedLootPolicy.legalDelay(pickupDelay)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ground_loot_entity.invalid_pickup_delay"));
            pickupTarget = tag.hasUUID("PickupTarget") ? tag.getUUID("PickupTarget") : null;
            quantity = LootPileQuantities.restored(tag.getInt("Version"), tag.contains("Quantity", Tag.TAG_INT),
                    tag.getInt("Quantity"), item.getCount(), item.getMaxStackSize());
        } catch (IllegalArgumentException error) {
            ExampleMod.LOGGER.warn("Discarding invalid ground loot at {}: {}", anchor, error.getMessage());
            item = ItemStack.EMPTY;
            validAnchor = false;
        }
        entityData.set(ITEM, item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1));
        entityData.set(QUANTITY, quantity);
        noPhysics = false;
        setNoGravity(false);
        setInvulnerable(true);
        setPhysicsState(FALLING); // Legacy frozen records recheck their support on the next tick.
        refreshCollisionBounds();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("System", SYSTEM);
        tag.putInt("Version", 2);
        tag.putLong("Anchor", anchor.asLong());
        tag.putLong("Generation", generation);
        tag.put("Item", getItem().saveOptional(registryAccess()));
        tag.putInt("Quantity", getQuantity());
        tag.putString("Origin", origin);
        tag.putInt("PickupDelay", pickupDelay);
        if (pickupTarget != null) tag.putUUID("PickupTarget", pickupTarget);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown() || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!validAnchor || isRemoved() || player.isSpectator() || getItem().isEmpty() || getQuantity() <= 0
                || player.level() != level() || !player.canInteractWithEntity(this, 0)
                || !player.hasLineOfSight(this)) {
            return InteractionResult.FAIL;
        }
        return pickup(player);
    }

    /** Reused after the F payload's authoritative empty-hand/ray checks. */
    public InteractionResult pickup(Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer p && com.example.examplemod.combat.CombatService.actionBlocked(p)) return InteractionResult.FAIL;
        if (level().isClientSide || !validAnchor || isRemoved() || player.isSpectator()
                || player.level() != level() || getItem().isEmpty() || getQuantity() <= 0
                || !canBePickedUpBy(player)) return InteractionResult.FAIL;
        if (com.example.examplemod.inventory.EquipmentInventoryRules.category(getItem()) != null) {
            if (!exchangeEquipment(player, com.example.examplemod.inventory.InventoryConfig.active(player)
                    && com.example.examplemod.inventory.InventoryConfig.current(player).autoEquipmentReplacement()))
                player.displayClientMessage(Component.translatable("message.tacz_pubg.equipment.swap_rejected"), true);
            return InteractionResult.CONSUME;
        }
        if (com.example.examplemod.inventory.InventoryConfig.active(player)
                && com.example.examplemod.inventory.MatchInventoryService.weapon(getItem())
                && weaponSlotsFull(player, getItem())) {
            boolean handgun = com.example.examplemod.inventory.MatchInventoryService.role(getItem()).equals("handgun");
            int selected = player.getInventory().selected;
            if (handgun ? selected != 2 : selected != 0 && selected != 1) {
                player.displayClientMessage(Component.translatable("message.tacz_pubg.equipment.select_weapon_to_replace"), true);
                return InteractionResult.CONSUME;
            }
            if (!exchangeWeapon(player))
                player.displayClientMessage(Component.translatable("message.tacz_pubg.equipment.swap_rejected"), true);
            return InteractionResult.CONSUME;
        }
        ItemStack original = getItem().copy();
        com.example.examplemod.inventory.MatchInventoryService.prepareStack(player, original);
        Inventory inventory = player.getInventory();
        int quantityBefore = getQuantity();
        ItemStack automatic = original.copyWithCount(quantityBefore);
        com.example.examplemod.inventory.AutoAttachmentService.tryEquipPickedUp(player, automatic);
        int taken = quantityBefore - automatic.getCount();
        int attemptedCount = Math.min(quantityBefore - taken, physicalCapacity(inventory, original));
        if (attemptedCount == 0 && taken == 0) {
            player.displayClientMessage(Texts.of("message.tacz_pubg.ground_loot_entity.inventory_full_loot_remains_ground"), true);
            return InteractionResult.CONSUME;
        }
        int stackLimit = Math.min(original.getMaxStackSize(), inventory.getMaxStackSize(original));
        for (int batch : LootPileQuantities.batches(attemptedCount, stackLimit)) {
            long before = matchingCount(inventory, original);
            ItemStack remainder = original.copyWithCount(batch);
            inventory.add(remainder);
            // Creative Inventory.add may void overflow. Count only items actually stored.
            int stored = com.example.examplemod.inventory.InventoryConfig.active(player)
                    ? Math.clamp(batch - remainder.getCount(), 0, batch)
                    : (int) Math.clamp(matchingCount(inventory, original) - before, 0L, (long) batch);
            taken += stored;
            if (stored < batch) break;
        }
        if (taken <= 0) {
            player.displayClientMessage(Texts.of("message.tacz_pubg.ground_loot_entity.inventory_full_loot_remains_ground"), true);
            return InteractionResult.CONSUME;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        int remaining = quantityBefore - taken;
        com.example.examplemod.inventory.PubgInventoryService.acquired(player, original);
        entityData.set(QUANTITY, remaining);
        if (remaining == 0) {
            discard();
        }
        player.displayClientMessage(Texts.of("message.tacz_pubg.ground_loot_entity.picked_up_items", taken), true);
        return InteractionResult.CONSUME;
    }

    /**
     * Explicit F wears an item even when spare equipment is permitted. Automatic callers
     * may request upgrades only. Old equipment becomes a real drop with all components intact.
     */
    public boolean exchangeEquipment(Player player, boolean automatic) {
        if (level().isClientSide || !validAnchor || isRemoved() || !player.isAlive() || player.isSpectator()
                || player.level() != level() || getQuantity() < 1 || !canBePickedUpBy(player)) return false;
        var category = com.example.examplemod.inventory.EquipmentInventoryRules.category(getItem());
        if (category == null || !com.example.examplemod.inventory.EquipmentInventoryRules.mayReplace(player, getItem(), automatic))
            return false;
        ItemStack replacement = getItem().copyWithCount(1);
        ItemStack previous = category.equipped(player).copy();
        return commitExchange(player, replacement, previous, previous, stack -> category.equip(player, stack));
    }
    private static boolean weaponSlotsFull(Player player, ItemStack stack) {
        boolean handgun = com.example.examplemod.inventory.MatchInventoryService.role(stack).equals("handgun");
        if (handgun) return !player.getInventory().getItem(2).isEmpty();
        return !player.getInventory().getItem(0).isEmpty() && !player.getInventory().getItem(1).isEmpty();
    }
    /** A deliberate F press may replace a weapon; passive acquisition never replaces guns. */
    private boolean exchangeWeapon(Player player) {
        return exchangeWeapon(player, player.getInventory().selected);
    }

    /** Explicit inventory drag can select either real primary slot without changing the held gun. */
    public boolean exchangeWeapon(Player player, int target) {
        if (level().isClientSide || isRemoved() || !validAnchor || player.level() != level()
                || !canBePickedUpBy(player) || getQuantity() < 1
                || !com.example.examplemod.inventory.InventoryConfig.active(player)
                || !com.example.examplemod.inventory.MatchInventoryService.weapon(getItem())) return false;
        if (com.example.examplemod.inventory.AutoAttachmentService.busy(player)) return false;
        boolean handgun = com.example.examplemod.inventory.MatchInventoryService.role(getItem()).equals("handgun");
        if (handgun ? target != 2 : target != 0 && target != 1) return false;
        ItemStack previous = player.getInventory().getItem(target).copy();
        ItemStack rollback = previous.copy();
        ItemStack replacement = getItem().copyWithCount(1);
        int used = com.example.examplemod.backpack.BackpackService.used(player);
        int next = used - com.example.examplemod.backpack.BackpackWeights.weight(player, previous)
                + com.example.examplemod.backpack.BackpackWeights.weight(player, replacement);
        if (!com.example.examplemod.backpack.BackpackRules.permitsChange(used, next,
                com.example.examplemod.backpack.BackpackService.capacity(player))) return false;
        com.example.examplemod.inventory.AutoAttachmentService.transferCompatibleAttachments(player, previous, replacement);
        boolean changed = commitExchange(player, replacement, previous, rollback, stack -> player.getInventory().setItem(target, stack));
        if (changed) com.example.examplemod.inventory.AutoAttachmentService.onGunAcquired(player, target);
        return changed;
    }
    private boolean commitExchange(Player player, ItemStack replacement, ItemStack previous, ItemStack rollback,
            java.util.function.Consumer<ItemStack> equip) {
        int quantity = getQuantity();
        LootSpawnPlacement.Slot offset = previous.isEmpty() ? null : LootSpawnPlacement.replacementDrop(this, previous);
        GroundLootEntity displaced = null;
        if (!previous.isEmpty() && quantity > 1) {
            displaced = LootSpawnRegistration.GROUND_LOOT.get().create(level());
            if (displaced == null) return false;
            displaced.initializeDrop(previous, offset == null ? anchor : offset.floor(), 10, null);
            displaced.setPos(position());
            displaced.setYRot(getYRot());
            if (offset != null) displaced.placeReplacement(offset);
        }
        try {
            // Spawn cancellation leaves source and worn item completely untouched.
            if (displaced != null && !level().addFreshEntity(displaced)) { displaced.discard(); return false; }
            equip.accept(replacement);
        } catch (RuntimeException failure) {
            if (displaced != null) displaced.discard();
            equip.accept(rollback);
            ExampleMod.LOGGER.error("Equipment swap rejected; source and old equipment retained", failure);
            return false;
        }
        if (!previous.isEmpty() && quantity == 1) {
            // Reuse this carrier: there is no entity-spawn failure window for the usual one-item swap.
            byte physics = entityData.get(PHYSICS_STATE);
            initializeDrop(previous, offset == null ? anchor : offset.floor(), 0, null);
            if (offset == null) setPhysicsState(physics);
            else placeReplacement(offset);
        } else {
            entityData.set(QUANTITY, quantity - 1);
            if (quantity == 1) discard();
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        com.example.examplemod.inventory.PubgInventoryService.acquired(player, replacement);
        return true;
    }

    private void placeReplacement(LootSpawnPlacement.Slot slot) {
        setPos(slot.x(), slot.y(), slot.z());
        setDeltaMovement(Vec3.ZERO);
        setOnGround(true);
        setPhysicsState(RESTING);
        hasImpulse = true;
    }

    private static int physicalCapacity(Inventory inventory, ItemStack stack) {
        int maximum = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize(stack));
        int capacity = 0;
        for (ItemStack slot : inventory.items) {
            if (slot.isEmpty()) {
                capacity += maximum;
            } else if (slot.isStackable() && ItemStack.isSameItemSameComponents(slot, stack)) {
                capacity += Math.max(0, Math.min(maximum, slot.getMaxStackSize()) - slot.getCount());
            }
        }
        ItemStack offhand = inventory.offhand.getFirst();
        if (!offhand.isEmpty() && offhand.isStackable() && ItemStack.isSameItemSameComponents(offhand, stack)) {
            capacity += Math.max(0, Math.min(maximum, offhand.getMaxStackSize()) - offhand.getCount());
        }
        return capacity;
    }

    private static long matchingCount(Inventory inventory, ItemStack stack) {
        long count = 0;
        for (ItemStack slot : inventory.items) {
            if (ItemStack.isSameItemSameComponents(slot, stack)) count += slot.getCount();
        }
        for (ItemStack slot : inventory.offhand) {
            if (ItemStack.isSameItemSameComponents(slot, stack)) count += slot.getCount();
        }
        return count;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean skipAttackInteraction(Entity entity) {
        return true;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false;
    }
}
