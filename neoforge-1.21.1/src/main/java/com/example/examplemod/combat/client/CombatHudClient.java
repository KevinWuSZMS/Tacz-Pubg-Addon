package com.example.examplemod.combat.client;

import static com.example.examplemod.combat.client.CombatMessageRenderer.text;

import com.example.examplemod.combat.CombatPayloads;
import com.example.examplemod.combat.CombatPayloads.Event;
import com.example.examplemod.combat.CombatPayloads.Actor;
import com.example.examplemod.combat.CombatPayloads.Snapshot;
import com.example.examplemod.combat.CombatTimeline;
import com.example.examplemod.combat.CombatCounterNotice;
import com.example.examplemod.combat.TeamPalette;
import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.pubgsettings.PubgSettings;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import com.example.examplemod.zones.match.client.MatchHudClient;
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
    private static final CombatCounterNotice counterNotice=new CombatCounterNotice();
    private static double renderFraction;
    private CombatHudClient() {}
    public static Snapshot snapshot() { return current; }
    public static boolean hasStats(UUID session) { return session.equals(current.session())&&!CombatPayloads.NO_SESSION.equals(session); }
    public static void register(IEventBus modBus) {
        KillFeedWeaponIcons.register(modBus);
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
                    var settings = PubgSettingsService.client();
                    CombatMessageRenderer.draw(g, sample(false), false, r.x()+r.width()/2, r.y()+8, r.width(),
                            settings.color("combat.text_color"), settings.color("combat.kill_color"), 1);
                    centered(g,text("kills",2),r.x()+r.width()/2,r.y()+32,settings.color("combat.kill_color"),1.4F);
                });
        HudLayoutClient.registerPreview("combat_feed",(w,h)->new HudLayoutModel.Rect(Math.max(0,w-190),33,182,72),
                (g,r,s)-> {
                    g.pose().pushPose();
                    g.pose().translate(r.x(), r.y(), 0);
                    var settings = PubgSettingsService.client();
                    CombatFeedRow.draw(g, sample(true), 3, r.width(), 0, 14, settings, 1);
                    CombatFeedRow.draw(g, sample(false), 2, r.width(), 17, 14, settings, 1);
                    g.pose().popPose();
                });
    }
    private static Event sample(boolean knock) {
        var attacker = new Actor(new UUID(0, 1), "Player", 3, TeamPalette.color(3));
        var victim = new Actor(new UUID(0, 2), "Player", 6, TeamPalette.color(6));
        return new Event(CombatPayloads.NO_SESSION, 0, knock ? "knock" : "elimination", attacker, victim,
                "tacz:m4a1", knock, "gun", 2, 0);
    }
    private static void clear() { current=Snapshot.EMPTY; timeline.reset(CombatPayloads.NO_SESSION); counterNotice.reset(CombatPayloads.NO_SESSION,0,0); CombatPayloads.clearClient(); }
    private static void accept(Snapshot next) {
        if(!next.session().equals(current.session())) {
            timeline.reset(next.session()); counterNotice.reset(next.session(),next.kills(),next.assists());
        } else if(counterNotice.snapshot(next.session(),next.kills(),next.assists(),timeline.time())) timeline.markPersonal();
        current=next;
    }
    private static void event(Event value) {
        var mc=Minecraft.getInstance();
        if(mc.player==null||!value.session().equals(current.session())) return;
        boolean own=value.attacker().id().equals(mc.player.getUUID());
        if(value.type().equals("assist")&&!own) return;
        boolean friendlyVictim = value.friendlyFire() && value.victim().id().equals(mc.player.getUUID());
        boolean accepted = timeline.offer(value.session(), value.sequence(), value, own || friendlyVictim, !value.type().equals("assist"));
        if (accepted && own && !value.friendlyFire())
            counterNotice.event(value.session(),value.type(),value.kills(),value.assists(),timeline.time());
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
                Event event = entry.value();
                String textColor = event.friendlyFire() ? "combat.kill_color" : event.type().equals("assist")
                        ? "combat.assist_color" : i > 0 ? "combat.older_color" : "combat.text_color";
                int red = alpha(s.color("combat.kill_color"), a);
                boolean receiving = event.victim().id().equals(Minecraft.getInstance().player.getUUID());
                CombatMessageRenderer.draw(g, event, receiving, x + width/2, y + height - 25 - i*row + (int)slide,
                        width, alpha(s.color(textColor), a), red, (float)s.number("combat.message_scale"));
                if (i == 0 && event.friendlyFire()) {
                    centered(g, text("friendly_fire"), x + width/2, y + height - 10, red,
                            (float)s.number("combat.counter_scale"));
                }
            }
            if (!rows.isEmpty() && rows.get(0).value().friendlyFire()) return;
            double a=CombatTimeline.alpha(counterNotice.age(time()),s.number("combat.counter_seconds")*20,s.number("combat.fade_in_seconds")*20,s.number("combat.fade_out_seconds")*20);
            boolean assistCounter=counterNotice.assist();
            int amount=counterNotice.amount();
            if(amount>0&&a>0) centered(g,text(assistCounter?"assists":"kills",amount),x+width/2,y+height-10,
                    alpha(s.color(assistCounter?"combat.assist_color":"combat.kill_color"),a),(float)s.number("combat.counter_scale"));
        }
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
                CombatFeedRow.draw(g,event,current.viewerTeam(),width,top,row,s,a);
            }
            g.pose().popPose();
        }
    }
    static int alpha(int color,double amount) {return (color&0xFFFFFF)|((int)Math.clamp(Math.round((color>>>24)*amount),0,255)<<24);}
    private static void centered(GuiGraphics g,Component message,int x,int y,int color,float scale) {
        if((color>>>24)<4)return;var font=Minecraft.getInstance().font;
        String value=font.plainSubstrByWidth(message.getString(),Math.max(20,(int)((g.guiWidth()-20)/scale)));
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(scale,scale,1);g.drawString(font,value,-font.width(value)/2,0,color);g.pose().popPose();
    }
}
