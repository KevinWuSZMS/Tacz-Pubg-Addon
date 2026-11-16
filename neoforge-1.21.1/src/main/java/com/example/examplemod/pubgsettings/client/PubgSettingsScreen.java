package com.example.examplemod.pubgsettings.client;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.pubgsettings.PubgSettings;
import com.example.examplemod.pubgsettings.PubgSettingsPayloads;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Administrator editor: raw drafts survive invalid input, category changes and conflicts. */
public final class PubgSettingsScreen extends Screen implements ConfigUi.DraftPage {
    private static final List<String> GROUPS = List.of("all","hud","medicine","dbno","rescue","combat","lean","map","team");
    private final Screen parent;
    private final Map<String,String> raw = new LinkedHashMap<>();
    private PubgSettings baseline = PubgSettingsService.client();
    private String group = "all", search = "", status = "loading", detail = "";
    private long revision = -1;
    private boolean requested, loaded, saving;
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS = new java.util.concurrent.atomic.AtomicLong();
    private long loadRequest, saveRequest;
    private Map<String,String> submittedRaw = Map.of();
    private PubgSettings submitted;
    private int waitingTicks;
    private double scroll;
    private FieldList list;
    public PubgSettingsScreen(Screen parent) { super(text("title")); this.parent = parent; fill(baseline); }
    private static Component text(String name,Object... args) { return Component.translatable("screen.tacz_pubg.battle_settings."+name,args); }
    private static Component label(PubgSettings.Field field) {
        return Component.translatableWithFallback("screen.tacz_pubg.battle_settings.field."+field.key(),field.key());
    }
    private static String format(PubgSettings.Field field,double value) {
        return field.kind() == PubgSettings.Kind.COLOR ? String.format(Locale.ROOT,"#%08X",(long)value)
                : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    private void fill(PubgSettings value) {
        raw.clear(); for (var field : PubgSettings.fields()) raw.put(field.key(),format(field,value.number(field.key())));
    }
    @Override protected void init() {
        int filterWidth = Math.max(60,width-148);
        var filter = addRenderableWidget(new EditBox(font,10,34,filterWidth,20,text("search")));
        filter.setHint(text("search")); filter.setValue(search); filter.setMaxLength(100);
        filter.setResponder(value -> { search=value; if (list!=null) { list.refill(); list.setScrollAmount(0); } });
        addRenderableWidget(Button.builder(text("group."+group),b -> {
            group=GROUPS.get((GROUPS.indexOf(group)+1)%GROUPS.size()); scroll=0; rebuildWidgets();
        }).bounds(width-130,34,120,20).build()).active=!saving;
        list=addRenderableWidget(new FieldList()); list.refill(); list.setScrollAmount(scroll);
        int buttonWidth=Math.max(38,(width-38)/4);
        addRenderableWidget(Button.builder(text("save"),b -> saveDraft()).bounds(10,height-27,buttonWidth,20).build()).active=loaded&&!saving;
        addRenderableWidget(Button.builder(text("restore"),b -> {
            for (var field : PubgSettings.fields()) if (group.equals("all")||field.group().equals(group))
                raw.put(field.key(),format(field,field.initial()));
            status="draft"; detail=""; rebuildWidgets();
        }).bounds(16+buttonWidth,height-27,buttonWidth,20).build()).active=loaded&&!saving;
        addRenderableWidget(Button.builder(text("reload"),b -> confirmReload()).bounds(22+buttonWidth*2,height-27,buttonWidth,20).build()).active=!saving;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b -> onClose()).bounds(28+buttonWidth*3,height-27,buttonWidth,20).build()).active=!saving;
        if (!requested) { requested=true; reload(); }
    }
    private static long nextRequest() { return REQUEST_IDS.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1 : previous + 1); }
    private void reload() {
        loaded=false; saving=false; waitingTicks=0; status="loading"; detail="";
        saveRequest=0; submitted=null; submittedRaw=Map.of(); loadRequest=nextRequest();
        PacketDistributor.sendToServer(new PubgSettingsPayloads.Request(loadRequest));
    }
    private void confirmReload() {
        if (!hasUnsavedChanges()) { reload(); return; }
        minecraft.setScreen(new ConfirmScreen(yes -> { minecraft.setScreen(this); if (yes) reload(); },text("discard"),text("discard_hint")));
    }
    public void accept(PubgSettingsPayloads.State next) {
        boolean loadReply=loadRequest>0 && next.requestId()==loadRequest;
        boolean saveReply=saveRequest>0 && next.requestId()==saveRequest;
        if (next.requestId()>0 && !loadReply && !saveReply) return;
        // A broadcast never satisfies an explicit load, or changes an in-flight/dirty draft.
        if (next.requestId()==0 && (!loaded || saving || hasUnsavedChanges())) return;
        if (next.status()==3||next.status()==2) {
            if (saveReply) saveRequest=0;
            if (loadReply) loadRequest=0;
            saving=false; status=next.status()==3?"denied":"conflict"; rebuildWidgets(); return;
        }
        if (saveReply && next.status()==1) {
            boolean editedSinceSubmit=!raw.equals(submittedRaw);
            saving=false; saveRequest=0; loaded=true;
            if (editedSinceSubmit) {
                // The accepted transaction becomes the baseline, while edits made after
                // its timeout remain visible. Keeping its revision also preserves normal
                // optimistic conflict detection if another admin changed it afterwards.
                baseline=submitted; revision=next.revision(); status="saved_draft";
            } else {
                baseline=PubgSettingsClient.revision()>next.revision()?PubgSettingsService.client():next.settings();
                revision=Math.max(next.revision(),PubgSettingsClient.revision()); fill(baseline); status="saved";
            }
            submitted=null; submittedRaw=Map.of(); detail=""; rebuildWidgets(); return;
        }
        if (loadReply || next.requestId()==0 && !hasUnsavedChanges()) {
            baseline=PubgSettingsClient.revision()>next.revision()?PubgSettingsService.client():next.settings();
            revision=Math.max(next.revision(),PubgSettingsClient.revision()); loadRequest=0;
            loaded=true; saving=false; fill(baseline); status="hint"; detail=""; rebuildWidgets();
        }
    }
    private void saveDraft() {
        if (!loaded||saving) return;
        try {
            PubgSettings next=parseDraft(raw);
            saving=true; waitingTicks=0; status="saving"; detail="";
            submitted=next; submittedRaw=Map.copyOf(raw); saveRequest=nextRequest();
            rebuildWidgets(); PacketDistributor.sendToServer(new PubgSettingsPayloads.Save(saveRequest,revision,next));
        } catch (IllegalArgumentException invalid) {
            status="invalid"; detail=invalid.getMessage()==null?"":invalid.getMessage();
        }
    }
    public static PubgSettings parseDraft(Map<String,String> raw) {
        var values=new LinkedHashMap<String,Double>();
        for (var field : PubgSettings.fields()) {
            String input=raw.get(field.key());
            if (input==null||input.trim().isEmpty()) throw new IllegalArgumentException(field.key());
            double value;
            try {
                if (field.kind()==PubgSettings.Kind.COLOR) {
                    String hex=input.trim().replaceFirst("^(#|0[xX])","");
                    if (!hex.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) throw new NumberFormatException();
                    value=Long.parseLong(hex,16); if (hex.length()==6) value+=0xFF000000L;
                } else value=Double.parseDouble(input.trim());
                field.validate(value);
            } catch (IllegalArgumentException bad) { throw new IllegalArgumentException(field.key(),bad); }
            values.put(field.key(),value);
        }
        return new PubgSettings(values);
    }
    @Override public boolean hasUnsavedChanges() {
        for (var field : PubgSettings.fields()) if (!format(field,baseline.number(field.key())).equals(raw.get(field.key()))) return true;
        return false;
    }
    @Override public void tick() {
        super.tick();
        if ((saving || !loaded&&loadRequest>0) && ++waitingTicks==201) { saving=false; status="timeout"; rebuildWidgets(); }
    }
    @Override public void onClose() {
        if (saving) return;
        if (!hasUnsavedChanges()) minecraft.setScreen(parent);
        else minecraft.setScreen(new ConfirmScreen(yes -> minecraft.setScreen(yes?parent:this),text("discard"),text("discard_hint")));
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        super.render(g,mx,my,partial);
        g.drawCenteredString(font,title,width/2,12,-1);
        Component message=detail.isEmpty()?text(status):text(status,detail);
        g.drawString(font,font.plainSubstrByWidth(message.getString(),Math.max(1,width-20)),10,height-43,
                List.of("invalid","conflict","denied","timeout").contains(status)?0xFFFF8B80:0xFFD4DDC7);
    }
    private final class FieldList extends ContainerObjectSelectionList<FieldRow> {
        FieldList() { super(PubgSettingsScreen.this.minecraft,Math.max(40,PubgSettingsScreen.this.width-20),
                Math.max(25,PubgSettingsScreen.this.height-112),60,39); setX(10); centerListVertically=false; }
        void refill() {
            setFocused(null); clearEntries(); String query=search.toLowerCase(Locale.ROOT);
            for (var field : PubgSettings.fields()) if ((group.equals("all")||field.group().equals(group))
                    && (field.key()+" "+label(field).getString()).toLowerCase(Locale.ROOT).contains(query)) addEntry(new FieldRow(field));
        }
        @Override public int getRowWidth() { return getWidth()-16; }
        @Override protected int getScrollbarPosition() { return getX()+getWidth()-6; }
        @Override public void setScrollAmount(double value) { super.setScrollAmount(value); scroll=getScrollAmount(); }
    }
    private final class FieldRow extends ContainerObjectSelectionList.Entry<FieldRow> {
        final PubgSettings.Field field;
        final AbstractWidget widget;
        FieldRow(PubgSettings.Field field) {
            this.field=field;
            if (field.kind()==PubgSettings.Kind.BOOLEAN) widget=Button.builder(onOff(),b -> {
                raw.put(field.key(),raw.get(field.key()).equals("1")?"0":"1"); b.setMessage(onOff());
            }).bounds(0,0,100,20).build();
            else {
                var box=new EditBox(font,0,0,100,20,label(field)); box.setMaxLength(30);
                box.setValue(raw.get(field.key())); box.setResponder(value -> raw.put(field.key(),value)); widget=box;
            }
            widget.active=loaded&&!saving;
            widget.setTooltip(Tooltip.create(field.key().equals("map.entity_recording_prompt_side_length")?text("entity_recording_threshold_hint")
                    :field.key().equals("lean.body_penetration_tolerance")?text("range",format(field,field.minimum()),format(field,field.maximum()),format(field,field.initial()))
                            .copy().append("\n").append(text("lean_body_tolerance_hint"))
                    :field.kind()==PubgSettings.Kind.COLOR?text("color_hint")
                    :text("range",format(field,field.minimum()),format(field,field.maximum()),format(field,field.initial()))));
        }
        private Component onOff() { return text(raw.get(field.key()).equals("1")?"on":"off"); }
        @Override public void render(GuiGraphics g,int index,int y,int x,int rowWidth,int rowHeight,int mx,int my,boolean hovered,float partial) {
            int input=Math.min(100,Math.max(50,rowWidth/3));
            g.drawString(font,font.plainSubstrByWidth(label(field).getString(),Math.max(10,rowWidth-input-12)),x+2,y+4,-1);
            g.drawString(font,font.plainSubstrByWidth(text("default",format(field,field.initial())).getString(),Math.max(10,rowWidth-input-12)),x+2,y+18,0xFF9CA7AF);
            widget.setRectangle(input,20,x+rowWidth-input,y+5); widget.render(g,mx,my,partial);
        }
        @Override public List<? extends GuiEventListener> children() { return List.of(widget); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(widget); }
    }
}
