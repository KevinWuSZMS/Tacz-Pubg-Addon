package com.example.examplemod.throwables;

import com.example.examplemod.throwables.mixin.ProjectilePreviewAccessor;
import com.mojang.authlib.GameProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Compares against real LR ticks in a registered world, never ticks an explosive at detonation. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class ThrowableTrajectoryGameTests {
    @GameTest(template = "empty", templateNamespace = "tacz_pubg", timeoutTicks = 200)
    public static void trajectoryMatchesLrPhysicsWithoutWorldEffects(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "trajectory-test"));
        var base = helper.absolutePos(new BlockPos(0, 30, 0));
        var saved = new LinkedHashMap<BlockPos, BlockState>();
        var probe = new ThrowableTrajectoryProbe(level);
        try {
            helper.assertTrue(Projectile.class.getDeclaredField("leftOwner").getType() == boolean.class,
                    "Projectile leftOwner ABI changed");
            helper.assertTrue(Projectile.class.getDeclaredMethod("checkLeftOwner").getReturnType() == boolean.class,
                    "Projectile owner-check ABI changed");
            helper.assertTrue((Object) probe instanceof ProjectilePreviewAccessor,
                    "COMMON-side preview accessor was not applied to Projectile");
            // Each kind uses the same real shootFromRotation, including its unusual pitch offset
            // and getKnownMovement inheritance. A legacy launch is an independent comparison.
            for (boolean low : new boolean[]{false, true}) for (boolean grounded : new boolean[]{false, true}) {
                player.setPos(base.getX() + .5, base.getY(), base.getZ() + .5);
                player.setXRot(-25); player.setYRot(0);
                player.setShiftKeyDown(low); player.setOnGround(grounded);
                player.setDeltaMovement(.17, .23, -.11);
                for (ThrowableKind kind : ThrowableKind.values()) {
                    if (kind == ThrowableKind.C4) continue;
                    var real = actual(helper, player, kind);
                    var legacy = actual(helper, player, kind);
                    legacy.shootFromRotation(player, player.getXRot(), player.getYRot(), low ? 0 : -10,
                            low ? .55F : 1.3F, 0);
                    ThrowableBallistics.launch(real, player, false);
                    same(helper, real.getDeltaMovement(), legacy.getDeltaMovement(), "Shared launch changed existing velocity");
                    probe.reset(kind, player, kind.fuse, entity -> true);
                    for (int tick = 0; tick < 8; tick++) compareStep(helper, real, probe, kind + " open air " + tick);
                    real.discard(); legacy.discard();
                }
            }

            player.setPos(base.getX() + .5, base.getY(), base.getZ() + .5);
            player.setXRot(0); player.setYRot(0); player.setShiftKeyDown(false); player.setOnGround(false);
            player.setDeltaMovement(Vec3.ZERO);
            for (int y = 0; y < 5; y++) put(level, saved, base.offset(0, y, 2), Blocks.STONE.defaultBlockState());
            for (ThrowableKind kind : new ThrowableKind[]{ThrowableKind.FRAG, ThrowableKind.STUN, ThrowableKind.SMOKE,
                    ThrowableKind.STICKY, ThrowableKind.MOLOTOV, ThrowableKind.BLUE_ZONE}) {
                var real = actual(helper, player, kind);
                ThrowableBallistics.launch(real, player, false);
                probe.reset(kind, player, kind.fuse, entity -> true);
                compareStep(helper, real, probe, kind + " first wall impact");
                if (kind == ThrowableKind.STICKY || kind == ThrowableKind.MOLOTOV) {
                    helper.assertTrue(probe.end() == (kind == ThrowableKind.STICKY
                            ? ThrowableTrajectoryProbe.End.ATTACHED : ThrowableTrajectoryProbe.End.IMPACT),
                            "Impact did not terminate the appropriate prediction");
                } else {
                    helper.assertTrue(real.getDeltaMovement().z < 0, "Fixture did not exercise LR reflection");
                    if (kind == ThrowableKind.STUN)
                        helper.assertTrue(real.remainingFuse() == kind.fuse - 1 && probe.remainingFuse() == kind.fuse - 1,
                                "An LR stun impact must not shorten its timed fuse");
                    int steps = 4;
                    for (int tick = 0; tick < steps; tick++) compareStep(helper, real, probe, kind + " rebound " + tick);
                }
                real.discard();
            }
            helper.assertTrue(ThrowableBallistics.placement(player) != null, "C4 in-range wall was not found");
            player.setYRot(180);
            helper.assertTrue(ThrowableBallistics.placement(player) == null, "C4 showed an out-of-range/absent surface");
            player.setYRot(0);
            // Near-wall launch must stay on the eye's side; the original .25 offset could cross it.
            player.setPos(base.getX() + .5, base.getY(), base.getZ() + 1.92);
            var close = actual(helper, player, ThrowableKind.FRAG);
            ThrowableBallistics.launch(close, player, false);
            probe.reset(ThrowableKind.FRAG, player, 100, entity -> true);
            helper.assertTrue(close.getZ() < base.getZ() + 2, "Close wall launch spawned through the collision face");
            same(helper, close.position(), probe.position(), "Preview and actual near-wall origins differ");
            close.discard();
            restore(level, saved);

            // Use native old-bounding-box water/current pushing, not water at the future endpoint.
            player.setPos(base.getX() + .5, base.getY(), base.getZ() + .5);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                for (int y = 0; y <= 2; y++)
                    put(level, saved, base.offset(x, y, z), Blocks.WATER.defaultBlockState()
                            .setValue(LiquidBlock.LEVEL, x == 1 ? 4 : 0));
            var wet = actual(helper, player, ThrowableKind.FRAG);
            ThrowableBallistics.launch(wet, player, false);
            probe.reset(ThrowableKind.FRAG, player, 100, entity -> true);
            wet.setDeltaMovement(.03, -.01, .02); probe.setDeltaMovement(wet.getDeltaMovement());
            for (int tick = 0; tick < 4; tick++) compareStep(helper, wet, probe, "Native water/current step " + tick);
            wet.discard();
            restore(level, saved);

            // A prediction collision must not trigger a target block's projectile callback.
            BlockPos target = base.offset(0, 1, 2);
            put(level, saved, target, Blocks.TARGET.defaultBlockState());
            probe.reset(ThrowableKind.MOLOTOV, player, 1, entity -> true);
            var path = probe.predict();
            helper.assertTrue(path.end() == ThrowableTrajectoryProbe.End.IMPACT
                    && level.getBlockState(target).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER) == 0,
                    "Prediction changed a target block or missed its collision");
            restore(level, saved);

            // No hurt callback or real projectile is generated while observing a visible creature.
            var pig = EntityType.PIG.create(level);
            helper.assertTrue(pig != null, "Pig fixture unavailable");
            pig.setNoAi(true);
            pig.setPos(base.getX() + .5, base.getY() + 1.2, base.getZ() + 2);
            level.addFreshEntity(pig);
            try {
                float health = pig.getHealth();
                int entitiesBefore = level.getEntitiesOfClass(PubgThrowableEntity.class, player.getBoundingBox().inflate(20)).size();
                probe.reset(ThrowableKind.MOLOTOV, player, 1, entity -> entity == pig);
                helper.assertTrue(probe.predict().end() == ThrowableTrajectoryProbe.End.IMPACT,
                        "Visible entity collision did not stop a bottle");
                helper.assertTrue(pig.getHealth() == health && !pig.isOnFire(), "Prediction damaged or ignited a creature");
                helper.assertTrue(level.getEntitiesOfClass(PubgThrowableEntity.class, player.getBoundingBox().inflate(20)).size() == entitiesBefore,
                        "Prediction spawned a real projectile");
            } finally { pig.discard(); }

            probe.reset(ThrowableKind.FRAG, player, 1, entity -> false);
            var expired = probe.predict();
            helper.assertTrue(expired.end() == ThrowableTrajectoryProbe.End.FUSE && expired.points().size() == 1,
                    "A one-tick fuse moved before detonating");
            probe.reset(ThrowableKind.MOLOTOV, player, 1, entity -> false);
            probe.setScope(point -> false);
            helper.assertTrue(probe.predict().end() == ThrowableTrajectoryProbe.End.LIMIT,
                    "Map-exit removal became a fictitious endpoint");
            probe.setScope(point -> true);
            probe.reset(ThrowableKind.MOLOTOV, player, 1, entity -> false);
            probe.setDeltaMovement(1000, 0, 0);
            var bounded = probe.predict();
            helper.assertTrue(bounded.end() == ThrowableTrajectoryProbe.End.LIMIT
                    && bounded.points().size() <= ThrowableTrajectoryProbe.MAX_POINTS,
                    "Prediction failed to bound an extreme launch");
            helper.succeed();
        } finally {
            restore(level, saved);
            probe.discard(); player.discard();
        }
    }
    private static PubgThrowableEntity actual(GameTestHelper helper, FakePlayer owner, ThrowableKind kind) {
        var entity = new PubgThrowableEntity(ThrowableRegistration.THROWABLE.get(), helper.getLevel());
        entity.initialize(kind, owner, new ItemStack(ThrowableRegistration.ITEMS.get(kind).get()),
                Math.max(1, kind.fuse), null, 0);
        return entity;
    }
    private static void compareStep(GameTestHelper helper, PubgThrowableEntity real,
            ThrowableTrajectoryProbe probe, String label) {
        probe.advance();
        real.tick();
        same(helper, real.position(), probe.position(), label + " position");
        if (probe.end() != ThrowableTrajectoryProbe.End.IMPACT && probe.end() != ThrowableTrajectoryProbe.End.ATTACHED)
            same(helper, real.getDeltaMovement(), probe.getDeltaMovement(), label + " velocity");
        if (real.kind() != ThrowableKind.MOLOTOV)
            helper.assertTrue(real.remainingFuse() == probe.remainingFuse(), label + " fuse");
    }
    private static void same(GameTestHelper helper, Vec3 expected, Vec3 actual, String label) {
        helper.assertTrue(expected.distanceToSqr(actual) < 1e-14, label + ": expected " + expected + ", got " + actual);
    }
    private static void put(net.minecraft.server.level.ServerLevel level, Map<BlockPos, BlockState> saved,
            BlockPos at, BlockState state) {
        saved.putIfAbsent(at, level.getBlockState(at));
        level.setBlock(at, state, 2);
    }
    private static void restore(net.minecraft.server.level.ServerLevel level, Map<BlockPos, BlockState> saved) {
        saved.forEach((position, state) -> level.setBlock(position, state, 2));
        saved.clear();
    }
}
