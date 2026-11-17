package com.example.examplemod.zones.match;

import com.example.examplemod.combat.CombatService;
import com.example.examplemod.lean.LeanService;
import com.example.examplemod.movement.MatchSwimming;
import com.example.examplemod.movement.MatchSwimming.DepthBand;
import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MovementState.Mode;
import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.example.examplemod.zones.MapZoneModel;
import com.mojang.authlib.GameProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real fluid blocks and native fluid/pose updates, without a client or synthetic water flags. */
@GameTestHolder("movement_swimming")
@PrefixGameTestTemplate(false)
public final class MatchSwimmingGameTests {
    private static final AtomicLong MAP_IDS = new AtomicLong(9_600_000);

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void shallowWaterNeverSwimsAndDeepWaterForcesSwimming(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.pool(1);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.isInWater() && MatchSwimming.controls(f.player), "Shallow fixture did not contact match water");
            helper.assertTrue(!f.player.isSwimming() && MatchSwimming.wading(f.player) && !MatchSwimming.hasSwimmingDepth(f.player),
                    "One-block grounded water incorrectly forced swimming");
            for (Mode requested : new Mode[] { Mode.CROUCH, Mode.PRONE, Mode.SPRINT }) {
                MovementService.input(f.player, requested, true);
                f.player.samplePose();
                Mode expected = requested == Mode.CROUCH ? Mode.CROUCH : Mode.STAND;
                Pose expectedPose = requested == Mode.CROUCH ? Pose.CROUCHING : Pose.STANDING;
                helper.assertTrue(MovementService.mode(f.player) == expected && f.player.getPose() == expectedPose
                                && !f.player.isSprinting(),
                        "One-block water did not enforce crouch-only stance permission for " + requested);
            }
            helper.assertTrue(LeanService.eligible(f.player), "Shallow grounded water blocked leaning");
            f.player.setYRot(-90);
            f.player.zza = 1;
            helper.assertTrue(!MatchSwimming.canClimbOut(f.player), "A level shallow pool floor was mistaken for a bank");
            for (int z = -1; z <= 1; z++) f.put(f.origin.offset(1, 0, z), Blocks.STONE.defaultBlockState());
            helper.assertTrue(MatchSwimming.canClimbOut(f.player), "Walking toward a higher shallow-water bank did not allow climbing");
            f.player.zza = 0;
            helper.assertTrue(!MatchSwimming.canClimbOut(f.player), "Looking at a shallow bank without moving allowed hopping");
            f.player.zza = 1;
            for (int z = -1; z <= 1; z++) f.put(f.origin.offset(1, 2, z), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!MatchSwimming.canClimbOut(f.player), "A shallow bank without standing headroom allowed climbing");
            f.player.zza = 0;
            f.pool(1);
            f.put(f.origin.east(), Blocks.AIR.defaultBlockState());
            f.player.setPos(f.origin.getX() + 1.1, f.origin.getY(), f.origin.getZ() + .5);
            f.player.samplePose();
            helper.assertTrue(f.player.isInWater() && !MatchSwimming.wading(f.player)
                            && !MatchSwimming.swimming(f.player) && MovementService.eligible(f.player),
                    "Body-edge water contact kept the dry center column in shallow-water controls");
            f.pool(1);
            f.place(.2, false);
            f.player.setSprinting(true);
            f.player.samplePose();
            helper.assertTrue(!f.player.onGround() && !f.player.isSwimming() && !f.player.isSprinting()
                            && f.player.getPose() == Pose.STANDING && MatchSwimming.wading(f.player),
                    "Jumping into one-block water incorrectly started swimming or sprinting");

            f.pool(0);
            f.place(0, true);
            f.player.samplePose();
            MovementService.input(f.player, Mode.SPRINT, true);
            MovementService.clear(f.player);
            helper.assertTrue(!f.player.isSprinting(), "Releasing ordinary ground controls retained sprint");
            MovementService.input(f.player, Mode.SPRINT, true);
            f.pool(2);
            f.place(0, true);
            f.player.setSprinting(true);
            f.player.samplePose();
            helper.assertTrue(f.player.isSwimming() && f.player.isSprinting()
                            && MovementService.mode(f.player) == Mode.STAND && !MovementService.active(f.player),
                    "Entering deep water cleared the held sprint flag while relinquishing ground controls");
            f.player.setSprinting(false);
            f.player.samplePose();
            helper.assertTrue(f.player.onGround() && MatchSwimming.hasSwimmingDepth(f.player), "Deep fixture lost its grounded condition");
            double depth = 1 + helper.getLevel().getFluidState(f.origin.above()).getHeight(helper.getLevel(), f.origin.above());
            helper.assertTrue(MatchSwimming.depthAtLeast(f.player, depth)
                            && !MatchSwimming.depthAtLeast(f.player, depth + .0001),
                    "Exact water-depth equality or its upper boundary was classified incorrectly");
            helper.assertTrue(f.player.isSwimming() && f.player.getPose() == Pose.SWIMMING && !f.player.isSprinting(),
                    "Two-block water must swim while grounded without requesting sprint");
            helper.assertTrue(!MovementService.eligible(f.player) && !LeanService.eligible(f.player),
                    "Deep grounded swimming retained ground stance or leaning");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void waterDepthBandsPreserveLegalStancesAndCorrectCrossings(GameTestHelper helper) throws Exception {
        helper.assertTrue(MatchSwimming.depthBand(.5) == DepthBand.ANKLE
                        && MatchSwimming.depthBand(Math.nextUp(.5)) == DepthBand.KNEE
                        && MatchSwimming.depthBand(1) == DepthBand.KNEE
                        && MatchSwimming.depthBand(Math.nextUp(1.0)) == DepthBand.WAIST
                        && MatchSwimming.depthBand(1.5) == DepthBand.WAIST
                        && MatchSwimming.depthBand(Math.nextUp(1.5)) == DepthBand.DEEP,
                "A depth-band equality boundary was rounded into the next band");
        try (Fixture f = new Fixture(helper)) {
            f.pool(1);
            f.put(f.origin, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.place(.5, true);
            f.player.samplePose();
            helper.assertTrue(MatchSwimming.depthBand(f.player) == DepthBand.ANKLE,
                    "Shallow water above a bottom slab was not in the <=.5 band");
            for (Mode requested : new Mode[] { Mode.SPRINT, Mode.CROUCH, Mode.PRONE }) {
                MovementService.input(f.player, requested, true);
                f.player.setSprinting(requested == Mode.SPRINT);
                for (int tick = 0; tick < 3; tick++) f.player.samplePose();
                Pose expected = requested == Mode.PRONE ? Pose.SWIMMING
                        : requested == Mode.CROUCH ? Pose.CROUCHING : Pose.STANDING;
                helper.assertTrue(MovementService.mode(f.player) == requested && f.player.getPose() == expected
                                && f.player.isSprinting() == (requested == Mode.SPRINT)
                                && !f.player.isSwimming() && !SwimmingGunRestrictions.blocked(f.player),
                        "The <=.5 band lost a legal stance or misclassified shallow prone as swimming: " + requested);
                if (requested != Mode.SPRINT)
                    helper.assertTrue(LeanService.eligible(f.player), "Legal shallow-water stance blocked grounded leaning");
            }
            f.player.setOnGround(false);
            f.player.samplePose();
            helper.assertTrue(MovementService.mode(f.player) == Mode.PRONE && f.player.getPose() == Pose.SWIMMING
                            && !f.player.isSwimming() && !LeanService.eligible(f.player),
                    "Unsupported shallow-water prone lost its stance or incorrectly allowed leaning");

            // Keep the old PRONE metadata: only the water changes, with no new stance packet.
            f.pool(1);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(MatchSwimming.depthBand(f.player) == DepthBand.KNEE
                            && MovementService.mode(f.player) == Mode.STAND && f.player.getPose() == Pose.STANDING,
                    "Crossing .5 did not automatically return an existing prone stance to STAND");
            MovementService.input(f.player, Mode.CROUCH, true);
            f.player.samplePose();
            helper.assertTrue(f.player.getPose() == Pose.CROUCHING && MovementService.mode(f.player) == Mode.CROUCH,
                    "The (.5,1] band rejected crouching");
            f.pool(2);
            f.put(f.origin, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.place(.5, true);
            f.player.samplePose();
            helper.assertTrue(MatchSwimming.depthBand(f.player) == DepthBand.WAIST
                            && MovementService.mode(f.player) == Mode.STAND && f.player.getPose() == Pose.STANDING,
                    "Crossing 1 did not automatically return an existing crouch stance to STAND");
            for (Mode requested : new Mode[] { Mode.SPRINT, Mode.CROUCH, Mode.PRONE }) {
                MovementService.input(f.player, requested, true);
                f.player.samplePose();
                helper.assertTrue(MovementService.mode(f.player) == Mode.STAND && f.player.getPose() == Pose.STANDING
                                && !f.player.isSwimming() && !SwimmingGunRestrictions.blocked(f.player),
                        "The (1,1.5] band did not use STAND for " + requested);
            }
            MovementService.input(f.player, Mode.WALK, true);
            helper.assertTrue(MovementService.mode(f.player) == Mode.WALK, "Explicit manual walking was disabled");

            // Exact boundaries are made from real waterlogged collision intervals.
            // A small player fits below the slab ceiling; scale must not change the band.
            MovementService.clear(f.player);
            f.player.getAttribute(Attributes.SCALE).setBaseValue(.2);
            f.player.refreshDimensions();
            f.pool(1);
            f.put(f.origin, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(MatchSwimming.depthBand(f.player) == DepthBand.ANKLE
                            && MatchSwimming.depthAtLeast(f.player, .5)
                            && !MatchSwimming.depthAtLeast(f.player, .5001),
                    "Exactly .5 blocks of real water did not remain in the unrestricted band");
            f.pool(2);
            f.put(f.origin, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.put(f.origin.above(), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.place(.5, true);
            f.player.samplePose();
            helper.assertTrue(MatchSwimming.depthBand(f.player) == DepthBand.KNEE
                            && MatchSwimming.depthAtLeast(f.player, 1)
                            && !MatchSwimming.depthAtLeast(f.player, 1.0001),
                    "Exactly 1 block of real water did not retain crouching permission");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void sameDeepColumnSurfaceDoesNotOscillateAndDryLandClearsSwimming(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.pool(2);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.isSwimming(), "Deep entry did not start swimming");
            f.place(1.4, false);
            for (int sample = 0; sample < 40; sample++) {
                // Attempt the upward displacement after liquid jumping/look thrust,
                // exercising the shared Entity.move surface limiter used by real players.
                f.player.setDeltaMovement(0, .2, 0);
                f.player.move(MoverType.SELF, f.player.getDeltaMovement());
                f.player.samplePose();
                helper.assertTrue(MatchSwimming.hasSwimmingDepth(f.player) && f.player.isSwimming()
                                && f.player.getPose() == Pose.SWIMMING,
                        "Holding upward swimming oscillated at the surface at sample " + sample);
            }
            double surfaceHeight = 1 + f.level.getFluidState(f.origin.above()).getHeight(f.level, f.origin.above());
            double surfaceY = f.origin.getY() + surfaceHeight;
            helper.assertTrue(f.player.getY() < surfaceY && f.player.getEyeY() > surfaceY
                            && !MatchSwimming.canClimbOut(f.player),
                    "Open-water rise did not retain an immersed body with breathing room");

            f.place(surfaceHeight + .1, false);
            for (int sample = 0; sample < 4; sample++) f.player.samplePose();
            helper.assertTrue(!f.player.isInWater() && MatchSwimming.swimming(f.player)
                            && !MatchSwimming.wading(f.player) && f.player.getPose() == Pose.SWIMMING,
                    "A brief native fluid-contact gap discarded an existing surface swimmer");
            f.place(surfaceHeight + .5, false);
            f.player.samplePose();
            helper.assertTrue(!MatchSwimming.controls(f.player) && !f.player.isSwimming(),
                    "Swimming ownership followed the player too far above the water");

            f.place(1.4, false);
            f.player.samplePose();
            for (int z = -1; z <= 1; z++) for (int y = 0; y < 2; y++)
                f.put(f.origin.offset(1, y, z), Blocks.STONE.defaultBlockState());
            f.player.setYRot(-90);
            helper.assertTrue(!MatchSwimming.canClimbOut(f.player)
                            && MatchSwimming.limitSurfaceRise(f.player, new Vec3(0, .3, 0)).y < .3,
                    "Looking at a bank without horizontal motion bypassed the surface limit");
            for (int sample = 0; sample < 8; sample++) {
                f.player.setDeltaMovement(0, .2, 0);
                f.player.move(MoverType.SELF, f.player.getDeltaMovement());
                f.player.samplePose();
                helper.assertTrue(MatchSwimming.swimming(f.player) && f.player.getY() < surfaceY,
                        "Stationary upward swimming next to a bank left the water at sample " + sample);
            }
            f.player.zza = 1;
            helper.assertTrue(MatchSwimming.canClimbOut(f.player)
                            && MatchSwimming.limitSurfaceRise(f.player, new Vec3(.08, .3, 0)).y == .3,
                    "A reachable dry bank was blocked by the open-water rise limit");
            f.player.setPos(f.origin.getX() + 1.5, f.origin.getY() + 2, f.origin.getZ() + .5);
            f.player.zza = 0;
            f.player.setOnGround(true);
            f.player.setDeltaMovement(Vec3.ZERO);
            f.player.samplePose();
            f.player.samplePose();
            helper.assertTrue(!f.player.isInWater() && !MatchSwimming.controls(f.player) && !f.player.isSwimming()
                            && f.player.getPose() == Pose.STANDING,
                    "Leaving water retained forced swimming or a stale swimming pose");
            MovementService.input(f.player, Mode.PRONE, true);
            f.player.samplePose();
            helper.assertTrue(f.player.getPose() == Pose.SWIMMING && LeanService.eligible(f.player),
                    "Water-specific lean restriction disabled ordinary land prone leaning");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void fixedThresholdAndWaterloggedSupportDetermineUsableDepth(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.pool(2);
            f.player.getAttribute(Attributes.SCALE).setBaseValue(2);
            f.player.refreshDimensions();
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.getDimensions(Pose.STANDING).height() > 3
                            && MatchSwimming.hasSwimmingDepth(f.player) && f.player.isSwimming(),
                    "Enlarging the body changed the fixed deep-water threshold");
            f.player.getAttribute(Attributes.SCALE).setBaseValue(.4);
            f.player.refreshDimensions();
            f.pool(1);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.getDimensions(Pose.STANDING).height() < 1
                            && !MatchSwimming.hasSwimmingDepth(f.player) && !f.player.isSwimming(),
                    "Shrinking the body changed the fixed shallow-water threshold");

            f.player.getAttribute(Attributes.SCALE).setBaseValue(1);
            f.player.refreshDimensions();
            f.pool(2);
            f.put(f.origin, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.place(.5, true);
            f.player.samplePose();
            helper.assertTrue(f.player.isInWater() && !MatchSwimming.hasSwimmingDepth(f.player) && !f.player.isSwimming(),
                    "Waterlogged bottom slab counted solid support as swimmable depth");
            helper.assertTrue(MovementService.eligible(f.player) && LeanService.eligible(f.player),
                    "Waterlogged shallow support blocked ground actions");

            f.pool(2);
            f.put(f.origin.above(), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(MatchSwimming.depthAtLeast(f.player, 1.5)
                            && !MatchSwimming.depthAtLeast(f.player, 1.5001)
                            && MatchSwimming.depthBand(f.player) == DepthBand.WAIST
                            && !MatchSwimming.hasSwimmingDepth(f.player) && !f.player.isSwimming(),
                    "Exactly 1.5 blocks of traversable water must remain below the swimming threshold");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "movement_swimming")
    public static void outsideMatchFlyingAndDownedKeepTheirOwnMovementRules(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.pool(2);
            f.place(0, true);
            MatchSwimming.syncParticipation(f.player);
            helper.assertTrue(((com.example.examplemod.movement.MovementStateAccess) (Object) f.player).taczPubg$matchParticipant(),
                    "Match participation was not available for remote-player metadata");
            f.player.getAbilities().flying = true;
            f.player.samplePose();
            helper.assertTrue(!MatchSwimming.controls(f.player) && !f.player.isSwimming(), "Creative flight was forced into swimming");
            f.player.getAbilities().flying = false;
            f.setDowned(true);
            f.player.samplePose();
            helper.assertTrue(CombatService.downed(f.player) && !MatchSwimming.controls(f.player)
                            && !MatchSwimming.swimming(f.player), "Downed state was overridden by swimming");
            f.setDowned(false);
            f.leaveMatch();
            MatchSwimming.syncParticipation(f.player);
            helper.assertTrue(!((com.example.examplemod.movement.MovementStateAccess) (Object) f.player).taczPubg$matchParticipant(),
                    "Ending participation left remote-player swimming ownership enabled");
            f.player.setSwimming(false);
            f.player.setSprinting(false);
            f.player.samplePose();
            f.player.samplePose();
            helper.assertTrue(!MatchSwimming.inMatch(f.player) && !MatchSwimming.controls(f.player) && !f.player.isSwimming(),
                    "Outside-match water gained automatic swimming");
            f.player.setSprinting(true);
            f.player.samplePose();
            helper.assertTrue(f.player.isSwimming(), "Outside-match vanilla sprint swimming stopped working");
        }
        helper.succeed();
    }

    static final class PosePlayer extends FakePlayer {
        PosePlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "[swim-fixture]")); }
        void samplePose() {
            // FakePlayer.tick is empty. Run the actual fluid update owner (which calls
            // updateSwimming) and Player's native pose update without synthetic fluid flags.
            baseTick();
            updatePlayerPose();
        }
    }

    static final class Fixture implements AutoCloseable {
        final PosePlayer player;
        final ServerLevel level;
        final BlockPos origin;
        final MatchRuntimeState state;
        final long mapId = MAP_IDS.getAndIncrement();
        final UUID session;
        final Map<BlockPos, BlockState> saved = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) throws Exception {
            level = helper.getLevel();
            origin = helper.absolutePos(new BlockPos(2, 8, 2));
            player = new PosePlayer(level);
            player.setNoGravity(true);
            var field = MatchService.class.getDeclaredField("STATES");
            field.setAccessible(true);
            var states = (Map<MinecraftServer, MatchRuntimeState>) field.get(null);
            state = states.computeIfAbsent(level.getServer(), ignored -> new MatchRuntimeState());
            var map = new MapZoneModel.MapDefinition(mapId, "swimming fixture", level.dimension().location(),
                    origin.getX() - 50, origin.getZ() - 50, origin.getX() + 50, origin.getZ() + 50,
                    new MapZoneModel.Circle(origin.getX(), origin.getZ(), 40), 0, List.of());
            var defaults = MatchConfigModel.defaults(mapId);
            var settings = new MatchConfigModel.Settings(mapId, "swimming fixture", 1, 20, 1, 0, 2400, 60,
                    false, false, false, false, defaults.phases(), defaults.bombingRules(), defaults.flowRules(),
                    false, false, new MatchConfigModel.FlightRules(false, true, 40, 512, 3, 52), 1);
            session = state.start(map, settings,
                    List.of(new MatchEngine.Participant(player.getUUID(), "swimming fixture", "solo")), 19).sessionId();
        }

        void pool(int depth) {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                put(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 4; y++) put(origin.offset(x, y, z),
                        y < depth ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }

        void place(double height, boolean grounded) {
            player.setPos(origin.getX() + .5, origin.getY() + height, origin.getZ() + .5);
            player.setDeltaMovement(Vec3.ZERO);
            player.setOnGround(grounded);
            player.setSprinting(false);
        }

        void put(BlockPos position, BlockState block) {
            saved.putIfAbsent(position, level.getBlockState(position));
            level.setBlock(position, block, 2);
        }

        @SuppressWarnings("unchecked")
        void setDowned(boolean value) throws Exception {
            var method = CombatService.class.getDeclaredMethod("game", ServerPlayer.class, boolean.class);
            method.setAccessible(true);
            Object game = method.invoke(null, player, true);
            var playersField = game.getClass().getDeclaredField("players");
            playersField.setAccessible(true);
            Object combatPlayer = ((Map<UUID, ?>) playersField.get(game)).get(player.getUUID());
            var downField = combatPlayer.getClass().getDeclaredField("down");
            downField.setAccessible(true);
            var constructor = downField.getType().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            downField.set(combatPlayer, value ? constructor.newInstance(null, 0L, 100F, player.position()) : null);
        }

        void leaveMatch() {
            if (state.session(mapId) != null) state.end(mapId);
            CombatService.ended(level.getServer(), session);
            state.forgetDeletedMap(mapId);
        }

        @Override public void close() {
            MovementService.clear(player);
            leaveMatch();
            saved.forEach((position, block) -> level.setBlock(position, block, 2));
            player.discard();
        }
    }
}
