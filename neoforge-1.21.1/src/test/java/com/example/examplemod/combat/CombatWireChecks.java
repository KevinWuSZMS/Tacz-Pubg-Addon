package com.example.examplemod.combat;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Actual wire records, remote identity, optional rescue and hostile-health privacy shape. */
public final class CombatWireChecks {
    private static int checks;
    public static void main(String[] args) {
        UUID session=new UUID(1,2),self=new UUID(3,4),target=new UUID(5,6);
        var actor=new CombatPayloads.Actor(self,"远处玩家",21,0xff36aabe);
        var victim=new CombatPayloads.Actor(target,"CarpetBot",23,0xffac633a);
        for(String type:List.of("knock","elimination","assist","revive")) {
            var event=new CombatPayloads.Event(session,50,type,actor,victim,"tacz:m4a1",true,"gun",2,1);
            check(round(CombatPayloads.Event.CODEC,event).equals(event),"Semantic event identity and headshot survive without loaded entities");
            var wrapper=new CombatPayloads.EventMessage(event);check(round(CombatPayloads.EventMessage.CODEC,wrapper).equals(wrapper),"Payload wrapper preserves String event.type()");
        }
        var rescue=new CombatPayloads.Rescue(target,"队友",self,"救援者",60,200,true);
        var snapshot=new CombatPayloads.Snapshot(session,51,2,1,3,false,0,0,2,rescue,List.of(new CombatPayloads.DownedPlayer(target,55,100,true),new CombatPayloads.DownedPlayer(self,0,0,false)),actor.team());
        check(round(CombatPayloads.Snapshot.CODEC,snapshot).equals(snapshot),"Rescue and independent DBNO pool round trip");
        check(round(CombatPayloads.Snapshot.CODEC,CombatPayloads.Snapshot.EMPTY).equals(CombatPayloads.Snapshot.EMPTY),"End/logout clear snapshot has a distinct NO_SESSION");
        var teammate = new CombatPayloads.Actor(target, "Teammate", actor.team(), actor.color());
        var gun = new CombatPayloads.Event(session, 52, "knock", actor, teammate, "tacz:m4a1", true, "gun", 0, 0);
        check(gun.friendlyFire() && gun.firearm() && !gun.bleedout(), "Team headshot knock retains attacker, gun and headshot");
        var bleed = new CombatPayloads.Event(session, 53, "elimination", actor, teammate, "", false, "bleedout", 0, 0);
        check(bleed.friendlyFire() && bleed.bleedout() && !bleed.firearm(), "Friendly bleedout has a downer without the original weapon");
        check(bleed.wasDowned() && !bleed.directElimination(), "Legacy bleedout construction cannot select the direct-kill icon");
        check(!gun.directElimination(), "A headshot knock never selects the direct-kill icon");
        for (String cause : List.of("gun", "gun_blast", "throwable", "unarmed", "item", "boundary", "disconnect")) {
            for (boolean wasDowned : List.of(false, true)) {
                for (var endVictim : List.of(victim, teammate)) {
                    var end = new CombatPayloads.Event(session, 54, "elimination", actor, endVictim,
                            cause.equals("gun") ? "tacz:ak47" : "", cause.equals("gun"), cause, 1, 0, wasDowned);
                    var decoded = round(CombatPayloads.Event.CODEC, end);
                    check(decoded.equals(end) && decoded.directElimination() == !wasDowned,
                            "Direct death and finishing retain distinct wire semantics for " + cause);
                    check(decoded.friendlyFire() == (endVictim == teammate),
                            "Previous DBNO state does not alter friendly-fire coloring");
                }
            }
        }
        var inconsistentBleed = new CombatPayloads.Event(session, 54, "elimination", actor, victim,
                "", false, "bleedout", 1, 0, false);
        check(!inconsistentBleed.directElimination(), "Bleedout cannot use the direct-kill icon even with an absent DBNO flag");
        for (String type : List.of("assist", "revive")) {
            check(!new CombatPayloads.Event(session, 54, type, actor, victim, "", false, "", 1, 0)
                    .directElimination(), "Non-elimination messages cannot use the direct-kill icon");
        }
        for (String cause : List.of("drown", "team_wipe", "disconnect", "boundary", "item")) {
            var end = new CombatPayloads.Event(session, 54, "elimination", actor, victim, "", false, cause, 1, 0);
            check(!end.bleedout() && !end.friendlyFire() && !end.firearm(), "Only loss of DBNO health over time counts as bleeding out");
        }
        var bash = new CombatPayloads.Event(session, 55, "elimination", actor, actor, "tacz:m4a1", false, "item", 0, 0);
        check(!bash.firearm() && !bash.friendlyFire(), "Gun carried as a melee weapon and self damage are distinguished");
        var blast = new CombatPayloads.Event(session, 56, "elimination", actor, victim, "tacz:rpg7", false, "gun_blast", 1, 0);
        check(blast.firearm() && !blast.headshot(), "TaCZ explosive rounds retain the original firearm");
        var finisher = new CombatPayloads.Actor(new UUID(7, 8), "Finisher", actor.team(), actor.color());
        var teamFinish = new CombatPayloads.Event(session, 57, "elimination", actor, victim, "tacz:m320", false,
                "gun_blast", 1, 0, true, finisher);
        check(round(CombatPayloads.Event.CODEC, teamFinish).equals(teamFinish),
                "A teammate's launcher and identity reach the feed independently of the credited downer");
        var punch = new CombatPayloads.Event(session, 56, "knock", actor, victim, "", false, "unarmed", 0, 0);
        check(round(CombatPayloads.Event.CODEC, punch).unarmed() && !bleed.unarmed(), "Only a bare-handed strike gets a fist");
        var grenade = new CombatPayloads.Event(session, 57, "elimination", actor, victim, "tacz_pubg:frag_grenade", false, "throwable", 1, 0);
        var received = round(CombatPayloads.Event.CODEC, grenade);
        check(received.throwable() && received.weaponId().equals("tacz_pubg:frag_grenade") && !received.firearm(),
                "Throwable identity stays separate from firearm and melee damage");
        check(round(CombatPayloads.RescueAction.CODEC,new CombatPayloads.RescueAction(target,true)).cancel(),"Cancel request is explicit rather than a client-granted resurrection");
        rejects(()->new CombatPayloads.DownedPlayer(target,Float.NaN,100,true));
        rejects(()->new CombatPayloads.DownedPlayer(target,101,100,true));
        rejects(()->new CombatPayloads.Rescue(target,"x",self,"y",201,200,true));
        rejects(()->new CombatPayloads.Actor(self,"x",101,0));
        rejects(()->new CombatPayloads.Event(session,0,"invented",actor,victim,"",false,"",0,0));
        System.out.println("CombatWireChecks: "+checks+" checks passed; no game launched.");
    }
    private static <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value) {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.NEOFORGE);
        try{codec.encode(buffer,value);T copy=codec.decode(buffer);check(buffer.readableBytes()==0,"No unconsumed wire data");return copy;}finally{buffer.release();}
    }
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Malformed combat record accepted");}
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
}
