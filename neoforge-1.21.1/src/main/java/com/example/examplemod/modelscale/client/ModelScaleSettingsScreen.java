package com.example.examplemod.modelscale.client;

import com.example.examplemod.modelscale.ModelScalePayloads;
import com.example.examplemod.modelscale.ModelScaleService;
import com.example.examplemod.modelscale.ModelScaleSettings;
import com.example.examplemod.modelscale.ModelScaleState;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** A standalone save-wide editor; switching styles preserves both complete raw drafts. */
public final class ModelScaleSettingsScreen extends Screen {
    private final Screen parent;
    private final Map<String, String[]> raw = new LinkedHashMap<>();
    private ModelScaleSettings baseline = ModelScaleState.settings(), draft = baseline;
    private String style = ModelScaleService.clientStyle(), search = "", status = "";
    private long expectedRevision = ModelScaleState.revision();
    private boolean requested, loaded, saving;
    private ScaleList list;
    private double scroll;

    public ModelScaleSettingsScreen(Screen parent) {
        super(text("title")); this.parent = parent;
        fillRaw();
    }
    private static Component text(String path, Object... arguments) {
        return Component.translatable("screen.tacz_pubg.model_scale." + path, arguments);
    }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private void fillRaw() {
        raw.clear();
        for (String s : ModelScaleSettings.STYLES) for (String id : ModelScaleSettings.ITEMS) {
            var value = draft.get(s, id);
            raw.put(s + "/" + id, new String[] { number(value.dropped()), number(value.worn()) });
        }
    }
    @Override protected void init() {
        int half = (width - 28) / 2;
        for (int index = 0; index < 2; index++) {
            String next = ModelScaleSettings.STYLES.get(index);
            addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.style." + next), b -> {
                if (list != null) scroll = list.getScrollAmount();
                style = next; scroll = 0; rebuildWidgets();
            }).bounds(10 + index * (half + 8), 32, half, 20).build()).active = !style.equals(next) && !saving;
        }
        EditBox filter = addRenderableWidget(new EditBox(font, 10, 58, Math.max(80, width - 120), 20, text("search")));
        filter.setHint(text("search")); filter.setValue(search);
        filter.setResponder(value -> { search = value; scroll = 0; if (list != null) { list.refill(); list.setScrollAmount(0); } });
        addRenderableWidget(Button.builder(text("reload"), b -> {
            if (dirty()) minecraft.setScreen(new ConfirmScreen(discard -> {
                minecraft.setScreen(this);
                if (discard) requestReload();
            }, text("discard"), text("discard_hint")));
            else requestReload();
        }).bounds(width - 104, 58, 94, 20).build()).active = !saving;
        list = addRenderableWidget(new ScaleList());
        list.refill(); list.setScrollAmount(scroll);
        int buttonWidth = Math.max(40, (width - 38) / 4);
        addRenderableWidget(Button.builder(text("save"), b -> commit(false)).bounds(10, height - 49, buttonWidth, 20).build()).active = loaded && !saving;
        addRenderableWidget(Button.builder(text("set_default"), b -> commit(true)).bounds(16 + buttonWidth, height - 49, buttonWidth, 20).build()).active = loaded && !saving;
        addRenderableWidget(Button.builder(text("restore_default"), b -> restore(false)).bounds(22 + buttonWidth * 2, height - 49, buttonWidth, 20).build()).active = loaded && !saving;
        addRenderableWidget(Button.builder(text("factory"), b -> restore(true)).bounds(28 + buttonWidth * 3, height - 49, buttonWidth, 20).build()).active = loaded && !saving;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(10, height - 25, 80, 20).build());
        if (!requested) { requested = true; requestReload(); }
    }
    private void requestReload() {
        loaded = false; status = "loading"; saving = false;
        PacketDistributor.sendToServer(new ModelScalePayloads.Request());
    }
    public void accept(ModelScalePayloads.State state) {
        if (state.status() == 3) { saving = false; status = "denied"; rebuildWidgets(); return; }
        if (state.status() == 2) { saving = false; status = "conflict"; rebuildWidgets(); return; }
        if (!loaded || state.status() == 1 || !saving && !dirty()) {
            baseline = draft = state.settings(); expectedRevision = state.revision(); loaded = true; saving = false;
            fillRaw(); status = state.status() == 1 ? "saved" : ""; rebuildWidgets();
        }
    }
    private boolean flush() {
        try {
            ModelScaleSettings next = draft;
            for (String s : ModelScaleSettings.STYLES) for (String id : ModelScaleSettings.ITEMS) {
                String[] value = raw.get(s + "/" + id);
                next = next.with(s, id, Double.parseDouble(value[0].trim()), Double.parseDouble(value[1].trim()));
            }
            draft = next; return true;
        } catch (IllegalArgumentException error) { status = "range_error"; return false; }
    }
    private void commit(boolean asDefault) {
        if (!loaded || saving || !flush()) return;
        if (asDefault) draft = draft.setDefault(style);
        saving = true; status = "saving";
        if (list != null) scroll = list.getScrollAmount();
        rebuildWidgets();
        PacketDistributor.sendToServer(new ModelScalePayloads.Save(expectedRevision, draft));
    }
    private void restore(boolean initial) {
        if (!loaded || saving || !flush()) return;
        draft = initial ? draft.restoreInitial(style) : draft.restoreDefault(style);
        fillRaw(); status = "draft_restored"; rebuildWidgets();
    }
    private boolean dirty() {
        if (!draft.defaults().equals(baseline.defaults())) return true;
        for (String s : ModelScaleSettings.STYLES) for (String id : ModelScaleSettings.ITEMS) {
            var value = baseline.get(s, id); var r = raw.get(s + "/" + id);
            if (r == null || !r[0].equals(number(value.dropped())) || !r[1].equals(number(value.worn()))) return true;
        }
        return false;
    }
    @Override public void onClose() {
        if (saving) return;
        if (!dirty()) minecraft.setScreen(parent);
        else minecraft.setScreen(new ConfirmScreen(discard -> minecraft.setScreen(discard ? parent : this), text("discard"), text("discard_hint")));
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffffff);
        int right = list.getRowLeft() + list.getRowWidth();
        graphics.drawString(font, text("item"), list.getRowLeft() + 2, 84, 0xffdddddd);
        graphics.drawCenteredString(font, text("dropped"), right - 108, 84, 0xffdddddd);
        graphics.drawCenteredString(font, text("worn"), right - 37, 84, 0xffdddddd);
        Component hint = status.isEmpty() ? text("hint") : text(status);
        graphics.drawString(font, font.plainSubstrByWidth(hint.getString(), width - 20), 10, height - 64,
                status.equals("range_error") || status.equals("conflict") || status.equals("denied") ? 0xffff8888 : 0xffc9d4bd);
    }
    private final class ScaleList extends ContainerObjectSelectionList<ScaleRow> {
        ScaleList() { super(ModelScaleSettingsScreen.this.minecraft, ModelScaleSettingsScreen.this.width - 20,
                Math.max(25, ModelScaleSettingsScreen.this.height - 166), 96, 38); setX(10); centerListVertically = false; }
        void refill() {
            setFocused(null); clearEntries();
            String query = search.toLowerCase(Locale.ROOT);
            for (String id : ModelScaleSettings.ITEMS) {
                var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
                ItemStack stack = item.getDefaultInstance();
                if (!stack.isEmpty() && (id + " " + stack.getHoverName().getString()).toLowerCase(Locale.ROOT).contains(query))
                    addEntry(new ScaleRow(id, stack));
            }
        }
        @Override public int getRowWidth() { return getWidth() - 16; }
        @Override protected int getScrollbarPosition() { return getX() + getWidth() - 6; }
        @Override public void setScrollAmount(double value) { super.setScrollAmount(value); scroll = getScrollAmount(); }
    }
    private final class ScaleRow extends ContainerObjectSelectionList.Entry<ScaleRow> {
        final String id; final ItemStack stack;
        final List<AbstractWidget> widgets = new ArrayList<>();
        final EditBox dropped, worn;
        ScaleRow(String id, ItemStack stack) {
            this.id = id; this.stack = stack;
            String[] values = raw.get(style + "/" + id);
            dropped = box(values, 0, "dropped"); worn = box(values, 1, "worn");
            worn.active = ModelScaleSettings.wearable(id) && loaded && !saving;
            if (!ModelScaleSettings.wearable(id)) worn.setTooltip(Tooltip.create(text("not_worn")));
        }
        private EditBox box(String[] values, int index, String label) {
            EditBox box = new EditBox(font, 0, 0, 62, 20, text(label));
            box.setMaxLength(12); box.setValue(values[index]); box.moveCursorToStart(false);
            box.setTooltip(Tooltip.create(text("range"))); box.active = loaded && !saving;
            box.setResponder(value -> values[index] = value);
            widgets.add(box); return box;
        }
        @Override public void render(GuiGraphics graphics, int index, int y, int x, int rowWidth, int rowHeight,
                int mouseX, int mouseY, boolean hovered, float partialTick) {
            graphics.renderItem(stack, x + 2, y + 7);
            graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), Math.max(20, rowWidth - 172)), x + 23, y + 3, 0xffffffff);
            graphics.drawString(font, font.plainSubstrByWidth(id.substring(id.indexOf(':') + 1), Math.max(20, rowWidth - 172)), x + 23, y + 17, 0xff888888);
            dropped.setX(x + rowWidth - 139); dropped.setY(y + 6);
            worn.setX(x + rowWidth - 68); worn.setY(y + 6);
            for (AbstractWidget widget : widgets) widget.render(graphics, mouseX, mouseY, partialTick);
        }
        @Override public List<? extends GuiEventListener> children() { return widgets; }
        @Override public List<? extends NarratableEntry> narratables() { return widgets; }
    }
}
