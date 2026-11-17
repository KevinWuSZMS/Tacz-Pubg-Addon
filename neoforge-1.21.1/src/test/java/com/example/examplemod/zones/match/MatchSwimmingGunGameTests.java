package com.example.examplemod.zones.match;

import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MovementState.Mode;
import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.entity.shooter.LivingEntityAim;
import com.tacz.guns.entity.shooter.LivingEntityShoot;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
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
        NeoForge.EVENT_BUS.post(shoot);
        NeoForge.EVENT_BUS.post(fire);
        helper.assertTrue(shoot.isCanceled() == canceled && fire.isCanceled() == canceled,
                "Per-request/per-round event cancellation disagreed with swimming: " + canceled);
    }
}
