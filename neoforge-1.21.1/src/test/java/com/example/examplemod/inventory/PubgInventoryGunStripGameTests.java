package com.example.examplemod.inventory;

import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.backpack.BackpackWeights;
import com.example.examplemod.inventory.PubgInventoryPayloads.Action;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootWorldPhysics;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import com.example.examplemod.lootspawn.RoundLootService;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.util.AttachmentDataUtils;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Physical gun/attachment/ammunition conservation through the authorized inventory command path. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class PubgInventoryGunStripGameTests {
    private static final AtomicLong MAP_IDS = new AtomicLong(98_710_000);

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void dropReturnsMagazineChamberAndPartsWithoutMaterializingDummyReserve(GameTestHelper helper) throws Exception {
        // An empty pack, a completely full pack, and TaCZ's virtual reserve exercise distinct paths.
        for (int scenario = 0; scenario < 3; scenario++) try (var f = new Fixture(helper)) {
            boolean full = scenario == 1;
            ItemStack original = f.gun("m4a1", 7, true, "discarded rifle " + scenario);
            ItemStack sight = part("sight_t2", "returned sight"), magazine = part("extended_mag_3", "returned magazine");
            f.install(original, sight); f.install(original, magazine);
            if (scenario == 2) api(original).setDummyAmmoAmount(original, 123);
            f.player.getInventory().setItem(0, original);
            if (full) f.fillStorage();
            ResourceLocation ammo = ammoId(original);
            f.command(Action.DROP, false, 0, -1, "");
            helper.assertTrue(f.stack(0).isEmpty(), "DROP left the original gun in its inventory slot");
            f.assertBareDrop(original);
            helper.assertTrue(f.carried(sight) == (full ? 0 : 1) && f.onGround(sight) == (full ? 1 : 0)
                            && f.carried(magazine) == (full ? 0 : 1) && f.onGround(magazine) == (full ? 1 : 0),
                    "DROP failed to return each physical attachment exactly once, preserving its components");
            helper.assertTrue(f.carriedAmmo(ammo) == (full ? 0 : 8) && f.groundAmmo(ammo) == (full ? 8 : 0),
                    "DROP lost/duplicated the chamber round or materialized the 123-round virtual reserve");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void droppedAmmoUsesRemainingCapacityBeforeSpillingTheRest(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            // M249 is open-bolt: all forty are magazine rounds, with no separate chamber round.
            ItemStack gun = f.gun("m249", 40, false, "forty loaded rounds");
            helper.assertTrue(capacity(gun) >= 40, "Fixture requires a native magazine that can really hold 40 rounds");
            ResourceLocation ammo = ammoId(gun);
            ItemStack reserve = AmmoItemBuilder.create().setId(ammo).setCount(1).build();
            int unit = BackpackWeights.unit(f.player, reserve), capacity = BackpackService.capacity(f.player);
            helper.assertTrue(unit > 0 && capacity % unit == 0, "Fixture must leave exactly ten rounds of capacity");
            int existing = capacity / unit - 10;
            reserve.setCount(existing); MatchInventoryService.prepareStack(f.player, reserve);
            helper.assertTrue(existing > 0 && existing <= InventoryConfig.ammoStackLimit(f.player, reserve),
                    "Fixture reserve does not fit one legal ammunition stack");
            f.player.getInventory().setItem(9, reserve); f.player.getInventory().setItem(0, gun);
            helper.assertTrue(BackpackService.used(f.player) == capacity - 10 * unit, "Fixture does not leave ten rounds of space");
            f.command(Action.DROP, false, 0, -1, "");
            f.assertBareDrop(gun);
            helper.assertTrue(f.stack(0).isEmpty() && f.carriedAmmo(ammo) == existing + 10 && f.groundAmmo(ammo) == 30
                            && BackpackService.used(f.player) == capacity,
                    "Partial return must fill the last ten rounds of capacity and spill only the remaining thirty");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void sameCaliberReplacementTransfersCompatiblePartsButNotLoadedAmmo(GameTestHelper helper) throws Exception {
        for (boolean full : new boolean[] {false, true}) try (var f = new Fixture(helper)) {
            ItemStack old = f.gun("m4a1", 7, true, "old same-caliber rifle");
            ItemStack incoming = f.gun("m4a1", 3, false, "new same-caliber rifle");
            ItemStack sight = part("sight_t2", "transferred sight"), magazine = part("extended_mag_3", "transferred magazine");
            f.install(old, sight); f.install(old, magazine);
            helper.assertTrue(api(incoming).allowAttachment(incoming, sight) && api(incoming).allowAttachment(incoming, magazine),
                    "Fixture attachments must be compatible with the replacement rifle");
            f.player.getInventory().setItem(0, old); if (full) f.fillStorage();
            GroundLootEntity source = f.ground(incoming);
            f.command(Action.MOVE, true, source.getId(), 0, "");
            ItemStack equipped = f.stack(0);
            helper.assertTrue(source.isRemoved() && namedLike(equipped, incoming)
                            && ItemStack.matches(sight, f.attached(equipped, AttachmentType.SCOPE))
                            && ItemStack.matches(magazine, f.attached(equipped, AttachmentType.EXTENDED_MAG)),
                    "Ground replacement did not transfer compatible attachments into the incoming empty slots");
            helper.assertTrue(api(equipped).getCurrentAmmoCount(equipped) == 3 && !api(equipped).hasBulletInBarrel(equipped)
                            && f.carriedAmmo(ammoId(old)) == (full ? 0 : 8) && f.groundAmmo(ammoId(old)) == (full ? 8 : 0),
                    "Same-caliber replacement moved old loaded ammunition into the new gun or lost its chamber round");
            helper.assertTrue(f.carried(sight) + f.onGround(sight) + f.carried(magazine) + f.onGround(magazine) == 0,
                    "Transferred attachments also appeared as loose items");
            f.assertBareDrop(old);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void differentCaliberReplacementReturnsIncompatibleOrDisplacedSight(GameTestHelper helper) throws Exception {
        for (boolean occupied : new boolean[] {false, true}) for (boolean full : new boolean[] {false, true}) {
            try (var f = new Fixture(helper)) {
                ItemStack old = f.gun(occupied ? "m4a1" : "ai_awp", 2, true, "outgoing different-caliber gun");
                ItemStack incoming = f.gun(occupied ? "ai_awp" : "m4a1", 1, false, "incoming different-caliber gun");
                ItemStack oldSight = part(occupied ? "sight_t2" : "scope_standard_8x", "outgoing sight");
                ItemStack incomingSight = occupied ? part("scope_standard_8x", "keep incoming sight") : ItemStack.EMPTY;
                f.install(old, oldSight);
                if (occupied) f.install(incoming, incomingSight);
                helper.assertTrue(!ammoId(old).equals(ammoId(incoming))
                                && api(incoming).allowAttachment(incoming, oldSight) == occupied,
                        "Fixture must distinguish incompatibility from an already occupied compatible slot");
                f.player.getInventory().setItem(0, old); if (full) f.fillStorage();
                GroundLootEntity source = f.ground(incoming);
                f.command(Action.MOVE, true, source.getId(), 0, "");
                ItemStack equipped = f.stack(0);
                helper.assertTrue(source.isRemoved() && namedLike(equipped, incoming)
                                && ItemStack.matches(incomingSight, f.attached(equipped, AttachmentType.SCOPE))
                                && api(equipped).getCurrentAmmoCount(equipped) == 1 && !api(equipped).hasBulletInBarrel(equipped),
                        "Replacement overwrote the incoming gun's own sight or changed its loaded ammunition");
                helper.assertTrue(f.carried(oldSight) == (full ? 0 : 1) && f.onGround(oldSight) == (full ? 1 : 0)
                                && f.carriedAmmo(ammoId(old)) == (full ? 0 : 3) && f.groundAmmo(ammoId(old)) == (full ? 3 : 0)
                                && f.carriedAmmo(ammoId(incoming)) + f.groundAmmo(ammoId(incoming)) == 0,
                        "Incompatible/displaced attachment or old-caliber ammunition was lost, duplicated, or converted");
                f.assertBareDrop(old);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void unloadingLargeMagazineKeepsChamberAndReturnsOnlyExcess(GameTestHelper helper) throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) try (var f = new Fixture(helper)) {
            boolean full = scenario == 1; int target = scenario == 2 ? -2 : -1;
            ItemStack gun = f.gun("m4a1", 0, true, "magazine unload");
            int base = capacity(gun); ItemStack magazine = part("extended_mag_3", "unloaded large magazine");
            f.install(gun, magazine); int loaded = capacity(gun);
            helper.assertTrue(loaded > base, "Large magazine must add real native capacity");
            api(gun).setCurrentAmmoCount(gun, loaded); f.player.getInventory().setItem(0, gun);
            if (full) f.fillStorage();
            f.command(Action.UNLOAD, false, 0, target, AttachmentType.EXTENDED_MAG.name());
            ItemStack result = f.stack(0);
            helper.assertTrue(f.attached(result, AttachmentType.EXTENDED_MAG).isEmpty()
                            && api(result).getCurrentAmmoCount(result) == base && api(result).hasBulletInBarrel(result),
                    "Magazine unload must retain the final capacity plus its separate chamber round");
            helper.assertTrue(f.carriedAmmo(ammoId(gun)) == (full ? 0 : loaded - base)
                            && f.groundAmmo(ammoId(gun)) == (full ? loaded - base : 0)
                            && f.carried(magazine) == (full || target == -2 ? 0 : 1)
                            && f.onGround(magazine) == (full || target == -2 ? 1 : 0),
                    "Unload did not conserve excess rounds and the physical magazine at their requested destinations");
            helper.assertTrue(f.groundGuns().isEmpty(), "Unloading an attachment accidentally dropped the whole gun");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void smallerMagazineInstallReturnsExcessAndDisplacedMagazine(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack gun = f.gun("m4a1", 0, true, "magazine replacement");
            ItemStack large = part("extended_mag_3", "outgoing large magazine"), small = part("extended_mag_1", "incoming small magazine");
            f.install(gun, large); int loaded = capacity(gun); api(gun).setCurrentAmmoCount(gun, loaded);
            ItemStack smaller = f.gun("m4a1", 0, false, "capacity reference"); f.install(smaller, small); int finalCapacity = capacity(smaller);
            helper.assertTrue(loaded > finalCapacity, "Magazine replacement fixture must reduce capacity");
            f.player.getInventory().setItem(0, gun); f.player.getInventory().setItem(9, small);
            f.command(Action.ATTACH, false, 9, 0, AttachmentType.EXTENDED_MAG.name());
            ItemStack result = f.stack(0);
            helper.assertTrue(ItemStack.matches(small, f.attached(result, AttachmentType.EXTENDED_MAG))
                            && api(result).getCurrentAmmoCount(result) == finalCapacity && api(result).hasBulletInBarrel(result)
                            && f.carried(large) == 1 && f.carried(small) == 0 && f.onGround(large) == 0
                            && f.carriedAmmo(ammoId(gun)) == loaded - finalCapacity && f.groundAmmo(ammoId(gun)) == 0,
                    "Installing a smaller magazine rejected, destroyed excess ammunition, or duplicated the incoming magazine");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void crossGunMagazineSwapUsesBothFinalCapacities(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack first = f.gun("m4a1", 0, true, "large magazine source"), second = f.gun("m4a1", 0, true, "small magazine source");
            ItemStack large = part("extended_mag_3", "large swapped magazine"), small = part("extended_mag_1", "small swapped magazine");
            f.install(first, large); f.install(second, small);
            int firstLoaded = capacity(first), secondLoaded = capacity(second);
            helper.assertTrue(firstLoaded > secondLoaded, "Cross-gun exchange fixture must have different magazine capacities");
            api(first).setCurrentAmmoCount(first, firstLoaded); api(second).setCurrentAmmoCount(second, secondLoaded);
            f.player.getInventory().setItem(0, first); f.player.getInventory().setItem(1, second);
            f.command(Action.UNLOAD, false, 0, 1, AttachmentType.EXTENDED_MAG.name());
            ItemStack a = f.stack(0), b = f.stack(1);
            helper.assertTrue(ItemStack.matches(small, f.attached(a, AttachmentType.EXTENDED_MAG))
                            && ItemStack.matches(large, f.attached(b, AttachmentType.EXTENDED_MAG))
                            && api(a).getCurrentAmmoCount(a) == secondLoaded && api(b).getCurrentAmmoCount(b) == secondLoaded
                            && api(a).hasBulletInBarrel(a) && api(b).hasBulletInBarrel(b),
                    "Cross-gun exchange failed to settle each gun against its final magazine while preserving both chambers");
            helper.assertTrue(f.carriedAmmo(ammoId(first)) == firstLoaded - secondLoaded && f.groundAmmo(ammoId(first)) == 0
                            && f.carried(large) + f.carried(small) + f.onGround(large) + f.onGround(small) == 0,
                    "Cross-gun exchange duplicated a magazine or returned ammunition based on the temporary unloaded state");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void canceledSecondDropCallbackRollsBackGunAndGroundReplacementAtomically(GameTestHelper helper) throws Exception {
        for (boolean replacement : new boolean[] {false, true}) for (boolean spawnVeto : new boolean[] {false, true}) {
            try (var f = new Fixture(helper)) {
                ItemStack gun = f.gun("m4a1", 7, true, "gun before veto");
                f.install(gun, part("sight_t2", "sight before veto"));
                f.install(gun, part("extended_mag_3", "magazine before veto"));
                f.player.getInventory().setItem(0, gun); f.fillStorage();
                ItemStack incoming = f.gun("ai_awp", 1, false, "ground gun before veto");
                f.install(incoming, part("scope_standard_8x", "incoming sight before veto"));
                GroundLootEntity source = replacement ? f.ground(incoming) : null;
                ItemStack[] before = f.snapshot();
                var data = IGunOperator.fromLivingEntity(f.player).getDataHolder();
                data.currentGunItem = () -> f.player.getMainHandItem(); data.isAiming = true;
                long reloadTimestamp = System.currentTimeMillis(); data.reloadTimestamp = reloadTimestamp;
                AtomicInteger callbacks = new AtomicInteger();
                Consumer<EntityJoinLevelEvent> spawn = event -> {
                    if (spawnVeto && event.getEntity() instanceof GroundLootEntity loot && f.near(loot)
                            && callbacks.incrementAndGet() == 2) event.setCanceled(true);
                };
                Consumer<ItemTossEvent> toss = event -> {
                    if (!spawnVeto && event.getPlayer() == f.player && callbacks.incrementAndGet() == 2) event.setCanceled(true);
                };
                NeoForge.EVENT_BUS.addListener(spawn); NeoForge.EVENT_BUS.addListener(toss);
                try {
                    f.command(replacement ? Action.MOVE : Action.DROP, replacement, replacement ? source.getId() : 0, replacement ? 0 : -1, "");
                } finally { NeoForge.EVENT_BUS.unregister(spawn); NeoForge.EVENT_BUS.unregister(toss); }
                helper.assertTrue(callbacks.get() == 2, "Fixture did not cancel a later callback after an earlier planned drop");
                f.assertUnchanged(before);
                helper.assertTrue(f.player.getMainHandItem() == gun && data.isAiming && data.reloadTimestamp == reloadTimestamp,
                        "Aborted strip/replacement changed the active gun object or canceled an uncommitted aiming/reload action");
                helper.assertTrue(f.ground().size() == (replacement ? 1 : 0), "A canceled transaction leaked an earlier spawned return/drop");
                if (replacement) helper.assertTrue(!source.isRemoved() && source.getQuantity() == 1
                                && same(source.getItem(), incoming),
                        "Canceled ground replacement consumed or altered the incoming weapon");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void lockedExternalPartsRejectStripAndReplacementWithoutPartialReturns(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack gun = f.gun("m4a1", 7, true, "locked external attachment gun");
            f.install(gun, part("sight_t2", "locked physical sight")); api(gun).setAttachmentLock(gun, true);
            f.player.getInventory().setItem(0, gun); ItemStack[] before = f.snapshot();
            f.command(Action.DROP, false, 0, -1, ""); f.assertUnchanged(before);
            helper.assertTrue(f.ground().isEmpty(), "Locked attachment DROP leaked a gun or an ammunition refund");
            GroundLootEntity source = f.ground(f.gun("m4a1", 1, false, "incoming locked replacement"));
            f.command(Action.MOVE, true, source.getId(), 0, ""); f.assertUnchanged(before);
            helper.assertTrue(!source.isRemoved() && source.getQuantity() == 1 && f.ground().size() == 1,
                    "Locked attachment replacement consumed its source or produced a misleading partially stripped gun");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void internalWeaponSlotSwapPreservesWholeGuns(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack a = f.gun("m4a1", 7, true, "whole rifle"), b = f.gun("ai_awp", 2, false, "whole sniper");
            f.install(a, part("sight_t2", "rifle sight")); f.install(b, part("scope_standard_8x", "sniper sight"));
            ItemStack expectedA = a.copy(), expectedB = b.copy();
            f.player.getInventory().setItem(0, a); f.player.getInventory().setItem(1, b);
            f.command(Action.MOVE, false, 0, 1, "");
            helper.assertTrue(ItemStack.matches(f.stack(0), expectedB) && ItemStack.matches(f.stack(1), expectedA)
                            && f.ground().isEmpty() && f.carriedAmmo(ammoId(a)) + f.carriedAmmo(ammoId(b)) == 0,
                    "An internal primary-slot swap stripped attachments/ammunition instead of moving complete guns");
            helper.succeed();
        }
    }

    private static IGun api(ItemStack gun) { return (IGun) gun.getItem(); }
    private static ResourceLocation ammoId(ItemStack gun) {
        return TimelessAPI.getCommonGunIndex(api(gun).getGunId(gun)).orElseThrow().getGunData().getAmmoId();
    }
    private static int capacity(ItemStack gun) {
        return AttachmentDataUtils.getAmmoCountWithAttachment(gun,
                TimelessAPI.getCommonGunIndex(api(gun).getGunId(gun)).orElseThrow().getGunData());
    }
    private static ItemStack part(String id, String name) {
        ItemStack result = AttachmentItemBuilder.create().setId(ResourceLocation.fromNamespaceAndPath("tacz", id)).build();
        result.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return result;
    }
    private static boolean namedLike(ItemStack a, ItemStack b) {
        return a.getItem() instanceof IGun && api(a).getGunId(a).equals(api(b).getGunId(b))
                && a.getHoverName().equals(b.getHoverName());
    }
    private static boolean same(ItemStack a, ItemStack b) {
        ItemStack left = a.copy(), right = b.copy();
        // Gameplay provenance is added to real drops; it is not a new attachment or a changed custom name.
        left.remove(RoundLootService.MARK); right.remove(RoundLootService.MARK);
        return ItemStack.isSameItemSameComponents(left, right);
    }
    private static boolean isAmmo(ItemStack stack, ResourceLocation id) {
        return stack.getItem() instanceof IAmmo ammo && ammo.getAmmoId(stack).equals(id);
    }

    /** Real match authorization and snapshots, with no test-only bypass of handle's validation. */
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final FakePlayer player;
        final Object state;
        final long mapId = MAP_IDS.incrementAndGet();

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "gun-strip-test"));
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            player.setPos(origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5);
            player.setDeltaMovement(Vec3.ZERO); player.getInventory().clearContent(); player.getInventory().selected = 0;
            player.getAbilities().instabuild = false;
            var field = MatchService.class.getDeclaredField("STATES"); field.setAccessible(true);
            Map<MinecraftServer, Object> states = (Map<MinecraftServer, Object>) field.get(null);
            Class<?> type = Class.forName("com.example.examplemod.zones.match.MatchRuntimeState");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            state = states.containsKey(player.server) ? states.get(player.server) : constructor.newInstance(); states.put(player.server, state);
            var map = new MapZoneModel.MapDefinition(mapId, "gun strip fixture", player.level().dimension().location(),
                    origin.getX() - 50, origin.getZ() - 50, origin.getX() + 50, origin.getZ() + 50,
                    new MapZoneModel.Circle(origin.getX(), origin.getZ(), 40), 0, List.of());
            var defaults = MatchConfigModel.defaults(mapId);
            var settings = new MatchConfigModel.Settings(mapId, "gun strip fixture", 1, 20, 1, 0, 2400, 60,
                    false, false, false, false, defaults.phases(),
                    new MatchConfigModel.BombingRules(false, .12, 60, defaults.bombingRules().projectile()),
                    defaults.flowRules(), false, false, new MatchConfigModel.FlightRules(false, true, 40, 512, 3, 52));
            var start = type.getDeclaredMethod("start", MapZoneModel.MapDefinition.class, MatchConfigModel.Settings.class, List.class, long.class);
            start.setAccessible(true);
            start.invoke(state, map, settings, List.of(new MatchEngine.Participant(player.getUUID(), "gun-strip-test", "A")), 71L);
            helper.assertTrue(InventoryConfig.active(player), "Fixture did not activate the production match inventory gate");
        }

        ItemStack gun(String id, int rounds, boolean chamber, String name) {
            ItemStack result = GunItemBuilder.create().setId(ResourceLocation.fromNamespaceAndPath("tacz", id))
                    .setAmmoCount(rounds).setAmmoInBarrel(chamber).build(player.registryAccess());
            helper.assertTrue(result.getItem() instanceof IGun, "Native gun fixture unavailable: " + id);
            result.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return result;
        }
        void install(ItemStack gun, ItemStack attachment) {
            var type = ((com.tacz.guns.api.item.IAttachment) attachment.getItem()).getType(attachment);
            helper.assertTrue(api(gun).allowAttachment(gun, attachment)
                            && DefaultAssets.isEmptyAttachmentId(api(gun).getBuiltInAttachmentId(gun, type)),
                    "Fixture requires a compatible physical attachment rather than a built-in fallback: " + attachment.getHoverName());
            api(gun).installAttachment(player.registryAccess(), gun, attachment.copy());
            helper.assertTrue(ItemStack.matches(attachment, attached(gun, type)), "Native fixture install lost attachment components");
        }
        ItemStack attached(ItemStack gun, AttachmentType type) { return api(gun).getAttachment(player.registryAccess(), gun, type); }
        ItemStack stack(int slot) { return player.getInventory().getItem(slot); }
        void fillStorage() { for (int slot = 3; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64)); }

        @SuppressWarnings("unchecked")
        void command(Action action, boolean ground, int source, int target, String attachmentType) throws Exception {
            map("LAST_REFRESH").remove(player.getUUID());
            PubgInventoryService.handle(player, new PubgInventoryPayloads.Command(Action.REFRESH, -1, false, -1, -1, ""));
            ArrayDeque<PubgInventoryPayloads.Snapshot> snapshots = (ArrayDeque<PubgInventoryPayloads.Snapshot>) map("SNAPSHOTS").get(player.getUUID());
            helper.assertTrue(snapshots != null && !snapshots.isEmpty(), "Real REFRESH did not authorize a command snapshot");
            PubgInventoryService.handle(player, new PubgInventoryPayloads.Command(action, snapshots.getLast().revision(), ground, source, target, attachmentType));
        }
        GroundLootEntity ground(ItemStack stack) {
            BlockPos floor = player.blockPosition().below(); player.level().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            GroundLootEntity result = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
            if (result == null) throw new AssertionError("Ground loot type unavailable");
            result.initializeDrop(stack, floor, 0, null); result.setPos(player.getX(), floor.getY() + 1, player.getZ());
            helper.assertTrue(player.level().addFreshEntity(result), "Fixture source spawn was canceled");
            GroundLootWorldPhysics.prepareSpawn(result); GroundLootWorldPhysics.tick(result);
            helper.assertTrue(result.canBePickedUpBy(player) && PubgInventoryService.nearby(player, result),
                    "Fixture source has not physically landed within the visible pickup range");
            return result;
        }
        boolean near(GroundLootEntity loot) { return loot.level() == player.level() && loot.position().distanceToSqr(player.position()) < 4; }
        List<GroundLootEntity> ground() { return player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2)); }
        List<GroundLootEntity> groundGuns() { return ground().stream().filter(loot -> loot.getItem().getItem() instanceof IGun).toList(); }
        int carried(ItemStack expected) {
            int count = 0; for (int slot = 0; slot < 41; slot++) if (same(stack(slot), expected)) count += stack(slot).getCount(); return count;
        }
        int onGround(ItemStack expected) { return ground().stream().filter(loot -> same(loot.getItem(), expected)).mapToInt(GroundLootEntity::getQuantity).sum(); }
        int carriedAmmo(ResourceLocation id) {
            int count = 0; for (int slot = 0; slot < 41; slot++) if (isAmmo(stack(slot), id)) count += stack(slot).getCount(); return count;
        }
        int groundAmmo(ResourceLocation id) { return ground().stream().filter(loot -> isAmmo(loot.getItem(), id)).mapToInt(GroundLootEntity::getQuantity).sum(); }
        void assertBareDrop(ItemStack original) {
            List<GroundLootEntity> matching = groundGuns().stream().filter(loot -> namedLike(loot.getItem(), original)).toList();
            helper.assertTrue(matching.size() == 1 && matching.getFirst().getQuantity() == 1, "Old gun must appear exactly once on the ground");
            ItemStack dropped = matching.getFirst().getItem();
            helper.assertTrue(api(dropped).getCurrentAmmoCount(dropped) == 0 && !api(dropped).hasBulletInBarrel(dropped),
                    "Dropped gun retained loaded magazine ammunition or a chamber round");
            for (AttachmentType type : AttachmentType.values()) if (type != AttachmentType.NONE
                    && DefaultAssets.isEmptyAttachmentId(api(dropped).getBuiltInAttachmentId(dropped, type))) {
                helper.assertTrue(attached(dropped, type).isEmpty() && DefaultAssets.isEmptyAttachmentId(api(dropped).getAttachmentId(dropped, type)),
                        "Dropped gun retained an external attachment: " + type);
            }
        }
        ItemStack[] snapshot() {
            ItemStack[] before = new ItemStack[41]; for (int slot = 0; slot < 41; slot++) before[slot] = stack(slot).copy(); return before;
        }
        void assertUnchanged(ItemStack[] before) {
            for (int slot = 0; slot < 41; slot++) helper.assertTrue(ItemStack.matches(before[slot], stack(slot)), "Canceled/rejected command mutated inventory slot " + slot);
        }
        @Override public void close() throws Exception {
            ground().forEach(GroundLootEntity::discard); BackpackService.setEquipped(player, ItemStack.EMPTY);
            player.getInventory().clearContent();
            for (String name : List.of("SNAPSHOTS", "RECENT", "LAST_REFRESH", "SESSIONS", "FAILURE")) map(name).remove(player.getUUID());
            var end = state.getClass().getDeclaredMethod("end", long.class); end.setAccessible(true); end.invoke(state, mapId);
            var forget = state.getClass().getDeclaredMethod("forgetDeletedMap", long.class); forget.setAccessible(true); forget.invoke(state, mapId);
            player.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> map(String name) throws Exception {
        var field = PubgInventoryService.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<UUID, Object>) field.get(null);
    }
}
