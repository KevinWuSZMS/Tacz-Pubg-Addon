package com.example.examplemod.combat.client;

import com.example.examplemod.combat.CombatPayloads;
import com.example.examplemod.lootspawn.client.GroundLootClient;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Shared interaction begins/cancels rescue before held medicine, guns or ground loot. */
public final class CombatInputClient {
    private static boolean forced;
    public static void register(IEventBus ignored) {
        CombatPayloads.setClientSessionGate((session,connection)-> {
            var current=Minecraft.getInstance().getConnection();
            return current!=null&&current.getConnection()==connection&&(session.equals(CombatPayloads.NO_SESSION)
                    ||com.example.examplemod.vitals.client.MatchVitalsHud.activeSession(session));
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event)-> {
            var mc=Minecraft.getInstance();if(mc.player==null)return;
            boolean down=CombatPayloads.clientDowned(mc.player.getUUID());
            if(down){mc.player.setForcedPose(Pose.SWIMMING);mc.player.setSprinting(false);forced=true;}
            else if(forced){if(mc.player.getForcedPose()==Pose.SWIMMING)mc.player.setForcedPose(null);forced=false;}
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->{forced=false;CombatPayloads.clearClient();});
    }
    public static boolean blocked() {
        var mc=Minecraft.getInstance();var snapshot=CombatPayloads.latest();
        return mc.player!=null && (snapshot.downed() || snapshot.rescue().active()&&snapshot.rescue().rescuer().equals(mc.player.getUUID()));
    }
    private static Player target() {
        var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null||mc.screen!=null)return null;
        double distance=PubgSettingsService.client().number("rescue.distance");Vec3 eye=mc.player.getEyePosition(),end=eye.add(mc.player.getViewVector(1).scale(distance));
        Player best=null;double nearest=distance*distance+1;
        for(Player p:mc.level.players())if(p!=mc.player&&CombatPayloads.clientDowned(p.getUUID())) {
            var intersection=p.getBoundingBox().inflate(.25).clip(eye,end);if(intersection.isEmpty())continue;
            double d=eye.distanceToSqr(intersection.get());
            if(d<nearest&&mc.level.clip(new ClipContext(eye,intersection.get(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player)).getType()==HitResult.Type.MISS){best=p;nearest=d;}
        }
        return best;
    }
    /** True means this press belongs exclusively to combat interaction. Eligibility is rechecked server-side. */
    public static boolean trySharedAction() {
        var mc=Minecraft.getInstance();if(mc.player==null||mc.screen!=null)return false;
        var snapshot=CombatPayloads.latest();
        if(snapshot.downed())return true;
        if(snapshot.rescue().active()&&snapshot.rescue().rescuer().equals(mc.player.getUUID())) {
            PacketDistributor.sendToServer(new CombatPayloads.RescueAction(snapshot.rescue().target(),true));return true;
        }
        Player target=target();if(target==null)return false;
        PacketDistributor.sendToServer(new CombatPayloads.RescueAction(target.getUUID(),false));return true;
    }
    public static boolean captureKey(long window,int key,int scan,int action) {
        var mc=Minecraft.getInstance();if(mc.player==null||window!=mc.getWindow().getWindow()||mc.screen!=null)return false;
        KeyMapping interact=GroundLootClient.pickupKey();
        boolean cancel=false;
        for(KeyMapping mapping:mc.options.keyMappings)if(mapping.getName().equals("key.tacz_pubg.cancel_use")&&mapping.matches(key,scan))cancel=true;
        if(cancel&&CombatPayloads.latest().rescue().active()) {
            if(action==GLFW.GLFW_PRESS)PacketDistributor.sendToServer(new CombatPayloads.RescueAction(CombatPayloads.latest().rescue().target(),true));return true;
        }
        if(!interact.matches(key,scan)||!blocked()&&target()==null)return false;
        if(action==GLFW.GLFW_RELEASE)KeyMapping.set(com.mojang.blaze3d.platform.InputConstants.getKey(key,scan),false);
        interact.setDown(action!=GLFW.GLFW_RELEASE);while(interact.consumeClick()){}
        if(action==GLFW.GLFW_PRESS)trySharedAction();return true;
    }
    private CombatInputClient() {}
}
