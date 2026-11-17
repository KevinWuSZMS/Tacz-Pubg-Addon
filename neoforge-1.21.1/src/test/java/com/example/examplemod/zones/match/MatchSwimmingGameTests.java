package com.example.examplemod.zones.match;

import com.example.examplemod.combat.CombatService;
import com.example.examplemod.lean.LeanService;
import com.example.examplemod.movement.MatchSwimming;
import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MovementState.Mode;
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
    public static void shallowGroundAllowsCrouchLeanAndDeepGroundForcesSwimming(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.pool(1);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.isInWater() && MatchSwimming.controls(f.player), "Shallow fixture did not contact match water");
            helper.assertTrue(!f.player.isSwimming() && !MatchSwimming.hasStandingDepth(f.player),
                    "One-block grounded water incorrectly forced swimming");
            MovementService.input(f.player, Mode.CROUCH, true);
            f.player.samplePose();
            helper.assertTrue(MovementService.active(f.player) && f.player.getPose() == Pose.CROUCHING,
                    "Shallow grounded water blocked the match crouch pose");
            helper.assertTrue(LeanService.eligible(f.player), "Shallow grounded water blocked leaning");
            f.player.setSprinting(true);
            f.player.samplePose();
            helper.assertTrue(!f.player.isSwimming(), "Sprinting in shallow grounded water bypassed the depth rule");

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
            helper.assertTrue(f.player.onGround() && MatchSwimming.hasStandingDepth(f.player), "Deep fixture lost its grounded condition");
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
    public static void sameDeepColumnSurfaceDoesNotOscillateAndDryLandClearsSwimming(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.pool(2);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.isSwimming(), "Deep entry did not start swimming");
            f.place(1.4, false);
            for (int sample = 0; sample < 8; sample++) {
                f.player.samplePose();
                helper.assertTrue(MatchSwimming.hasStandingDepth(f.player) && f.player.isSwimming()
                                && f.player.getPose() == Pose.SWIMMING,
                        "Swimming pose/surface height shortened the same deep column at sample " + sample);
            }
            f.pool(0);
            f.place(0, true);
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
    public static void standingScaleAndWaterloggedSupportDetermineUsableDepth(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.pool(2);
            f.player.getAttribute(Attributes.SCALE).setBaseValue(2);
            f.player.refreshDimensions();
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.getDimensions(Pose.STANDING).height() > 3
                            && !MatchSwimming.hasStandingDepth(f.player) && !f.player.isSwimming(),
                    "Swimming depth ignored the enlarged standing body");
            f.player.getAttribute(Attributes.SCALE).setBaseValue(.4);
            f.player.refreshDimensions();
            f.pool(1);
            f.place(0, true);
            f.player.samplePose();
            helper.assertTrue(f.player.getDimensions(Pose.STANDING).height() < 1
                            && MatchSwimming.hasStandingDepth(f.player) && f.player.isSwimming(),
                    "Swimming depth ignored the smaller standing body");

            f.player.getAttribute(Attributes.SCALE).setBaseValue(1);
            f.player.refreshDimensions();
            f.pool(2);
            f.put(f.origin, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                    .setValue(SlabBlock.WATERLOGGED, true));
            f.place(.5, true);
            f.player.samplePose();
            helper.assertTrue(f.player.isInWater() && !MatchSwimming.hasStandingDepth(f.player) && !f.player.isSwimming(),
                    "Waterlogged bottom slab counted solid support as swimmable depth");
            helper.assertTrue(MovementService.eligible(f.player) && LeanService.eligible(f.player),
                    "Waterlogged shallow support blocked ground actions");
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

    private static final class PosePlayer extends FakePlayer {
        PosePlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "[swim-fixture]")); }
        void samplePose() {
            // FakePlayer.tick is empty. Run the actual fluid update owner (which calls
            // updateSwimming) and Player's native pose update without synthetic fluid flags.
            baseTick();
            updatePlayerPose();
        }
    }

    private static final class Fixture implements AutoCloseable {
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
