package com.example.examplemod.combat.client;

import com.example.examplemod.combat.CombatPayloads;
import com.example.examplemod.combat.CombatPayloads.Event;
import com.example.examplemod.combat.CombatPayloads.Actor;
import com.example.examplemod.combat.CombatPayloads.Snapshot;
import com.example.examplemod.combat.CombatTimeline;
import com.example.examplemod.combat.TeamPalette;
import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.pubgsettings.PubgSettings;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import com.example.examplemod.vitals.client.WeaponStackRenderer;
import com.example.examplemod.zones.match.client.MatchHudClient;
import com.tacz.guns.api.TimelessAPI;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Match totals and semantic messages, independent of client actor entity loading. */
public final class CombatHudClient {
    private static final CombatTimeline<Event> timeline=new CombatTimeline<>();
    private static Snapshot current=Snapshot.EMPTY;
    private static boolean assistCounter;
    private static double renderFraction;
    private CombatHudClient() {}
    public static Snapshot snapshot() { return current; }
    public static boolean hasStats(UUID session) { return session.equals(current.session())&&!CombatPayloads.NO_SESSION.equals(session); }
    public static void register(IEventBus modBus) {
        CombatPayloads.setClientHandlers(CombatHudClient::accept,CombatHudClient::event);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e)->clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->clear());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)-> {
            var status=MatchHudClient.currentStatus();
            if(Minecraft.getInstance().level!=null) timeline.advance(Minecraft.getInstance().isPaused()||status!=null&&status.paused());
        });
        modBus.addListener((RegisterGuiLayersEvent e)->e.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg","combat_hud"),(g,d)->render(g)));
        HudLayoutClient.registerPreview("combat_messages",(w,h)->new HudLayoutModel.Rect(Math.max(0,w/2-140),(int)(h*.72)-48,280,70),
                (g,r,s)-> {
                    centered(g,text("knock","AKM","Player"),r.x()+r.width()/2,r.y()+8,0xFFF1F1EE,1);
                    centered(g,text("kills",2),r.x()+r.width()/2,r.y()+32,0xFFFF6B3D,1.4F);
                });
        HudLayoutClient.registerPreview("combat_feed",(w,h)->new HudLayoutModel.Rect(Math.max(0,w-190),33,182,72),
                (g,r,s)-> {for(int i=0;i<3;i++) {
                    g.fill(r.x(),r.y()+i*18,r.x()+r.width(),r.y()+i*18+15,0x66212427);
                    g.drawString(Minecraft.getInstance().font,"21 Player  >  Player 23",r.x()+4,r.y()+i*18+3,0xFF57BDD8);
                }});
    }
    private static void clear() { current=Snapshot.EMPTY; timeline.reset(CombatPayloads.NO_SESSION); assistCounter=false; CombatPayloads.clearClient(); }
    private static void accept(Snapshot next) {
        if(!next.session().equals(current.session())) { timeline.reset(next.session()); assistCounter=false; }
        if(next.kills()>current.kills()||next.assists()>current.assists()) {
            assistCounter=next.assists()>current.assists()&&next.kills()==current.kills(); timeline.markPersonal();
        }
        current=next;
    }
    private static void event(Event value) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||!value.session().equals(current.session())) return;
        boolean own=value.attacker().id().equals(mc.player.getUUID());
        if(value.type().equals("assist")&&!own) return;
        if(timeline.offer(value.session(),value.sequence(),value,own,!value.type().equals("assist"))&&own) assistCounter=value.type().equals("assist");
    }
    private static void render(GuiGraphics g) {
        var mc=Minecraft.getInstance(); var status=MatchHudClient.currentStatus();
        if(mc.player==null||mc.level==null||mc.options.hideGui||HudLayoutClient.editing()||status==null
                ||!hasStats(status.sessionId())||!MatchVitalsHud.activeSession(status.sessionId())) return;
        renderFraction=status.paused()||mc.isPaused()?0:mc.getTimer().getGameTimeDeltaPartialTick(false);
        var s=PubgSettingsService.client(); counters(g,status.playersAlive(),s); center(g,s); feed(g,s);
    }
    private static double time() { return timeline.time()+renderFraction; }
    private static void counters(GuiGraphics g,int alive,PubgSettings s) {
        var font=Minecraft.getInstance().font;
        double a=CombatTimeline.alpha(timeline.personalAge()+renderFraction,s.number("combat.counter_seconds")*20,0,s.number("combat.fade_out_seconds")*20);
        float scale=(float)s.number("combat.top_number_scale");
        String[] numbers={Integer.toString(current.kills()),Integer.toString(current.assists()),Integer.toString(alive)};
        String[] labels={text("kill_label").getString(),text("assist_label").getString(),Component.translatable("screen.tacz_pubg.pubg_hud.alive_label").getString()};
        int start=a>=.02?0:2,height=s.integer("combat.top_height"),width=0;
        int[] widths=new int[3];for(int i=0;i<3;i++)widths[i]=Math.round(font.width(numbers[i])*scale)+font.width(labels[i])+13;
        for(int i=start;i<3;i++)width+=widths[i]+3;width-=3;
        int x=g.guiWidth()-width-s.integer("combat.right_margin"),y=7;
        var map=com.example.examplemod.zones.client.xaero.XaeroHudAnchor.minimap();
        if(map!=null&&map.overlaps(x,y,x+width,y+height))x=Math.max(0,map.x()-width-4);
        try(var scope=HudLayoutClient.apply(g,"alive_counter",x,y,width,height)) {
            int at=x;for(int i=start;i<3;i++) {
                double opacity=i==2?1:a;g.fill(at,y,at+widths[i],y+height,alpha(s.color("hud.panel_color"),opacity));
                g.pose().pushPose();g.pose().translate(at+4,y+(height-font.lineHeight*scale)/2,0);g.pose().scale(scale,scale,1);
                g.drawString(font,numbers[i],0,0,alpha(s.color("combat.text_color"),opacity));g.pose().popPose();
                g.drawString(font,labels[i],at+widths[i]-font.width(labels[i])-4,y+(height-font.lineHeight)/2,alpha(s.color("combat.text_color"),opacity));
                at+=widths[i]+3;
            }
        }
    }
    private static void center(GuiGraphics g,PubgSettings s) {
        double lifetime=s.number("combat.message_seconds")*20;
        var rows=timeline.personal(s.integer("combat.message_rows"),lifetime);
        int row=s.integer("combat.center_row_height"),width=Math.min(g.guiWidth()-16,320),height=row*s.integer("combat.message_rows")+25;
        int x=(g.guiWidth()-width)/2,y=(int)(g.guiHeight()*s.number("combat.center_y"))-height;
        try(var scope=HudLayoutClient.apply(g,"combat_messages",x,y,width,height)) {
            for(int i=0;i<rows.size();i++) {
                var entry=rows.get(i);double age=time()-entry.born();
                double a=CombatTimeline.alpha(age,lifetime,s.number("combat.fade_in_seconds")*20,s.number("combat.fade_out_seconds")*20);
                double slide=i==0?0:row*(1-CombatTimeline.smooth(time()-rows.get(0).born(),s.number("combat.move_seconds")*20));
                centered(g,message(entry.value()),x+width/2,y+height-25-i*row+(int)slide,
                        alpha(s.color(entry.value().type().equals("assist")?"combat.assist_color":i>0?"combat.older_color":"combat.text_color"),a),(float)s.number("combat.message_scale"));
            }
            double a=CombatTimeline.alpha(timeline.personalAge()+renderFraction,s.number("combat.counter_seconds")*20,s.number("combat.fade_in_seconds")*20,s.number("combat.fade_out_seconds")*20);
            int amount=assistCounter?current.assists():current.kills();
            if(amount>0&&a>0) centered(g,text(assistCounter?"assists":"kills",amount),x+width/2,y+height-10,
                    alpha(s.color(assistCounter?"combat.assist_color":"combat.kill_color"),a),(float)s.number("combat.counter_scale"));
        }
    }
    private static Component message(Event e) {
        if(e.type().equals("revive")) return text("revive",e.victim().name());
        if(e.type().equals("assist")) return text("assist",e.victim().name());
        return text(e.type().equals("knock")?"knock":e.headshot()?"headshot":"elimination",weaponName(e.weaponId()),e.victim().name());
    }
    private static String weaponName(String key) {
        var id=ResourceLocation.tryParse(key);
        if(id!=null) {
            var gun=TimelessAPI.getClientGunIndex(id);
            if(gun.isPresent()) return Component.translatable(gun.get().getName()).getString();
            var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id);
            if(item.isPresent()) return item.get().getDescription().getString();
        }
        return text("environment").getString();
    }
    private static void feed(GuiGraphics g,PubgSettings s) {
        double lifetime=s.number("combat.feed_seconds")*20;
        var rows=timeline.feed(s.integer("combat.feed_rows"),lifetime);
        float scale=(float)s.number("combat.feed_scale");int width=s.integer("combat.feed_width"),row=s.integer("combat.feed_row_height");
        int x=g.guiWidth()-Math.round(width*scale)-s.integer("combat.right_margin"),y=s.integer("combat.feed_top");
        var map=com.example.examplemod.zones.client.xaero.XaeroHudAnchor.minimap();
        if(map!=null&&map.overlaps(x,y,g.guiWidth(),y+Math.round(row*s.integer("combat.feed_rows")*scale)))
            x=Math.max(0,map.x()-Math.round(width*scale)-4);
        try(var scope=HudLayoutClient.apply(g,"combat_feed",x,y,Math.round(width*scale),Math.round(row*s.integer("combat.feed_rows")*scale))) {
            g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(scale,scale,1);
            for(int i=0;i<rows.size();i++) {
                var entry=rows.get(i);var event=entry.value();double age=time()-entry.born();
                double a=CombatTimeline.alpha(age,lifetime,s.number("combat.fade_in_seconds")*20,s.number("combat.fade_out_seconds")*20);
                if(a<.02)continue;
                int top=i*row-(i==0?0:(int)(row*(1-CombatTimeline.smooth(time()-rows.get(0).born(),s.number("combat.move_seconds")*20))));
                g.fill(0,top,width,top+row-1,alpha(s.color("hud.panel_color"),a));
                int tint=event.attacker().id().equals(Minecraft.getInstance().player.getUUID())?s.color("combat.own_color"):s.color("combat.text_color");
                int nameWidth=Math.max(20,(width-79)/2);actor(g,event.attacker(),2,top+2,nameWidth,a,tint);
                int middle=nameWidth+4;var gun=ResourceLocation.tryParse(event.weaponId());
                boolean rendered=gun!=null&&WeaponStackRenderer.drawGunSilhouette(g,gun,middle,top+1,38,row-3,alpha(tint,a));
                if(!rendered) {
                    var item=gun==null?java.util.Optional.<net.minecraft.world.item.Item>empty():net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(gun);
                    if(item.isPresent()) {
                        g.pose().pushPose();g.pose().translate(middle+12,top,0);g.pose().scale(.65F,.65F,1);
                        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1,1,1,(float)a);
                        g.renderItem(item.get().getDefaultInstance(),0,0);
                        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1,1,1,1);g.pose().popPose();
                    } else {g.fill(middle+12,top+5,middle+28,top+6,alpha(tint,a));}
                }
                glyph(g,middle+43,top+4,event.type(),alpha(tint,a));
                if(event.headshot()) {g.fill(middle+55,top+3,middle+59,top+7,alpha(tint,a));g.fill(middle+56,top+2,middle+58,top+8,alpha(tint,a));}
                actor(g,event.victim(),middle+64,top+2,nameWidth,a,tint);
            }
            g.pose().popPose();
        }
    }
    private static void actor(GuiGraphics g,Actor actor,int x,int y,int width,double opacity,int textColor) {
        var font=Minecraft.getInstance().font;String number=actor.team()>0?Integer.toString(actor.team()):"–";
        int badge=Math.max(10,font.width(number)+4);g.fill(x,y,x+badge,y+9,alpha(actor.color(),opacity));
        g.drawString(font,number,x+2,y,alpha(TeamPalette.contrastText(actor.color()),opacity),false);
        String name=font.plainSubstrByWidth(actor.name(),Math.max(4,width-badge-3));g.drawString(font,name,x+badge+3,y,alpha(textColor,opacity));
    }
    private static void glyph(GuiGraphics g,int x,int y,String type,int color) {
        if(type.equals("revive")) {g.fill(x+3,y-1,x+5,y+7,color);g.fill(x,y+2,x+8,y+4,color);}
        else if(type.equals("knock")) {g.fill(x,y+1,x+2,y+3,color);g.fill(x+2,y+3,x+7,y+5,color);g.fill(x+5,y+5,x+9,y+6,color);}
        else for(int i=0;i<7;i++) {g.fill(x+i,y+i,x+i+2,y+i+1,color);g.fill(x+6-i,y+i,x+8-i,y+i+1,color);}
    }
    private static Component text(String key,Object... args) {return Component.translatable("screen.tacz_pubg.combat."+key,args);}
    private static int alpha(int color,double amount) {return (color&0xFFFFFF)|((int)Math.clamp(Math.round((color>>>24)*amount),0,255)<<24);}
    private static void centered(GuiGraphics g,Component message,int x,int y,int color,float scale) {
        if((color>>>24)<4)return;var font=Minecraft.getInstance().font;
        String value=font.plainSubstrByWidth(message.getString(),Math.max(20,(int)((g.guiWidth()-20)/scale)));
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(scale,scale,1);g.drawString(font,value,-font.width(value)/2,0,color);g.pose().popPose();
    }
}
