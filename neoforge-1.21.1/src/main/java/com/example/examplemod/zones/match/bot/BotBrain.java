package com.example.examplemod.zones.match.bot;

import com.example.examplemod.localization.Texts;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootPickup;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchEngine.Participant;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.MatchSession;
import com.example.examplemod.zones.match.MatchStatus;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.api.modifier.ParameterizedCachePair;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.modifier.custom.RecoilModifier;
import com.tacz.guns.resource.pojo.data.gun.GunRecoilKeyFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Code-only perception, native TaCZ operation and bounded loaded-terrain walking. */
final class BotBrain {
    enum Mode { AUTO, SEEK, LOOT, ATTACK, EVADE, FOLLOW, GUARD, PATROL, MOVE, HOLD, CIRCLE }
    private final EntityPlayerMPFake body;
    private final MatchBotService.BotSpec spec;
    private final MapDefinition map;
    private final BotRules.Skill skill;
    private final Random random;
    private UUID target;
    private Vec3 lastSeen;
    private int seenTick = -1000, acquiredTick, nextShot, shotsInBurst, nextReload;
    private double noiseYaw, noisePitch, recoilYaw, recoilPitch;
    private boolean suspended;
    private ItemStack drawn = ItemStack.EMPTY;
    private BotPathfinder search;
    private List<BotPathfinder.Cell> path = List.of();
    private int pathIndex, replanAt, pathX, pathY, pathZ, progressTick;
    private Vec3 progressPosition;
    private GroundLootEntity supply;
    private String teamId;
    private Mode mode = Mode.AUTO;
    private boolean fireAllowed = true, reloadRequested;
    private Vec3 orderedPoint;
    private UUID follow;
    private ShootResult lastResult;
    private long acceptedShots;

    BotBrain(EntityPlayerMPFake body, MatchBotService.BotSpec spec, MapDefinition map) {
        this.body = body; this.spec = spec; this.map = map; skill = BotRules.skill(spec.difficulty());
        random = new Random(spec.plannedId().getLeastSignificantBits());
        teamId = spec.teamId();
    }
    void team(String id) { teamId = id; }
    void mode(String selected, Vec3 point, UUID followed) {
        mode = Mode.valueOf(selected.toUpperCase(java.util.Locale.ROOT));
        orderedPoint = point == null ? body.position() : point; follow = followed;
        path = List.of(); search = null; replanAt = 0;
        if (mode == Mode.HOLD) {
            actions().stopAll(); operator().aim(false); operator().cancelReload();
            target = null; lastSeen = null;
        }
    }
    void fire(boolean allowed) { fireAllowed = allowed; if (!allowed) operator().aim(false); }
    void reload() { reloadRequested = true; }
    String description() {
        String label = switch (mode) {
            case AUTO -> Texts.tr("message.tacz_pubg.bot_brain.automatic_combat"); case SEEK -> Texts.tr("message.tacz_pubg.bot_brain.seek_enemies"); case LOOT -> Texts.tr("message.tacz_pubg.bot_brain.collect_loot"); case ATTACK -> Texts.tr("message.tacz_pubg.bot_brain.attack_enemies");
            case EVADE -> Texts.tr("message.tacz_pubg.bot_brain.evade_enemies"); case FOLLOW -> Texts.tr("message.tacz_pubg.bot_brain.follow_teammate"); case GUARD -> Texts.tr("message.tacz_pubg.bot_brain.guard_position");
            case PATROL -> Texts.tr("message.tacz_pubg.bot_brain.patrol"); case MOVE -> Texts.tr("message.tacz_pubg.bot_brain.move_target"); case HOLD -> Texts.tr("message.tacz_pubg.bot_brain.hold_position"); case CIRCLE -> Texts.tr("message.tacz_pubg.bot_brain.enter_safe_zone");
        };
        ItemStack held = body.getMainHandItem(); int loaded = 0, reserve = 0;
        if (held.getItem() instanceof IGun gun) {
            loaded = gun.getCurrentAmmoCount(held) + (gun.hasBulletInBarrel(held) ? 1 : 0);
            for (ItemStack stack : body.getInventory().items)
                if (stack.getItem() instanceof IAmmo ammo && ammo.isAmmoOfGun(held, stack)) reserve += stack.getCount();
        }
        return Texts.tr("message.tacz_pubg.bot_brain.behavior_fire_loaded_reserve_accepted_triggers_last_result", label, (fireAllowed ? Texts.tr("message.tacz_pubg.bot_brain.fire_will") : Texts.tr("message.tacz_pubg.bot_brain.hold_fire")), loaded, reserve, acceptedShots, (lastResult == null ? Texts.tr("message.tacz_pubg.bot_brain.awaiting_trigger") : lastResult.name()));
    }
    private EntityPlayerActionPack actions() { return ((ServerPlayerInterface) body).getActionPack(); }
    private IGunOperator operator() { return IGunOperator.fromLivingEntity(body); }
    void equip() {
        body.getInventory().clearContent(); body.getInventory().selected = 0;
        body.getInventory().setChanged();
    }
    private static boolean supported(CommonGunIndex index) {
        return List.of("rifle", "smg").contains(index.getType()) && index.getGunData().getBulletData().getExplosionData() == null
                && (index.getGunData().getFireModeSet().contains(FireMode.SEMI) || index.getGunData().getFireModeSet().contains(FireMode.AUTO));
    }
    private static FireMode preferredMode(CommonGunIndex index) {
        return index.getGunData().getFireModeSet().contains(FireMode.SEMI) ? FireMode.SEMI : FireMode.AUTO;
    }
    void suspend() {
        actions().stopAll(); body.setSprinting(false); body.setJumping(false);
        if (!suspended) { operator().aim(false); operator().cancelReload(); }
        suspended = true; target = null; lastSeen = null; shotsInBurst = 0;
    }
    void tick(MinecraftServer server, MatchSession session, List<Participant> roster, BotRules.Budget budget) {
        MatchStatus status = MatchService.status(server, map.id());
        if (status == null || !status.sessionId().equals(session.sessionId()) || "paused".equals(status.stage())
                || "preparing".equals(status.stage()) || !body.isAlive() || body.isRemoved()
                || com.example.examplemod.zones.match.flight.MatchFlightService.airborne(server, body.getUUID())
                || body.isSpectator() || body.isCreative() || !map.dimension().equals(body.serverLevel().dimension().location())) {
            suspend(); return;
        }
        suspended = false;
        int tick = server.getTickCount();
        actions().setSprinting(false).setForward(0).setStrafing(0); body.setSprinting(false);
        if (!(body.getMainHandItem().getItem() instanceof IGun)) equipInventoryGun();
        drawCurrent();
        if (reloadRequested && tick >= nextReload && body.getMainHandItem().getItem() instanceof IGun
                && !operator().getSynReloadState().getStateType().isReloading() && !operator().getSynIsBolting()) {
            operator().reload(); reloadRequested = false; nextReload = tick + 30;
        }
        if (mode == Mode.HOLD) { actions().stopAll(); return; }
        recoilPitch *= .84; recoilYaw *= .8;
        noiseYaw = noiseYaw * .88 + random.nextGaussian() * skill.aimError() * .15;
        noisePitch = noisePitch * .88 + random.nextGaussian() * skill.aimError() * .15;
        if (Math.floorMod(tick + body.getId(), 6) == 0) perceive(server, roster, budget, tick);
        if (lastSeen != null && tick - seenTick > 60) { target = null; lastSeen = null; }
        Circle circle = status.targetCircle() != null ? status.targetCircle() : status.currentCircle();
        boolean urgent = circle != null && Math.hypot(body.getX() - circle.centerX(), body.getZ() - circle.centerZ()) > Math.max(.5, circle.radius() * .88);
        Vec3 destination = urgent || mode == Mode.CIRCLE && circle != null ? inside(circle) : null;
        ServerPlayer enemy = target == null ? null : server.getPlayerList().getPlayer(target);
        boolean canFight = enemy != null && validEnemy(server, enemy, roster) && body.distanceToSqr(enemy) <= skill.range() * skill.range()
                && BotTerrain.visible(body, chest(enemy), budget);
        if (canFight) {
            lastSeen = chest(enemy); seenTick = tick;
            Vec3 difference = lastSeen.subtract(body.getEyePosition());
            float desiredYaw = (float) (Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90);
            float desiredPitch = (float) -Math.toDegrees(Math.atan2(difference.y, difference.horizontalDistance()));
            aim(desiredYaw + (float) (noiseYaw + recoilYaw), desiredPitch + (float) (noisePitch - recoilPitch));
            if (!urgent && mode == Mode.EVADE) destination = body.position().add(difference.normalize().scale(-10));
            if (!urgent && List.of(Mode.AUTO, Mode.SEEK, Mode.ATTACK).contains(mode)) {
                if (difference.horizontalDistance() > 22) destination = lastSeen;
                if (difference.horizontalDistance() < 7) destination = body.position().add(difference.normalize().scale(-5));
            }
            if (fireAllowed && mode != Mode.EVADE && mode != Mode.LOOT) fight(server, roster, enemy, tick, budget, desiredYaw, desiredPitch);
        } else {
            if (operator().getSynIsAiming()) operator().aim(false);
            if (destination == null && lastSeen != null && List.of(Mode.AUTO, Mode.SEEK, Mode.ATTACK).contains(mode)) destination = lastSeen;
            if (destination == null && (mode == Mode.LOOT || mode == Mode.AUTO && needsSupplies())) destination = supplies(budget, tick);
            reloadWhenUseful(tick);
        }
        if (destination == null && mode == Mode.LOOT) destination = supplies(budget, tick);
        if (destination == null) destination = orderedDestination(server, roster, circle, tick);
        if (destination != null) move(destination, budget, tick, canFight);
        else actions().look(body.getYRot() + 1.4F, body.getXRot() * .8F);
        body.setYHeadRot(body.getYRot()); body.setYBodyRot(body.getYRot());
    }
    private Vec3 orderedDestination(MinecraftServer server, List<Participant> roster, Circle circle, int tick) {
        if (mode == Mode.GUARD || mode == Mode.MOVE) {
            if (mode == Mode.MOVE && orderedPoint != null && body.position().distanceToSqr(orderedPoint) < 2) mode = Mode.GUARD;
            return orderedPoint;
        }
        if (mode == Mode.FOLLOW && follow != null && MatchService.alive(server, map.id(), follow)) {
            ServerPlayer teammate = server.getPlayerList().getPlayer(follow);
            if (teammate != null && teammate.serverLevel() == body.serverLevel())
                return body.distanceToSqr(teammate) > 16 ? teammate.position() : null;
        }
        if (mode == Mode.EVADE && lastSeen != null) return body.position().add(body.position().subtract(lastSeen).normalize().scale(10));
        return followOrPatrol(server, roster, circle, tick);
    }
    private void drawCurrent() {
        ItemStack held = body.getMainHandItem();
        if (held == drawn) return;
        if (held.getItem() instanceof IGun gun) {
            CommonGunIndex index = TimelessAPI.getCommonGunIndex(gun.getGunId(held)).orElse(null);
            if (index == null || !supported(index)) return;
            gun.setFireMode(held, preferredMode(index));
        }
        operator().draw(body::getMainHandItem); drawn = held;
    }
    private void equipInventoryGun() {
        for (int slot = 0; slot < 9; slot++) if (body.getInventory().getItem(slot).getItem() instanceof IGun gun) {
            CommonGunIndex index = TimelessAPI.getCommonGunIndex(gun.getGunId(body.getInventory().getItem(slot))).orElse(null);
            if (index != null && supported(index)) { body.getInventory().selected = slot; drawCurrent(); return; }
        }
        for (int slot = 9; slot < 36; slot++) if (body.getInventory().getItem(slot).getItem() instanceof IGun gun) {
            CommonGunIndex index = TimelessAPI.getCommonGunIndex(gun.getGunId(body.getInventory().getItem(slot))).orElse(null);
            if (index == null || !supported(index)) continue;
            ItemStack current = body.getInventory().getItem(0); body.getInventory().setItem(0, body.getInventory().getItem(slot));
            body.getInventory().setItem(slot, current); body.getInventory().selected = 0; drawCurrent(); return;
        }
    }
    private boolean validEnemy(MinecraftServer server, ServerPlayer candidate, List<Participant> roster) {
        return candidate != body && candidate.serverLevel() == body.serverLevel() && candidate.isAlive()
                && !candidate.isSpectator() && !candidate.isCreative() && MatchService.alive(server, map.id(), candidate.getUUID())
                && roster.stream().anyMatch(entry -> entry.id().equals(candidate.getUUID()) && !entry.teamId().equals(teamId));
    }
    private void perceive(MinecraftServer server, List<Participant> roster, BotRules.Budget budget, int tick) {
        List<ServerPlayer> candidates = roster.stream().filter(entry -> !entry.teamId().equals(teamId))
                .map(entry -> server.getPlayerList().getPlayer(entry.id())).filter(player -> player != null && validEnemy(server, player, roster))
                .filter(player -> body.distanceToSqr(player) <= skill.range() * skill.range())
                .sorted(Comparator.comparingDouble(body::distanceToSqr)).toList();
        for (ServerPlayer player : candidates) {
            Vec3 delta = chest(player).subtract(body.getEyePosition());
            if (delta.lengthSqr() > 25 && body.getViewVector(1).dot(delta.normalize()) < Math.cos(Math.toRadians(60))) continue;
            if (!BotTerrain.visible(body, chest(player), budget)) continue;
            if (!player.getUUID().equals(target)) { acquiredTick = tick; shotsInBurst = 0; }
            target = player.getUUID(); lastSeen = chest(player); seenTick = tick; return;
        }
    }
    private void aim(float yaw, float pitch) {
        actions().look(BotRules.approach(body.getYRot(), yaw, skill.turnDegrees()),
                Math.clamp(BotRules.approach(body.getXRot(), pitch, skill.turnDegrees()), -85, 85));
    }
    private void fight(MinecraftServer server, List<Participant> roster, ServerPlayer enemy, int tick,
            BotRules.Budget budget, float desiredYaw, float desiredPitch) {
        ItemStack held = body.getMainHandItem(); if (!(held.getItem() instanceof IGun gun)) return;
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gun.getGunId(held)).orElse(null);
        if (index == null || !supported(index)) return;
        FireMode mode = preferredMode(index); if (gun.getFireMode(held) != mode) gun.setFireMode(held, mode);
        IGunOperator operator = operator(); if (!operator.getSynIsAiming()) operator.aim(true);
        if (tick < nextShot || tick - acquiredTick < skill.reactionTicks() || operator.getSynAimingProgress() < .6
                || operator.getSynReloadState().getStateType().isReloading() || operator.getSynIsBolting()
                || Math.abs(BotRules.wrap(desiredYaw - body.getYRot())) > skill.aimError() + 3
                || Math.abs(desiredPitch - body.getXRot()) > skill.aimError() + 3) return;
        Vec3 from = body.getEyePosition(), to = from.add(body.getViewVector(1).scale(Math.max(3, body.distanceTo(enemy) + 2)));
        for (Participant entry : roster) if (entry.teamId().equals(teamId) && !entry.id().equals(body.getUUID())
                && MatchService.alive(server, map.id(), entry.id())) {
            ServerPlayer teammate = server.getPlayerList().getPlayer(entry.id());
            if (teammate != null && teammate.serverLevel() == body.serverLevel()
                    && teammate.getBoundingBox().inflate(.55).clip(from, to).isPresent()) return;
        }
        // Trace immediately before a trigger; a cached perception result never authorizes a shot through a wall.
        if (!BotTerrain.visible(body, chest(enemy), budget)) return;
        ShootResult result = operator.shoot(body::getXRot, body::getYRot);
        lastResult = result;
        switch (result) {
            case SUCCESS -> {
                acceptedShots++;
                nextShot = tick + Math.max(2, (int) Math.ceil(1200D / Math.max(1, index.getGunData().getRoundsPerMinute(mode))));
                if (++shotsInBurst >= 3) { shotsInBurst = 0; nextShot += 6 + random.nextInt(9); }
                kick(index);
            }
            case NEED_BOLT -> { operator.bolt(); nextShot = tick + 4; }
            case NO_AMMO -> reloadWhenUseful(tick);
            case NOT_DRAW -> { operator.draw(body::getMainHandItem); nextShot = tick + 4; }
            case ID_NOT_EXIST, NOT_GUN -> { drawn = ItemStack.EMPTY; nextShot = tick + 20; }
            default -> nextShot = tick + 1;
        }
    }
    private void reloadWhenUseful(int tick) {
        ItemStack held = body.getMainHandItem(); if (!(held.getItem() instanceof IGun gun)) return;
        IGunOperator operator = operator();
        if (tick < nextReload || operator.getSynIsBolting() || operator.getSynReloadState().getStateType().isReloading()
                || gun.getCurrentAmmoCount(held) > 2 || !gun.hasInventoryAmmo(body, held, operator.needCheckAmmo())) return;
        operator.reload(); nextReload = tick + 30;
    }
    private void kick(CommonGunIndex index) {
        float pitchModifier = 1, yawModifier = 1;
        if (operator().getCacheProperty() != null) {
            try {
                ParameterizedCachePair<Float, Float> modifier = operator().getCacheProperty().getCache(RecoilModifier.ID);
                if (modifier != null) {
                    double pitch = modifier.left().eval(1), yaw = modifier.right().eval(1);
                    if (Double.isFinite(pitch) && pitch >= 0) pitchModifier = (float) Math.min(4, pitch);
                    if (Double.isFinite(yaw) && yaw >= 0) yawModifier = (float) Math.min(4, yaw);
                }
            } catch (NullPointerException | ClassCastException unavailableCache) {
                // TaCZ can briefly expose an empty attachment cache around a gun-data reload. Native recoil remains available.
            }
        }
        var recoil = index.getGunData().getRecoil(); if (recoil == null) return;
        recoilPitch += sampleKick(recoil.getPitch()) * pitchModifier * (1 - skill.compensation());
        recoilYaw += sampleKick(recoil.getYaw()) * yawModifier * (1 - skill.compensation());
        recoilPitch = Math.clamp(recoilPitch, -12, 12); recoilYaw = Math.clamp(recoilYaw, -10, 10);
    }
    private double sampleKick(GunRecoilKeyFrame[] frames) {
        if (frames == null) return 0; double peak = 0;
        for (GunRecoilKeyFrame frame : frames) if (frame.getTime() <= .25 && frame.getValue() != null && frame.getValue().length >= 2) {
            float[] bounds = frame.getValue(); double value = bounds[0] + random.nextDouble() * (bounds[1] - bounds[0]);
            if (Double.isFinite(value) && Math.abs(value) > Math.abs(peak)) peak = value;
        }
        return Math.clamp(peak, -8, 8);
    }
    private boolean needsSupplies() {
        ItemStack held = body.getMainHandItem(); if (!(held.getItem() instanceof IGun gun)) return true;
        return gun.getCurrentAmmoCount(held) < 8 && !gun.hasInventoryAmmo(body, held, true);
    }
    private Vec3 supplies(BotRules.Budget budget, int tick) {
        if (supply == null || supply.isRemoved() || !useful(supply) || body.distanceToSqr(supply) > 225) {
            supply = null;
            if (Math.floorMod(tick + body.getId(), 10) != 0) return null;
            List<GroundLootEntity> candidates = body.serverLevel().getEntitiesOfClass(GroundLootEntity.class, body.getBoundingBox().inflate(12));
            candidates.sort(Comparator.comparingDouble(body::distanceToSqr));
            for (GroundLootEntity loot : candidates) if (useful(loot) && BotTerrain.visible(body, loot.position().add(0, .08, 0), budget)) { supply = loot; break; }
        }
        if (supply == null) return null;
        if (body.distanceToSqr(supply) <= 7) {
            int originalSlot = body.getInventory().selected, empty = -1;
            for (int slot = 0; slot < 9; slot++) if (body.getInventory().getItem(slot).isEmpty()) { empty = slot; break; }
            if (empty < 0) return null;
            body.getInventory().selected = empty; operator().aim(false);
            actions().lookAt(supply.position().add(0, .08, 0));
            Vec3 eye = body.getEyePosition();
            if (BotTerrain.loadedSegment(body.serverLevel(), eye, eye.add(body.getViewVector(1).scale(GroundLootPickup.reach(body))))
                    && GroundLootPickup.canPickup(body, supply)) supply.pickup(body);
            body.getInventory().selected = originalSlot; drawn = ItemStack.EMPTY; equipInventoryGun(); drawCurrent();
        }
        return supply == null || supply.isRemoved() ? null : supply.position();
    }
    private boolean useful(GroundLootEntity loot) {
        if (!loot.isManagedLoot() || loot.isRemoved() || !loot.canBePickedUpBy(body)) return false;
        ItemStack stack = loot.getItem(), held = body.getMainHandItem();
        if (stack.getItem() instanceof IGun gun && !(held.getItem() instanceof IGun))
            return TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).filter(BotBrain::supported).isPresent();
        return stack.getItem() instanceof IAmmo ammo && held.getItem() instanceof IGun && ammo.isAmmoOfGun(held, stack);
    }
    private Vec3 followOrPatrol(MinecraftServer server, List<Participant> roster, Circle circle, int tick) {
        for (Participant entry : roster) if (mode != Mode.PATROL && entry.teamId().equals(teamId) && !entry.id().equals(body.getUUID())
                && MatchService.alive(server, map.id(), entry.id())) {
            ServerPlayer teammate = server.getPlayerList().getPlayer(entry.id());
            if (teammate != null && teammate.serverLevel() == body.serverLevel() && body.distanceToSqr(teammate) > 64)
                return teammate.position();
        }
        if (circle == null) return null;
        double angle = Math.floorDiv(tick, 180) * .75 + (spec.plannedId().hashCode() & 255) * .1;
        double radius = Math.min(20, circle.radius() * .55);
        return new Vec3(circle.centerX() + Math.cos(angle) * radius, body.getY(), circle.centerZ() + Math.sin(angle) * radius);
    }
    private Vec3 inside(Circle circle) {
        double dx = body.getX() - circle.centerX(), dz = body.getZ() - circle.centerZ(), distance = Math.hypot(dx, dz);
        double safe = Math.max(0, circle.radius() * .65);
        return new Vec3(circle.centerX() + dx / Math.max(1, distance) * Math.min(safe, distance), body.getY(),
                circle.centerZ() + dz / Math.max(1, distance) * Math.min(safe, distance));
    }
    private void move(Vec3 destination, BotRules.Budget budget, int tick, boolean fighting) {
        double dx = destination.x - body.getX(), dz = destination.z - body.getZ(), distance = Math.hypot(dx, dz);
        boolean exactHeight = destination == orderedPoint && (mode == Mode.MOVE || mode == Mode.GUARD);
        if (distance < 1.2 && (!exactHeight || Math.abs(destination.y - body.getY()) < 1.2)) return;
        double horizon = Math.min(23, distance);
        int x = (int) Math.floor(body.getX() + dx / Math.max(.001, distance) * horizon), z = (int) Math.floor(body.getZ() + dz / Math.max(.001, distance) * horizon);
        int y = exactHeight && distance <= 23 ? (int) Math.floor(destination.y) : Integer.MIN_VALUE;
        if (progressPosition == null || tick - progressTick >= 30) {
            if (progressPosition != null && body.position().distanceToSqr(progressPosition) < .36) {
                path = List.of(); search = null; replanAt = tick;
                // A bounded side goal changes the route after a stall; every cell still passes terrain validation.
                x += random.nextBoolean() ? 3 : -3; z += random.nextBoolean() ? 3 : -3;
            }
            progressPosition = body.position(); progressTick = tick;
        }
        if (search == null && (tick >= replanAt || Math.abs(x - pathX) + Math.abs(z - pathZ) > 6 || y != pathY || pathIndex >= path.size())) {
            BlockPos current = body.blockPosition();
            BotPathfinder.Cell start = new BotPathfinder.Cell(current.getX(), current.getY(), current.getZ());
            search = y == Integer.MIN_VALUE ? new BotPathfinder(start, x, z) : new BotPathfinder(start, new BotPathfinder.Cell(x, y, z));
            pathX = x; pathY = y; pathZ = z; replanAt = tick + 40;
        }
        if (search != null) {
            search.advance((from, nx, nz) -> BotTerrain.step(body.serverLevel(), map, from, nx, nz), budget, 96);
            if (search.finished()) { path = search.path(); pathIndex = 0; search = null; }
        }
        while (pathIndex < path.size() && body.position().distanceToSqr(point(path.get(pathIndex))) < .55) pathIndex++;
        if (pathIndex >= path.size()) return;
        BotPathfinder.Cell next = path.get(pathIndex);
        if (!BotTerrain.standing(body.serverLevel(), map, next.x(), next.y(), next.z())) { path = List.of(); search = null; return; }
        Vec3 direction = point(next).subtract(body.position());
        if (Math.abs(direction.x) > 2.1 || Math.abs(direction.z) > 2.1) { path = List.of(); return; }
        if (!fighting) aim((float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90), 0);
        double radians = Math.toRadians(body.getYRot()), length = Math.max(.001, direction.horizontalDistance());
        float forward = (float) ((-Math.sin(radians) * direction.x + Math.cos(radians) * direction.z) / length);
        float strafe = (float) ((Math.cos(radians) * direction.x + Math.sin(radians) * direction.z) / length);
        actions().setForward(forward * .85F).setStrafing(strafe * .85F);
        if (next.y() > body.getY() + .35 && body.onGround()) body.jumpFromGround();
    }
    private static Vec3 point(BotPathfinder.Cell cell) { return new Vec3(cell.x() + .5, cell.y(), cell.z() + .5); }
    private static Vec3 chest(ServerPlayer player) { return player.position().add(0, player.getBbHeight() * .65, 0); }
}
