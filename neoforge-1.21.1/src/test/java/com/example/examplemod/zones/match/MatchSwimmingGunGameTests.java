package com.example.examplemod.zones.match;

import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MovementState.Mode;
import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.example.examplemod.throwables.PubgThrowableEntity;
import com.example.examplemod.throwables.ThrowableKind;
import com.example.examplemod.throwables.ThrowableRegistration;
import com.example.examplemod.throwables.ThrowableService;
import com.example.examplemod.throwables.ThrowableSettingsService;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.entity.shooter.LivingEntityAim;
import com.tacz.guns.entity.shooter.LivingEntityMelee;
import com.tacz.guns.entity.shooter.LivingEntityShoot;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import me.xjqsh.lrtactical.api.item.IMeleeWeapon;
import me.xjqsh.lrtactical.api.melee.MeleeAction;
import me.xjqsh.lrtactical.capability.CombatProperties;
import me.xjqsh.lrtactical.entity.ThrowableItemEntity;
import me.xjqsh.lrtactical.init.ModItems;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Calls TaCZ's actual request and aim owners, with production mixins applied. */
@GameTestHolder("movement_swimming")
@PrefixGameTestTemplate(false)
public final class MatchSwimmingGunGameTests {
    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void swimmingRejectsRequestsAimAndQueuedRoundsWithoutBlockingShallowOrLand(GameTestHelper helper) throws Exception {
        try (var f = new MatchSwimmingGameTests.Fixture(helper)) {
            var data = new ShooterDataHolder();
            var shoot = new LivingEntityShoot(f.player, data, null);
            var aim = new LivingEntityAim(f.player, data);
            var rounds = new AtomicInteger();
            var queued = SwimmingGunRestrictions.guardRound(f.player, () -> { rounds.incrementAndGet(); return true; });

            f.pool(1);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(!SwimmingGunRestrictions.blocked(f.player)
                            && shoot.shoot(() -> 0F, () -> 0F, 0) == ShootResult.NOT_DRAW,
                    "Shallow standing water intercepted TaCZ's native shooting path");
            aim.aim(true);
            helper.assertTrue(data.isAiming && queued.getAsBoolean() && rounds.get() == 1,
                    "Shallow standing water blocked aiming or a scheduled round");
            assertEvents(helper, f, false);

            f.pool(2);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(SwimmingGunRestrictions.blocked(f.player), "Deep water did not activate the gun restriction");
            data.currentGunItem = () -> { throw new AssertionError("Swimming request reached held-gun/ammunition work"); };
            data.chargeProgress = 1;
            helper.assertTrue(shoot.shoot(() -> 0F, () -> 0F, 0) == ShootResult.FORGE_EVENT_CANCEL
                            && shoot.shoot(() -> 0F, () -> 0F, 0, 1F) == ShootResult.FORGE_EVENT_CANCEL
                            && data.chargeProgress == 0,
                    "Normal or charged swimming request reached native gun work");
            aim.aim(true);
            helper.assertTrue(!data.isAiming, "Swimming accepted a new aiming request");
            data.isAiming = true;
            data.aimingProgress = 1;
            data.chargeProgress = 1;
            aim.tickAimingProgress();
            helper.assertTrue(!data.isAiming && data.aimingProgress == 0 && data.chargeProgress == 0,
                    "Entering water retained previous aiming or charge state");
            helper.assertTrue(!queued.getAsBoolean() && rounds.get() == 1,
                    "A round queued before swimming ran its native task instead of ending the burst");
            assertEvents(helper, f, true);

            double surface = 1 + f.level.getFluidState(f.origin.above()).getHeight(f.level, f.origin.above());
            f.place(surface + .1, false);
            f.player.samplePose();
            helper.assertTrue(!f.player.isInWater() && f.player.isSwimming()
                            && SwimmingGunRestrictions.blocked(f.player)
                            && shoot.shoot(() -> 0F, () -> 0F, 0, 1F) == ShootResult.FORGE_EVENT_CANCEL,
                    "Briefly clearing the deep-water surface allowed a charged shot");

            data.currentGunItem = null;
            f.pool(0);
            f.place(0, true);
            f.player.samplePose();
            f.player.samplePose();
            MovementService.input(f.player, Mode.PRONE, true);
            f.player.samplePose();
            helper.assertTrue(f.player.getPose() == Pose.SWIMMING && !SwimmingGunRestrictions.blocked(f.player)
                            && shoot.shoot(() -> 0F, () -> 0F, 0) == ShootResult.NOT_DRAW,
                    "Land prone was mistaken for swimming by the shooting gate");
            aim.aim(true);
            helper.assertTrue(data.isAiming, "Land prone could not aim");
            assertEvents(helper, f, false);

            MovementService.clear(f.player);
            f.leaveMatch();
            f.pool(2);
            f.place(0, true);
            f.player.setSprinting(true);
            f.player.samplePose();
            helper.assertTrue(f.player.isSwimming() && !SwimmingGunRestrictions.blocked(f.player)
                            && shoot.shoot(() -> 0F, () -> 0F, 0) == ShootResult.NOT_DRAW,
                    "The match restriction changed outside-match vanilla swimming guns");
            assertEvents(helper, f, false);
        }
        helper.succeed();
    }

    private static void assertEvents(GameTestHelper helper, MatchSwimmingGameTests.Fixture f, boolean canceled) {
        var shoot = new GunShootEvent(f.player, ItemStack.EMPTY, LogicalSide.SERVER);
        var fire = new GunFireEvent(f.player, ItemStack.EMPTY, LogicalSide.SERVER);
        var melee = new GunMeleeEvent(f.player, ItemStack.EMPTY, LogicalSide.SERVER);
        NeoForge.EVENT_BUS.post(shoot);
        NeoForge.EVENT_BUS.post(fire);
        NeoForge.EVENT_BUS.post(melee);
        helper.assertTrue(shoot.isCanceled() == canceled && fire.isCanceled() == canceled && melee.isCanceled() == canceled,
                "Per-request/per-round event cancellation disagreed with swimming: " + canceled);
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void deepWaterRejectsMeleeAndCancelsPendingNativeAttacks(GameTestHelper helper) throws Exception {
        try (var f = new MatchSwimmingGameTests.Fixture(helper)) {
            var target = EntityType.PIG.create(f.level);
            try {
                f.pool(1); f.place(0, true); f.player.samplePose();
                target.setPos(f.player.getX() + 1, f.player.getY(), f.player.getZ());
                float health = target.getHealth();
                helper.assertTrue(target.hurt(f.player.damageSources().playerAttack(f.player), 1)
                                && target.getHealth() < health, "Shallow water blocked ordinary melee damage");
                var data = new ShooterDataHolder();
                var gunMelee = new LivingEntityMelee(f.player, data, null);
                data.meleePrepTickCount = 2;
                gunMelee.scheduleTickMelee();
                helper.assertTrue(data.meleePrepTickCount == 1, "Shallow water cancelled TaCZ melee preparation");

                var blade = new ItemStack(ModItems.MELEE.get());
                ((IMeleeWeapon) blade.getItem()).setId(blade, ResourceLocation.parse("lrtactical:karambit"));
                f.player.setItemInHand(InteractionHand.MAIN_HAND, blade);
                var combat = new CombatProperties(f.player);
                combat.tick(); combat.resetMeleeSync();
                helper.assertTrue(combat.preAttack(MeleeAction.LEFT, 0, f.player.getEyePosition(), f.player.getLookAngle()),
                        "Native LR shallow-water melee fixture could not prepare");

                f.pool(2); f.place(0, true); f.player.samplePose();
                health = target.getHealth(); target.invulnerableTime = 0;
                f.player.attack(target);
                helper.assertTrue(target.getHealth() == health
                                && !target.hurt(f.player.damageSources().playerAttack(f.player), 1),
                        "Deep swimming still dealt vanilla or native direct melee damage");
                data.currentGunItem = () -> { throw new AssertionError("Deep gun melee reached held-weapon work"); };
                gunMelee.melee(); gunMelee.scheduleTickMelee();
                helper.assertTrue(data.meleePrepTickCount == -1, "Deep entry retained a pending TaCZ melee hit");
                combat.postAttack(MeleeAction.LEFT, 0, java.util.List.of(target));
                helper.assertTrue(combat.getActionCount(MeleeAction.LEFT) == 0 && target.getHealth() == health,
                        "Deep entry retained the prepared LR attack or dealt its hit");
                helper.assertTrue(!combat.preAttack(MeleeAction.LEFT, 0, Vec3.ZERO, Vec3.ZERO)
                                && !combat.preAttack(MeleeAction.RIGHT, 0, Vec3.ZERO, Vec3.ZERO),
                        "Deep water accepted an LR light or heavy attack");

                f.pool(1); f.place(0, true); f.player.samplePose();
                helper.assertTrue(combat.preAttack(MeleeAction.LEFT, 0, f.player.getEyePosition(), f.player.getLookAngle()),
                        "Leaving deep water did not restore new LR attacks");
                f.pool(2); f.place(0, true); f.player.samplePose();
                combat.tick();
                helper.assertTrue(combat.getActionCount(MeleeAction.LEFT) == 0,
                        "Native LR tick did not invalidate the pending attack at water entry");
                f.pool(1); f.place(0, true); f.player.samplePose();
                combat.postAttack(MeleeAction.LEFT, 0, java.util.List.of(target));
                helper.assertTrue(target.getHealth() == health, "Leaving water revived the old LR pending hit");
            } finally { target.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void deepWaterCancelsUnpinnedThrowablesButDoesNotGateMedicineItems(GameTestHelper helper) throws Exception {
        try (var f = new MatchSwimmingGameTests.Fixture(helper)) {
            var item = ThrowableRegistration.ITEMS.get(ThrowableKind.FRAG).get();
            var stack = new ItemStack(item, 2);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            try {
                f.pool(1); f.place(0, true); f.player.samplePose();
                helper.assertTrue(item.use(f.level, f.player, InteractionHand.MAIN_HAND).getResult().consumesAction(),
                        "Shallow water could not begin a grenade preparation");
                advanceCombatClock(f, ThrowableSettingsService.profile(ThrowableKind.FRAG).prepareTicks());
                f.pool(2); f.place(0, true); f.player.samplePose();
                ThrowableService.cook(f.player);
                ThrowableService.release(f.player, false);
                helper.assertTrue(!ThrowableService.cooking(f.player) && stack.getCount() == 2
                                && projectiles(f).isEmpty(), "Unpinned preparation in deep water consumed or launched a grenade");
                f.player.stopUsingItem();
                helper.assertTrue(item.use(f.level, f.player, InteractionHand.MAIN_HAND).getResult() == InteractionResult.FAIL,
                        "Deep water accepted a new PUBG grenade use");
                var nativeStack = me.xjqsh.lrtactical.resource.CommonAssetsManager.get()
                        .getThrowableIndex(ResourceLocation.parse("lrtactical:m67")).createItemStack();
                f.player.setItemInHand(InteractionHand.MAIN_HAND, nativeStack);
                helper.assertTrue(ModItems.THROWABLE.get().use(f.level, f.player, InteractionHand.MAIN_HAND).getResult() == InteractionResult.FAIL,
                        "Deep water accepted a new native LR grenade use");
                var medicine = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("tacz_pubg:bandage"));
                helper.assertTrue(medicine != Items.AIR, "Registered bandage fixture is missing");
                for (var allowed : java.util.List.of(new ItemStack(medicine), new ItemStack(Items.APPLE))) {
                    f.player.setItemInHand(InteractionHand.MAIN_HAND, allowed);
                    var event = new PlayerInteractEvent.RightClickItem(f.player, InteractionHand.MAIN_HAND);
                    NeoForge.EVENT_BUS.post(event);
                    helper.assertTrue(!event.isCanceled() && !SwimmingGunRestrictions.throwable(allowed),
                            "The combat gate cancelled a medicine or noncombat item interaction");
                }
            } finally { ThrowableService.cancel(f.player); f.player.stopUsingItem(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void cookedPubgGrenadeDropsOnceInDeepWaterWithItsRemainingFuse(GameTestHelper helper) throws Exception {
        try (var f = new MatchSwimmingGameTests.Fixture(helper)) {
            var item = ThrowableRegistration.ITEMS.get(ThrowableKind.FRAG).get();
            var stack = new ItemStack(item, 2);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            try {
                f.pool(1); f.place(0, true); f.player.samplePose();
                helper.assertTrue(item.use(f.level, f.player, InteractionHand.MAIN_HAND).getResult().consumesAction(), "Could not begin cooked fixture");
                var profile = ThrowableSettingsService.profile(ThrowableKind.FRAG);
                advanceCombatClock(f, profile.prepareTicks());
                ThrowableService.cook(f.player);
                helper.assertTrue(ThrowableService.cooking(f.player) && stack.getCount() == 1, "Cooking did not escrow exactly one grenade");
                advanceCombatClock(f, 7);
                f.pool(2); f.place(0, true); f.player.samplePose();
                ThrowableService.throwCooked(f.player);
                ThrowableService.throwCooked(f.player);
                ThrowableService.cancel(f.player);
                var live = projectiles(f);
                helper.assertTrue(live.size() == 1 && stack.getCount() == 1 && !ThrowableService.cooking(f.player),
                        "Deep entry lost or duplicated a paid grenade");
                var grenade = (PubgThrowableEntity) live.getFirst();
                helper.assertTrue(grenade.remainingFuse() == Math.max(1, profile.cookedFuseTicks() - 7)
                                && grenade.getDeltaMovement().x == 0 && grenade.getDeltaMovement().z == 0
                                && grenade.getDeltaMovement().y < 0,
                        "Cooked PUBG grenade was thrown horizontally or lost its elapsed fuse");
            } finally {
                ThrowableService.cancel(f.player);
                projectiles(f).forEach(net.minecraft.world.entity.Entity::discard);
                f.player.stopUsingItem();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void nativeLrCookedGrenadeDropsAtDeepEntryAndShallowReleaseStillThrows(GameTestHelper helper) throws Exception {
        for (boolean deep : new boolean[]{false, true}) try (var f = new MatchSwimmingGameTests.Fixture(helper)) {
            var index = me.xjqsh.lrtactical.resource.CommonAssetsManager.get().getThrowableIndex(ResourceLocation.parse("lrtactical:m67"));
            helper.assertTrue(index != null && index.getData().isCookable(), "Cookable native M67 fixture is missing");
            var item = ModItems.THROWABLE.get();
            ItemStack stack = index.createItemStack(); stack.setCount(2);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            try {
                f.pool(1); f.place(0, true); f.player.samplePose();
                item.use(f.level, f.player, InteractionHand.MAIN_HAND);
                int cooked = 7;
                var remaining = LivingEntity.class.getDeclaredField("useItemRemaining"); remaining.setAccessible(true);
                remaining.setInt(f.player, stack.getUseDuration(f.player) - index.getData().getPrepareTime() - cooked);
                if (deep) { f.pool(2); f.place(0, true); f.player.samplePose(); }
                if (deep) item.onUseTick(f.level, f.player, stack, f.player.getUseItemRemainingTicks());
                else { item.releaseUsing(stack, f.level, f.player, f.player.getUseItemRemainingTicks()); f.player.stopUsingItem(); }
                if (deep) item.releaseUsing(stack, f.level, f.player, f.player.getUseItemRemainingTicks());
                var live = projectiles(f);
                helper.assertTrue(live.size() == 1 && stack.getCount() == 1 && !f.player.isUsingItem(),
                        "Native LR water transition lost/duplicated supply or retained active use");
                var grenade = live.getFirst();
                helper.assertTrue(grenade.getLife() == Math.max(0, index.getData().getEntityData().getLifeTime() - cooked),
                        "Native LR water transition reset the remaining cooked fuse");
                double horizontal = grenade.getDeltaMovement().horizontalDistanceSqr();
                helper.assertTrue(deep ? horizontal == 0 && grenade.getDeltaMovement().y < 0 : horizontal > 0,
                        "Native LR deep drop or shallow normal throw had incorrect launch velocity");
            } finally { projectiles(f).forEach(net.minecraft.world.entity.Entity::discard); f.player.stopUsingItem(); }
        }
        helper.succeed();
    }

    private static java.util.List<ThrowableItemEntity> projectiles(MatchSwimmingGameTests.Fixture f) {
        return f.level.getEntitiesOfClass(ThrowableItemEntity.class, f.player.getBoundingBox().inflate(8), entity -> entity.getOwner() == f.player);
    }

    private static void advanceCombatClock(MatchSwimmingGameTests.Fixture f, long ticks) throws Exception {
        var game = MatchService.participantGame(f.player.server, f.player.getUUID());
        var field = MatchEngine.class.getDeclaredField("ticks"); field.setAccessible(true);
        field.setLong(game, field.getLong(game) + ticks);
    }
}
