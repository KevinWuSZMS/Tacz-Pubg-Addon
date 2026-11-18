package com.example.examplemod.combat;

import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MovementState.Mode;
import com.example.examplemod.throwables.PubgThrowableEntity;
import com.example.examplemod.throwables.PubgThrowableItem;
import com.example.examplemod.throwables.ThrowableKind;
import com.example.examplemod.throwables.ThrowableService;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.bot.MatchBotService;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.GunDamageSourcePart;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.EntityKineticBullet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import me.xjqsh.lrtactical.api.item.ICustomItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Real DBNO, server-owned rescue and one credit per final elimination, scoped to a frozen roster. */
public final class CombatService {
    private static final ResourceLocation CRAWL = ResourceLocation.fromNamespaceAndPath("tacz_pubg","dbno_crawl");
    private static final Map<MinecraftServer, Map<UUID, Game>> GAMES = new WeakHashMap<>();
    private static final Map<DamageSource, Hit> GUN_HITS = new WeakHashMap<>();
    private static final Set<UUID> FINISHING = new HashSet<>();
    private record Hit(UUID attacker, String weapon, boolean headshot, String cause, String gunType) {}
    private static final Hit ENVIRONMENT = new Hit(null, "", false, "environment", "");
    private static final Hit BLUE_ZONE = new Hit(null, "", false, "blue_zone", "");
    private static final Hit FALL = new Hit(null, "", false, "fall", "");
    private static final Hit DROWN = new Hit(null, "", false, "drown", "");
    private static final class Damage { double amount; long tick; Damage(double amount,long tick) { this.amount=amount;this.tick=tick; } }
    private static final class Player {
        int kills,assists,knocks,downs; boolean eliminated, directDeath=true;
        Down down; final Map<UUID,Damage> contributors=new HashMap<>();
    }
    private static final class Down {
        final Hit hit; final long started; final float max; float hp; Vec3 position; int underwater;
        Down(Hit hit,long tick,float hp,Vec3 position) { this.hit=hit;this.started=tick;this.hp=this.max=hp;this.position=position; }
    }
    private static final class Reviving {
        final UUID rescuer,target; final Vec3 origin,targetOrigin; final int slot; final long started; final int duration;
        Reviving(ServerPlayer a,ServerPlayer b,long tick,int duration) { rescuer=a.getUUID();target=b.getUUID();origin=a.position();targetOrigin=b.position();slot=a.getInventory().selected;started=tick;this.duration=duration; }
    }
    private static final class Game {
        final MinecraftServer server;
        final MatchEngine engine; final Map<UUID,Player> players=new LinkedHashMap<>(); final Map<UUID,Reviving> rescues=new HashMap<>();
        final Map<UUID,MatchEngine.Participant> identities=new HashMap<>();
        final Map<UUID,Hit> forced=new HashMap<>();
        final Map<String,Integer> teams=new LinkedHashMap<>(); long sequence;
        Game(MinecraftServer server,MatchEngine engine) {
            this.server=server;
            this.engine=engine;
            for(var member:engine.roster()) {
                players.put(member.id(),new Player());
                identities.put(member.id(),member);
                if(!teams.containsKey(member.teamId())) {
                    int number=CombatTeams.number(member.teamId());
                    if(number<1 || teams.containsValue(number)) { number=1;while(teams.containsValue(number))number++; }
                    teams.put(member.teamId(),number);
                }
            }
        }
    }
    private CombatService() {}
    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST,CombatService::interceptDeath);
        bus.addListener(EventPriority.HIGHEST,CombatService::incoming);
        bus.addListener(EventPriority.LOWEST,CombatService::gun);
        bus.addListener(EventPriority.LOWEST,CombatService::damaged);
        bus.addListener(EventPriority.LOWEST,(ServerTickEvent.Post event)->tick(event.getServer()));
        bus.addListener(EventPriority.HIGHEST,(GunShootEvent e)-> { if(blocked(e.getShooter()))e.setCanceled(true); });
        bus.addListener(EventPriority.HIGHEST,(GunFireEvent e)-> { if(blocked(e.getShooter()))e.setCanceled(true); });
        bus.addListener(EventPriority.HIGHEST,(AttackEntityEvent e)-> { if(e.getEntity() instanceof ServerPlayer p && actionBlocked(p))e.setCanceled(true); });
        bus.addListener(EventPriority.HIGHEST,(LivingHealEvent e)-> { if(e.getEntity() instanceof ServerPlayer p && downed(p))e.setCanceled(true); });
        bus.addListener(EventPriority.HIGHEST,(PlayerInteractEvent.RightClickItem e)-> { if(e.getEntity() instanceof ServerPlayer p && actionBlocked(p))e.setCanceled(true); });
        bus.addListener(EventPriority.HIGHEST,(PlayerInteractEvent.RightClickBlock e)-> { if(e.getEntity() instanceof ServerPlayer p && actionBlocked(p))e.setCanceled(true); });
        bus.addListener(EventPriority.HIGHEST,(PlayerInteractEvent.EntityInteract e)-> { if(e.getEntity() instanceof ServerPlayer p && actionBlocked(p))e.setCanceled(true); });
        bus.addListener(EventPriority.HIGHEST,(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre e)-> { if(e.getPlayer() instanceof ServerPlayer p && actionBlocked(p))e.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE); });
        bus.addListener(EventPriority.HIGHEST,(PlayerEvent.PlayerLoggedOutEvent e)-> { if(e.getEntity() instanceof ServerPlayer p)leave(p,"disconnect"); });
        bus.addListener(EventPriority.HIGHEST,(PlayerEvent.PlayerChangedDimensionEvent e)-> { if(e.getEntity() instanceof ServerPlayer p)leave(p,"boundary"); });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e)-> { if(e.getEntity() instanceof ServerPlayer p) { restorePose(p);push(p); } });
        bus.addListener((PlayerEvent.Clone e)-> { if(e.getEntity() instanceof ServerPlayer p)restorePose(p); });
        bus.addListener((ServerStoppedEvent e)->GAMES.remove(e.getServer()));
    }
    private static double number(MinecraftServer server,String key) { return PubgSettingsService.server(server).number(key); }
    private static boolean enabled(MinecraftServer server,String key) { return PubgSettingsService.server(server).enabled(key); }
    private static Game game(ServerPlayer p,boolean create) {
        MatchEngine engine=MatchService.participantGame(p.server,p.getUUID());
        if(engine==null || engine.finished())return null;
        var games=GAMES.computeIfAbsent(p.server,ignored->new LinkedHashMap<>());
        return create?games.computeIfAbsent(engine.session().sessionId(),ignored->new Game(p.server,engine)):games.get(engine.session().sessionId());
    }
    public static boolean downed(ServerPlayer p) { Game g=game(p,false);return g!=null && g.players.get(p.getUUID())!=null && g.players.get(p.getUUID()).down!=null; }
    public static boolean rescuing(ServerPlayer p) { Game g=game(p,false);return g!=null && g.rescues.containsKey(p.getUUID()); }
    public static boolean downed(net.minecraft.world.entity.player.Player p) {
        return p instanceof ServerPlayer server?downed(server):p.level().isClientSide&&CombatPayloads.clientDowned(p.getUUID());
    }
    public static boolean rescuing(net.minecraft.world.entity.player.Player p) {
        var rescue=CombatPayloads.latest().rescue();
        return p instanceof ServerPlayer server?rescuing(server):p.level().isClientSide&&rescue.active()&&rescue.rescuer().equals(p.getUUID());
    }
    public static boolean actionBlocked(ServerPlayer p) { return downed(p)||rescuing(p); }
    public static boolean blocked(net.minecraft.world.entity.LivingEntity entity) {
        if(entity instanceof ServerPlayer p)return actionBlocked(p);
        var snapshot=CombatPayloads.latest();
        return entity.level().isClientSide&&(CombatPayloads.clientDowned(entity.getUUID())||snapshot.rescue().active()&&snapshot.rescue().rescuer().equals(entity.getUUID()));
    }
    private static String team(Game g,UUID id) { var member=g.identities.get(id);return member==null?"":member.teamId(); }
    private static boolean sameTeam(Game g,UUID a,UUID b) { return !team(g,a).isEmpty() && team(g,a).equals(team(g,b)); }
    private static CombatPayloads.Actor actor(Game g,UUID id) {
        if(id==null)return CombatPayloads.Actor.NONE;
        var member=g.identities.get(id);
        if(member==null)return CombatPayloads.Actor.NONE;
        int team=g.teams.get(member.teamId());return new CombatPayloads.Actor(id,member.name(),team,PubgSettingsService.server(gServer(g)).teamColor(team));
    }
    private static void gun(EntityHurtByGunEvent.Pre e) {
        if(!e.getLogicalSide().isServer() || e.isCanceled())return;
        String type=TimelessAPI.getCommonGunIndex(e.getGunId()).map(index->index.getType()).orElse("");
        Hit hit=new Hit(e.getAttacker()==null?null:e.getAttacker().getUUID(),e.getGunId().toString(),e.isHeadShot(),"gun",type);
        for(var part:GunDamageSourcePart.values()) { DamageSource source=e.getDamageSource(part);if(source!=null)GUN_HITS.put(source,hit); }
    }
    private static Hit hit(DamageSource source) {
        Hit gun = GUN_HITS.get(source);
        if (gun != null) return gun;
        var direct = source.getDirectEntity();
        var owner = source.getEntity();
        UUID attacker = owner == null ? null : owner.getUUID();
        // TaCZ blast damage bypasses the gun-hit event but retains the projectile's launch-time gun ID.
        if (direct instanceof EntityKineticBullet bullet) {
            if (com.example.examplemod.zones.bombing.BombingService.isBombing(bullet)) return ENVIRONMENT;
            var shooter = bullet.getOwner() instanceof LivingEntity living ? living : owner;
            if (shooter instanceof LivingEntity && bullet.getGunId() != null)
                return new Hit(shooter.getUUID(), bullet.getGunId().toString(), false, "gun_blast", "");
            if (shooter == null) return ENVIRONMENT;
        }
        if (direct instanceof PubgThrowableEntity projectile) {
            return new Hit(attacker == null ? projectile.throwerId() : attacker, "tacz_pubg:" + projectile.kind().id, false, "throwable", "");
        }
        if (direct instanceof me.xjqsh.lrtactical.entity.ThrowableItemEntity projectile) {
            return new Hit(attacker, itemId(projectile.getItem()), false, "throwable", "");
        }
        if (owner == null) {
            // The play zone and a player's blue-zone grenade use the same damage type.
            if (direct == null) {
                if (source.is(MatchService.BLUE_ZONE)) return BLUE_ZONE;
                if (source.is(DamageTypes.FALL)) return FALL;
                if (source.is(DamageTypes.DROWN)) return DROWN;
            }
            return ENVIRONMENT;
        }
        if (direct != null && direct != owner) {
            return new Hit(attacker, BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).toString(), false, "item", "");
        }
        if (direct instanceof LivingEntity living && direct == owner) {
            var stack = living.getMainHandItem();
            if (stack.isEmpty() && source.is(DamageTypes.PLAYER_ATTACK)) {
                return new Hit(attacker, "", false, "unarmed", "");
            }
            if (!stack.isEmpty()) return new Hit(attacker, itemId(stack), false, "item", "");
        }
        return new Hit(attacker, "", false, "environment", "");
    }
    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) return "";
        var item = stack.getItem();
        if (item instanceof IGun gun) return gun.getGunId(stack).toString();
        // A PUBG throwable can share LR's animation ID with a different explosive.
        if (!(item instanceof PubgThrowableItem) && item instanceof ICustomItem custom) return custom.getId(stack).toString();
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
    private static Hit participantHit(Game game, Hit hit) {
        if (hit == BLUE_ZONE || hit == FALL || hit == DROWN) return hit;
        return game.players.containsKey(hit.attacker) ? hit : ENVIRONMENT;
    }
    private static void damaged(LivingDamageEvent.Post e) {
        if(!(e.getEntity() instanceof ServerPlayer p)||e.getNewDamage()<=0||FINISHING.contains(p.getUUID()))return;
        Game g=game(p,true);if(g==null||!g.engine.alive(p.getUUID()))return;
        record(g,p.getUUID(),hit(e.getSource()),CombatRules.pubgDamage(e.getNewDamage(),p.getMaxHealth()));
        cancelRescues(g,p.getUUID());
    }
    private static void record(Game g,UUID victim,Hit hit,double amount) {
        if(hit.attacker==null || !g.players.containsKey(hit.attacker) || sameTeam(g,hit.attacker,victim) || hit.attacker.equals(victim))return;
        long now=g.engine.combatTicks();Player p=g.players.get(victim);Damage d=p.contributors.get(hit.attacker);
        long window=(long)number(gServer(g),"combat.assist_window_ticks");
        if(d==null||now-d.tick>window)p.contributors.put(hit.attacker,new Damage(amount,now));else {d.amount+=amount;d.tick=now;}
    }
    private static MinecraftServer gServer(Game g) {
        return g.server;
    }
    private static void interceptDeath(LivingDeathEvent e) {
        if(!(e.getEntity() instanceof ServerPlayer p)||FINISHING.contains(p.getUUID()))return;
        Game g=game(p,true);
        if(g==null||!g.engine.alive(p.getUUID())||g.engine.paused()
                ||e.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)
                ||g.engine.elapsedTicks()<g.engine.settings().preparationSeconds()*20L||g.players.get(p.getUUID()).down!=null)return;
        if(refreshDirectDeath(g,p))return;
        e.setCanceled(true);p.setAbsorptionAmount(0);p.stopRiding();p.stopFallFlying();p.stopUsingItem();
        ThrowableService.cancel(p);
        Player state=g.players.get(p.getUUID());state.downs++;state.down=new Down(participantHit(g,hit(e.getSource())),g.engine.combatTicks(),(float)number(p.server,"dbno.health"),p.position());
        syncDownedHealth(p,state.down);
        MatchBotService.suspend(p);
        setPose(p);cancelRescues(g,p.getUUID());
        if(state.down.hit.attacker!=null && g.players.containsKey(state.down.hit.attacker) && !sameTeam(g,state.down.hit.attacker,p.getUUID()))g.players.get(state.down.hit.attacker).knocks++;
        publish(g,p.server,"knock",state.down.hit.attacker,p.getUUID(),state.down.hit,state.down.hit.cause,null);
        pushAll(g,p.server);
    }
    private static boolean standing(MinecraftServer server,Game g,UUID id) {
        ServerPlayer p=server.getPlayerList().getPlayer(id);Player s=g.players.get(id);
        return p!=null&&p.isAlive()&&!p.isSpectator()&&g.engine.alive(id)&&s!=null&&!s.eliminated&&s.down==null;
    }
    /** One policy for both lethal damage and team wipes; future self-rescue equipment joins here. */
    private static boolean refreshDirectDeath(Game g,ServerPlayer p) {
        boolean teammateCanRescue=g.engine.roster().stream().anyMatch(m->!m.id().equals(p.getUUID())
                &&sameTeam(g,m.id(),p.getUUID())&&standing(p.server,g,m.id()));
        // No self-rescue item is registered yet. Keep its policy input independent of teammates.
        boolean selfRescueAvailable=false;
        return g.players.get(p.getUUID()).directDeath=CombatRules.directDeath(enabled(p.server,"dbno.enabled"),teammateCanRescue,selfRescueAvailable);
    }
    private static void incoming(LivingIncomingDamageEvent e) {
        if(!(e.getEntity() instanceof ServerPlayer p)||FINISHING.contains(p.getUUID()))return;
        if(e.getSource().getEntity() instanceof ServerPlayer attacker && actionBlocked(attacker)
                && e.getSource().getDirectEntity()==attacker){e.setCanceled(true);return;}
        Game g=game(p,false);if(g==null)return;
        if(g.engine.paused()||!p.server.tickRateManager().runsNormally()) {e.setCanceled(true);return;}
        Player state=g.players.get(p.getUUID());if(state==null||state.down==null)return;
        e.setCanceled(true);Down down=state.down;
        if(g.engine.combatTicks()-down.started<number(p.server,"dbno.protection_ticks"))return;
        Hit h=hit(e.getSource());double factor=1;
        if(h.attacker!=null && g.engine.blocksPlayerDamage(h.attacker,p.getUUID()))return;
        if(h.cause.equals("gun")) {
            if(h.gunType.equalsIgnoreCase("sniper")||h.weapon.contains("crossbow")) {
                var armor=p.getItemBySlot(h.headshot?net.minecraft.world.entity.EquipmentSlot.HEAD:net.minecraft.world.entity.EquipmentSlot.CHEST);
                float protection=armor.getItem() instanceof com.example.examplemod.armor.PubgArmorItem item?com.example.examplemod.armor.ArmorRules.multiplier(item.tier(),item.helmet(),armor.getDamageValue()>=armor.getMaxDamage()):1;
                factor=protection*number(p.server,h.weapon.contains("crossbow")?"dbno.crossbow_multiplier":"dbno.sniper_multiplier");
            } else factor=number(p.server,"dbno.damage_multiplier");
        } else if(e.getSource().getDirectEntity() instanceof PubgThrowableEntity t&&t.kind()==ThrowableKind.FRAG)factor=number(p.server,"dbno.frag_multiplier")*number(p.server,"dbno.damage_multiplier");
        double damage=CombatRules.pubgDamage(e.getAmount(),p.getMaxHealth())*factor;
        if(!Double.isFinite(damage)||damage<=0)return;
        record(g,p.getUUID(),h,damage);cancelRescues(g,p.getUUID());
        down.hp=(float)Math.max(0,down.hp-damage);
        // Incoming damage is handled by the DBNO pool, so vanilla never reaches its hurt feedback.
        p.hurtDuration=10;p.hurtTime=p.hurtDuration;
        p.serverLevel().broadcastDamageEvent(p,e.getSource());
        if(down.hp<=0)finish(p,g,participantHit(g,h));else {syncDownedHealth(p,down);pushAll(g,p.server);}
    }
    private static void syncDownedHealth(ServerPlayer p,Down down) {
        // Keep the native entity, nameplate mods and the independent DBNO bar at the same fraction.
        // Zero health is applied only by finish(), which must still see a living entity on entry.
        p.setHealth((float)Math.max(Float.MIN_NORMAL,p.getMaxHealth()*(double)down.hp/down.max));
    }
    /** Called once from MatchService's authoritative final-death path, before roster elimination. */
    public static UUID finalDeath(ServerPlayer p,MatchEngine engine,DamageSource source) {
        Game g=GAMES.computeIfAbsent(p.server,ignored->new LinkedHashMap<>()).computeIfAbsent(engine.session().sessionId(),ignored->new Game(p.server,engine));Player state=g.players.get(p.getUUID());
        if(state==null||state.eliminated)return null;
        Hit end=g.forced.remove(p.getUUID());if(end==null)end=participantHit(g,hit(source));
        Down down=state.down;
        var contributions=new ArrayList<CombatRules.Contributor>();
        for(var entry:state.contributors.entrySet())contributions.add(new CombatRules.Contributor(entry.getKey(),team(g,entry.getKey()),entry.getValue().amount,entry.getValue().tick));
        var credit=CombatRules.credit(p.getUUID(),team(g,p.getUUID()),down==null?null:down.hit.attacker,down==null?"":team(g,down.hit.attacker),
                end.attacker,team(g,end.attacker),contributions,g.engine.combatTicks(),(long)number(p.server,"combat.assist_window_ticks"),number(p.server,"combat.assist_min_damage"));
        state.eliminated=true;state.down=null;state.contributors.clear();cancelRescues(g,p.getUUID());restorePose(p);
        if(credit.killer()!=null&&g.players.containsKey(credit.killer()))g.players.get(credit.killer()).kills++;
        for(UUID assister:credit.assistants())if(g.players.containsKey(assister))g.players.get(assister).assists++;
        // A teammate finishing the enemy's down awards the downer a kill and the finisher an assist.
        // Friendly damage has no credit but the HUD still needs the responsible player's identity.
        UUID attacker = credit.killer();
        if (attacker == null) attacker = end.attacker;
        if (attacker == null && down != null && end.cause.equals("bleedout")) attacker = down.hit.attacker;
        publish(g,p.server,"elimination",attacker,p.getUUID(),end,end.cause,null,down!=null);
        for(UUID assister:credit.assistants()) {ServerPlayer recipient=p.server.getPlayerList().getPlayer(assister);if(recipient!=null)publish(g,p.server,"assist",assister,p.getUUID(),end,end.cause,recipient);}
        pushAll(g,p.server);return credit.killer();
    }
    private static void finish(ServerPlayer p,Game g,Hit hit) {
        if(!p.isAlive()||g.players.get(p.getUUID()).eliminated)return;
        g.forced.put(p.getUUID(),hit);FINISHING.add(p.getUUID());
        try {p.setHealth(0);p.die(p.damageSources().genericKill());}finally{FINISHING.remove(p.getUUID());}
    }
    private static void leave(ServerPlayer p,String cause) {
        Game g=game(p,false);
        if(g!=null) {
            cancelRescues(g,p.getUUID());
            if(downed(p)) {
                Hit end=new Hit(null,"",false,cause,"");
                if(cause.equals("boundary")) {
                    // This event is emitted after arrival. Do not drop the arena inventory in
                    // another dimension or create a death-spectator anchor in that destination.
                    g.forced.put(p.getUUID(),end);
                    finalDeath(p,g.engine,p.damageSources().genericKill());
                    MatchService.eliminate(p);
                } else finish(p,g,end);
            }
        }
        // An end/respawn can already have removed the active engine before this callback.
        restorePose(p);
    }
    private static void setPose(ServerPlayer p) {
        p.setForcedPose(Pose.SWIMMING);p.setPose(Pose.SWIMMING);p.setSprinting(false);
        var speed=p.getAttribute(Attributes.MOVEMENT_SPEED);
        double multiplier=number(p.server,p.isInWater()?"dbno.swim_multiplier":"dbno.crawl_multiplier")-1;
        if(speed!=null && (speed.getModifier(CRAWL)==null || Double.compare(speed.getModifier(CRAWL).amount(),multiplier)!=0)) {
            speed.removeModifier(CRAWL);speed.addTransientModifier(new AttributeModifier(CRAWL,multiplier,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
    private static void restorePose(ServerPlayer p) { if(p.getForcedPose()==Pose.SWIMMING)p.setForcedPose(null);var speed=p.getAttribute(Attributes.MOVEMENT_SPEED);if(speed!=null)speed.removeModifier(CRAWL); }
    public static void rescue(ServerPlayer rescuer,UUID target,boolean cancel) {
        Game g=game(rescuer,true);if(g==null)return;
        if(cancel||g.rescues.containsKey(rescuer.getUUID())) {g.rescues.remove(rescuer.getUUID());pushAll(g,rescuer.server);return;}
        if(g.engine.paused()||!rescuer.server.tickRateManager().runsNormally())return;
        ServerPlayer victim=rescuer.server.getPlayerList().getPlayer(target);
        if(victim==null||rescuer.isUsingItem()||rescuer.containerMenu!=rescuer.inventoryMenu||g.rescues.values().stream().anyMatch(r->r.target.equals(target)))return;
        var use=new Reviving(rescuer,victim,g.engine.combatTicks(),(int)number(rescuer.server,"rescue.ticks"));
        if(!validRescue(rescuer.server,g,use,false))return;
        g.rescues.put(rescuer.getUUID(),use);rescuer.stopUsingItem();ThrowableService.cancel(rescuer);MatchBotService.suspend(rescuer);
        MovementService.beginRescue(rescuer);pushAll(g,rescuer.server);
    }
    private static boolean validRescue(MinecraftServer server,Game g,Reviving r) {
        return validRescue(server,g,r,true);
    }
    private static boolean validRescue(MinecraftServer server,Game g,Reviving r,boolean started) {
        ServerPlayer a=server.getPlayerList().getPlayer(r.rescuer),b=server.getPlayerList().getPlayer(r.target);
        if(a==null||b==null||!standing(server,g,r.rescuer)||!g.engine.alive(r.target)||!b.isAlive()||g.players.get(r.target).eliminated||g.players.get(r.target).down==null
                ||a.level()!=b.level()||a.getInventory().selected!=r.slot||a.isUsingItem()||a.isSprinting()||a.isPassenger()||a.getAbilities().flying||a.containerMenu!=a.inventoryMenu)return false;
        if(started&&(MovementService.mode(a)!=Mode.CROUCH||a.getPose()!=Pose.CROUCHING))return false;
        if(!started&&!a.level().noCollision(a,a.getDimensions(Pose.CROUCHING).makeBoundingBox(a.position()).deflate(1.0E-7)))return false;
        double tolerance=number(server,"rescue.move_tolerance");
        Vec3 eye=a.getEyePosition(),aim=b.getBoundingBox().getCenter();
        boolean visible=a.level().clip(new ClipContext(eye,aim,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,a)).getType()==HitResult.Type.MISS;
        boolean aimed=a.getViewVector(1).normalize().dot(aim.subtract(eye).normalize())+1e-9>=number(server,"rescue.aim_dot");
        return aimed&&CombatRules.mayRescue(sameTeam(g,r.rescuer,r.target),true,true,a.isUnderWater()||b.isUnderWater(),a.distanceToSqr(b),number(server,"rescue.distance"),visible,
                Math.max(a.position().distanceToSqr(r.origin),b.position().distanceToSqr(r.targetOrigin)),tolerance);
    }
    private static void cancelRescues(Game g,UUID id) {g.rescues.entrySet().removeIf(e->e.getKey().equals(id)||e.getValue().target.equals(id));}
    private static double bleedSeconds(MinecraftServer server,int downs,boolean moving) {
        if(!moving)return number(server,"dbno.stationary_seconds");
        return number(server,"dbno.bleed_"+switch(CombatRules.bleedTier(downs)){case 1->"first";case 2->"second";case 3->"third";case 4->"fourth";case 5->"fifth";case 6->"sixth";case 7->"seventh";case 8->"eighth";default->"ninth";}+"_seconds");
    }
    private static void reconcileEliminations(Game g) {
        for(var entry:g.players.entrySet())if(!g.engine.alive(entry.getKey())&&!entry.getValue().eliminated) {
            ServerPlayer p=g.server.getPlayerList().getPlayer(entry.getKey());
            String cause=p==null?"disconnect":"boundary";
            if(p!=null) {g.forced.put(p.getUUID(),new Hit(null,"",false,cause,""));finalDeath(p,g.engine,p.damageSources().genericKill());}
            else {
                Player state=entry.getValue();boolean wasDowned=state.down!=null;
                state.eliminated=true;state.down=null;state.contributors.clear();cancelRescues(g,entry.getKey());
                publish(g,g.server,"elimination",null,entry.getKey(),new Hit(null,"",false,cause,""),cause,null,wasDowned);
            }
        }
    }
    /** Called synchronously by both administrative and natural match endings. */
    public static void ended(MinecraftServer server,UUID session) {
        var games=GAMES.get(server);if(games==null)return;
        Game g=games.get(session);if(g==null)return;
        // Preserve a last boundary/disconnect elimination before discarding the round.
        reconcileEliminations(g);
        g.rescues.clear();
        for(UUID id:g.players.keySet()) {
            ServerPlayer p=server.getPlayerList().getPlayer(id);
            if(p!=null){restorePose(p);PacketDistributor.sendToPlayer(p,CombatPayloads.Snapshot.EMPTY);}
        }
        games.remove(session);
    }
    private static void tick(MinecraftServer server) {
        for(MatchEngine engine:MatchService.activeGames(server))if(!engine.finished())GAMES.computeIfAbsent(server,ignored->new LinkedHashMap<>()).computeIfAbsent(engine.session().sessionId(),ignored->new Game(server,engine));
        var games=GAMES.get(server);if(games==null)return;
        for(Game g:new ArrayList<>(games.values())) {
            if(g.engine.finished()) {ended(server,g.engine.session().sessionId());continue;}
            reconcileEliminations(g);
            if(!server.tickRateManager().runsNormally()||g.engine.paused())continue;
            for(Reviving r:new ArrayList<>(g.rescues.values())) {
                if(!validRescue(server,g,r)) {g.rescues.remove(r.rescuer);continue;}
                if(g.engine.combatTicks()-r.started>=r.duration) {
                    ServerPlayer p=server.getPlayerList().getPlayer(r.target);Player s=g.players.get(r.target);
                    s.down=null;s.contributors.clear();p.setHealth((float)Math.max(.1,p.getMaxHealth()*number(server,"rescue.health_percent")/100));restorePose(p);g.rescues.remove(r.rescuer);
                    publish(g,server,"revive",r.rescuer,r.target,new Hit(r.rescuer,"",false,"rescue",""),"rescue",null);
                }
            }
            for(var entry:g.players.entrySet()) {
                Player state=entry.getValue();if(state.down==null||state.eliminated)continue;
                ServerPlayer p=server.getPlayerList().getPlayer(entry.getKey());if(p==null)continue;
                if(!g.engine.alive(entry.getKey())) {state.down=null;restorePose(p);continue;}
                if(refreshDirectDeath(g,p)){finish(p,g,new Hit(null,"",false,"team_wipe",""));continue;}
                Down down=state.down;setPose(p);p.stopUsingItem();
                Vec3 position=p.position();double horizontal=position.subtract(down.position).horizontalDistance();
                double limit=.215*number(server,p.isInWater()?"dbno.swim_multiplier":"dbno.crawl_multiplier")+.012;
                if(horizontal>limit*1.5) {Vec3 delta=position.subtract(down.position);Vec3 correct=down.position.add(delta.x*limit/horizontal,delta.y,delta.z*limit/horizontal);p.connection.teleport(correct.x,correct.y,correct.z,p.getYRot(),p.getXRot());position=correct;}
                boolean moving=horizontal>.005||p.isInWater();down.position=position;
                if(p.isUnderWater())down.underwater++;else down.underwater=0;
                boolean healing=enabled(server,"rescue.pause_bleeding")&&g.rescues.values().stream().anyMatch(r->r.target.equals(p.getUUID()));
                down.hp=CombatRules.bleed(down.hp,down.max,bleedSeconds(server,state.downs,moving),healing);
                if(down.underwater>=number(server,"dbno.underwater_seconds")*20)finish(p,g,DROWN);
                else if(down.hp<=0)finish(p,g,new Hit(null,"",false,"bleedout",""));
                else syncDownedHealth(p,down);
            }
            if(server.getTickCount()%2==0)pushAll(g,server);
        }
    }
    private static void publish(Game g,MinecraftServer server,String type,UUID attacker,UUID victim,Hit hit,String cause,ServerPlayer only) {
        publish(g,server,type,attacker,victim,hit,cause,only,false);
    }
    private static void publish(Game g,MinecraftServer server,String type,UUID attacker,UUID victim,Hit hit,String cause,ServerPlayer only,boolean wasDowned) {
        Player stats = g.players.get(attacker);
        var credited = actor(g, attacker);
        var feedAttacker = type.equals("elimination") && hit.attacker != null ? actor(g, hit.attacker) : credited;
        var event = new CombatPayloads.Event(g.engine.session().sessionId(), ++g.sequence, type, credited,
                actor(g, victim), hit.weapon, hit.headshot, cause, stats == null ? 0 : stats.kills,
                stats == null ? 0 : stats.assists, wasDowned, feedAttacker);
        var packet=new CombatPayloads.EventMessage(event);
        if(only!=null)PacketDistributor.sendToPlayer(only,packet);else for(UUID id:g.players.keySet()){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)PacketDistributor.sendToPlayer(p,packet);}
    }
    private static void pushAll(Game g,MinecraftServer server) {for(UUID id:g.players.keySet()){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)push(p);}}
    public static void push(ServerPlayer viewer) {
        Game g=game(viewer,false);if(g==null){PacketDistributor.sendToPlayer(viewer,CombatPayloads.Snapshot.EMPTY);return;}
        Player own=g.players.get(viewer.getUUID());var players=new ArrayList<CombatPayloads.DownedPlayer>();
        for(var entry:g.players.entrySet()) {Down d=entry.getValue().down;boolean friendly=sameTeam(g,entry.getKey(),viewer.getUUID());players.add(new CombatPayloads.DownedPlayer(entry.getKey(),d!=null&&friendly?Math.max(0,d.hp):0,d!=null&&friendly?d.max:0,d!=null));}
        CombatPayloads.Rescue rescue=CombatPayloads.Rescue.NONE;
        for(Reviving r:g.rescues.values())if(r.rescuer.equals(viewer.getUUID())||r.target.equals(viewer.getUUID()))rescue=new CombatPayloads.Rescue(r.target,actor(g,r.target).name(),r.rescuer,actor(g,r.rescuer).name(),(int)Math.clamp(g.engine.combatTicks()-r.started,0,r.duration),r.duration,true);
        Down down=own.down;
        PacketDistributor.sendToPlayer(viewer,new CombatPayloads.Snapshot(g.engine.session().sessionId(),++g.sequence,own.kills,own.assists,own.knocks,down!=null,down==null?0:Math.max(0,down.hp),down==null?0:down.max,own.downs,rescue,players,g.teams.get(team(g,viewer.getUUID()))));
    }
}
