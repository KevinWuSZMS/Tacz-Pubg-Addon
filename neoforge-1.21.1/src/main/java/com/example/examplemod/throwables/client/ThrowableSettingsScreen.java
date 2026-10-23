package com.example.examplemod.throwables.client;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.throwables.ThrowableSettingsModel;
import com.example.examplemod.throwables.ThrowableSettingsPayloads;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Per-item, recursively enumerated property editor. Every native LR subtype keeps its fields. */
public final class ThrowableSettingsScreen extends Screen {
    private record Field(String path, JsonElement original, String raw) {}
    private record Row(AbstractWidget widget, int logicalY) {}
    private final Screen parent;
    private JsonObject saved = new JsonObject(), draft = new JsonObject(), catalog = new JsonObject();
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private List<String> ids = List.of();
    private String selected = "", query = "", error = "";
    private long revision = -1;
    private UUID request;
    private boolean started, pending;
    private int scroll, contentHeight, pendingTicks;
    private Button save;
    private boolean restoreSearchFocus;

    public ThrowableSettingsScreen(Screen parent) { super(Texts.of("screen.tacz_pubg.throwable_settings.title")); this.parent = parent; }
    @Override protected void init() {
        rows.clear();
        int usable = width - 20;
        EditBox search = ConfigUi.editBox(font, 10, 30, Math.max(100, usable - 150), 20, Texts.of("screen.tacz_pubg.loot_config.search_name_id"));
        search.setMaxLength(128); search.setValue(query);
        search.setResponder(value -> {
            if (pending || !commit()) return;
            query = value; String previous = selected; updateIds();
            if (!previous.equals(selected)) { restoreSearchFocus = true; rebuildWidgets(); }
        });
        addRenderableWidget(search);
        if (restoreSearchFocus) { setInitialFocus(search); restoreSearchFocus = false; }
        addRenderableWidget(ConfigUi.button(Component.literal("◀"), button -> select(-1)).bounds(width - 134, 30, 26, 20).build());
        addRenderableWidget(ConfigUi.button(Component.literal("▶"), button -> select(1)).bounds(width - 102, 30, 26, 20).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.throwable_settings.reload"), button -> {
            if (pending) return;
            if (dirty()) minecraft.setScreen(new ConfirmScreen(ok -> { minecraft.setScreen(this); if (ok) send(false); },
                    Texts.of("screen.tacz_pubg.throwable_settings.discard"), getTitle())); else send(false);
        }).bounds(width - 70, 30, 60, 20).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.throwable_settings.reset_item"), button -> {
            if (pending) return;
            if (!selected.isEmpty()) { draft.remove(selected); openFields(); rebuildWidgets(); }
        }).bounds(10, 77, Math.min(150, width / 2 - 16), 20).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.throwable_settings.complete_json"), button -> {
            if (!pending && commit() && !selected.isEmpty()) minecraft.setScreen(new JsonEditor(this, selected, current().toString()));
        }).bounds(Math.min(168, width / 2), 77, Math.min(140, width / 2 - 16), 20).build());
        int y = 0;
        for (Field field : fields.values()) {
            int boxWidth = Math.min(220, Math.max(90, width / 3));
            EditBox box = ConfigUi.editBox(font, width - boxWidth - 22, 0, boxWidth, 20, ThrowableSettingsFieldNames.label(field.path));
            box.setMaxLength(8192); box.setValue(field.raw);
            box.setResponder(value -> { fields.put(field.path, new Field(field.path, field.original, value)); error = ""; updateSave(); });
            box.setTooltip(Tooltip.create(Component.literal(field.path + " = " + field.original)));
            addRenderableWidget(box); rows.add(new Row(box, y)); y += 27;
        }
        contentHeight = y;
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.throwable_settings.back"), button -> onClose()).bounds(10, height - 27, 110, 20).build());
        save = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.configuration.save"), button -> { if (commit()) send(true); }).bounds(width - 110, height - 27, 100, 20).build());
        positionRows(); updateSave();
        if (!started) { started = true; send(false); }
    }
    private void updateIds() {
        String old = selected;
        ids = catalog.keySet().stream().filter(id -> query.isBlank() || id.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))
                || label(id).getString().toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))).sorted().toList();
        if (!ids.contains(old)) { selected = ids.isEmpty() ? "" : ids.getFirst(); openFields(); }
    }
    private void select(int delta) {
        if (pending || ids.isEmpty() || !commit()) return;
        selected = ids.get(Math.floorMod(ids.indexOf(selected) + delta, ids.size())); scroll = 0; openFields(); rebuildWidgets();
    }
    private static Component label(String id) {
        return id.startsWith("tacz_pubg:") ? Component.translatable("item.tacz_pubg." + id.substring(id.indexOf(':') + 1)) : Component.literal(id);
    }
    private JsonObject current() {
        if (selected.isEmpty()) return new JsonObject();
        JsonObject baseline = catalog.getAsJsonObject(selected), overrides = draft.getAsJsonObject(selected);
        return overrides == null ? baseline.deepCopy() : ThrowableSettingsModel.merge(baseline, overrides);
    }
    private void openFields() { fields.clear(); if (!selected.isEmpty()) flatten(current(), ""); }
    private void flatten(JsonObject object, String prefix) {
        for (var entry : object.entrySet()) {
            if (prefix.isEmpty() && selected.startsWith("tacz_pubg:")) {
                var kind = java.util.Arrays.stream(com.example.examplemod.throwables.ThrowableKind.values())
                        .filter(value -> selected.equals("tacz_pubg:" + value.id)).findFirst().orElse(null);
                if (kind != null && !ThrowableSettingsModel.applicable(kind, entry.getKey())) continue;
            }
            String path = prefix + entry.getKey(); JsonElement value = entry.getValue();
            if (value.isJsonObject() && !value.getAsJsonObject().isEmpty()) flatten(value.getAsJsonObject(), path + ".");
            else fields.put(path, new Field(path, value.deepCopy(), value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString()));
        }
    }
    private boolean commit() {
        if (selected.isEmpty()) return true;
        try {
            JsonObject next = current();
            for (Field field : fields.values()) {
                JsonElement value = field.original.isJsonPrimitive() && field.original.getAsJsonPrimitive().isString()
                        ? new JsonPrimitive(field.raw) : JsonParser.parseString(field.raw);
                set(next, field.path, value);
            }
            JsonObject overrides = difference(catalog.getAsJsonObject(selected), next);
            JsonObject candidate = draft.deepCopy();
            if (overrides.isEmpty()) candidate.remove(selected); else candidate.add(selected, overrides);
            ThrowableSettingsModel.parse(candidate.toString()); draft = candidate; error = ""; return true;
        } catch (RuntimeException failure) { error = String.valueOf(failure.getMessage()); return false; }
    }
    private static void set(JsonObject target, String path, JsonElement value) {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) target = target.getAsJsonObject(parts[i]);
        target.add(parts[parts.length - 1], value);
    }
    private static JsonObject difference(JsonObject base, JsonObject next) {
        JsonObject result = new JsonObject();
        for (var entry : next.entrySet()) {
            JsonElement previous = base.get(entry.getKey()), value = entry.getValue();
            if (previous != null && previous.equals(value)) continue;
            if (previous != null && previous.isJsonObject() && value.isJsonObject()) result.add(entry.getKey(), difference(previous.getAsJsonObject(), value.getAsJsonObject()));
            else result.add(entry.getKey(), value.deepCopy());
        }
        return result;
    }
    private boolean dirty() { return !fields.isEmpty() && fields.values().stream().anyMatch(field -> !field.raw.equals(field.original.isJsonPrimitive() && field.original.getAsJsonPrimitive().isString() ? field.original.getAsString() : field.original.toString())) || !draft.equals(saved); }
    private void updateSave() { if (save != null) { save.visible = dirty() || pending; save.active = !pending && revision >= 0; } }
    private void send(boolean saving) {
        request = UUID.randomUUID(); pending = true; pendingTicks = 0; error = ""; updateSave();
        positionRows();
        PacketDistributor.sendToServer(new ThrowableSettingsPayloads.Request(request, saving, revision, saving ? draft.toString() : "{}"));
    }
    void accept(ThrowableSettingsPayloads.State state) {
        if (!state.request().equals(request)) return;
        pending = false; request = null;
        if (!state.success()) { error = state.message(); updateSave(); positionRows(); return; }
        revision = state.revision(); saved = JsonParser.parseString(state.json()).getAsJsonObject(); draft = saved.deepCopy();
        catalog = JsonParser.parseString(state.catalog()).getAsJsonObject(); updateIds(); openFields(); rebuildWidgets();
    }
    @Override public void tick() { if (pending && ++pendingTicks > 200) { pending = false; request = null; error = Texts.tr("screen.tacz_pubg.throwable_settings.timeout"); updateSave(); positionRows(); } }
    private void positionRows() {
        int top = 105, bottom = height - 48;
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - Math.max(1, bottom - top)));
        for (Row row : rows) { int y = top + row.logicalY - scroll; row.widget.setY(y); row.widget.visible = y >= top && y + 20 <= bottom; row.widget.active = !pending; }
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { if (y >= 105 && y < height - 48) { scroll -= (int) (vertical * 27); positionRows(); return true; } return super.mouseScrolled(x, y, horizontal, vertical); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial); super.render(graphics, mouseX, mouseY, partial);
        graphics.drawCenteredString(font, getTitle(), width / 2, 11, 0xffffffff);
        if (!selected.isEmpty()) graphics.drawString(font, label(selected).copy().append("   " + (ids.indexOf(selected) + 1) + "/" + ids.size() + "  " + selected), 10, 59, 0xffe6d58e);
        int index = 0;
        for (Field field : fields.values()) {
            int y = 105 + index++ * 27 - scroll;
            if (y < 105 || y + 20 > height - 48) continue;
            String label = ThrowableSettingsFieldNames.label(field.path).getString();
            graphics.drawString(font, font.plainSubstrByWidth(label, Math.max(60, width * 2 / 3 - 36)), 10, y + 6, 0xffdddddd);
        }
        if (!error.isEmpty()) graphics.drawString(font, font.plainSubstrByWidth(Texts.resolve(error), width - 22), 10, height - 42, 0xffff7777);
    }
    @Override public void onClose() {
        if (pending) return;
        if (dirty()) minecraft.setScreen(new ConfirmScreen(ok -> minecraft.setScreen(ok ? parent : this), Texts.of("screen.tacz_pubg.throwable_settings.discard"), getTitle()));
        else minecraft.setScreen(parent);
    }
    private static final class JsonEditor extends Screen {
        private final ThrowableSettingsScreen parent;
        private final String id;
        private String text, failure = "";
        private final String initial;
        JsonEditor(ThrowableSettingsScreen parent, String id, String text) { super(Texts.of("screen.tacz_pubg.throwable_settings.complete_json")); this.parent = parent; this.id = id; this.text = text; initial = text; }
        @Override protected void init() {
            var box = new net.minecraft.client.gui.components.MultiLineEditBox(font, 10, 35, width - 20, height - 85, Component.empty(), Component.literal(id));
            box.setCharacterLimit(ThrowableSettingsModel.MAX_BYTES); box.setValue(text); box.setValueListener(value -> text = value); addRenderableWidget(box);
            addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.configuration.save"), button -> {
                try {
                    var value = ThrowableSettingsModel.parse("{\"" + id + "\":" + text + "}").getAsJsonObject(id);
                    JsonObject candidate = parent.draft.deepCopy(); candidate.add(id, difference(parent.catalog.getAsJsonObject(id), value));
                    ThrowableSettingsModel.parse(candidate.toString()); parent.draft = candidate; parent.openFields(); minecraft.setScreen(parent);
                }
                catch (RuntimeException error) { failure = String.valueOf(error.getMessage()); }
            }).bounds(width - 110, height - 27, 100, 20).build());
            addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.throwable_settings.back"), button -> onClose()).bounds(10, height - 27, 100, 20).build());
        }
        @Override public void render(GuiGraphics g, int x, int y, float p) { renderBackground(g,x,y,p); super.render(g,x,y,p); g.drawCenteredString(font,getTitle(),width/2,12,-1); if (!failure.isEmpty()) g.drawString(font,font.plainSubstrByWidth(Texts.resolve(failure),width-20),10,height-43,0xffff7777); }
        @Override public void onClose() {
            if (text.equals(initial)) minecraft.setScreen(parent);
            else minecraft.setScreen(new ConfirmScreen(discard -> minecraft.setScreen(discard ? parent : this),
                    Texts.of("screen.tacz_pubg.throwable_settings.discard"), getTitle()));
        }
    }
}
