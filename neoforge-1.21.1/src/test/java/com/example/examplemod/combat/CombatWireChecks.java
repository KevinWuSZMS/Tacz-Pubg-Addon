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
        var snapshot=new CombatPayloads.Snapshot(session,51,2,1,3,false,0,0,2,rescue,List.of(new CombatPayloads.DownedPlayer(target,55,100,true),new CombatPayloads.DownedPlayer(self,0,0,false)));
        check(round(CombatPayloads.Snapshot.CODEC,snapshot).equals(snapshot),"Rescue and independent DBNO pool round trip");
        check(round(CombatPayloads.Snapshot.CODEC,CombatPayloads.Snapshot.EMPTY).equals(CombatPayloads.Snapshot.EMPTY),"End/logout clear snapshot has a distinct NO_SESSION");
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
