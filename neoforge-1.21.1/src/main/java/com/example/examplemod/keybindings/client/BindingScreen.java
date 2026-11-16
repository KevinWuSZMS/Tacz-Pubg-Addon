package com.example.examplemod.keybindings.client;

import com.example.examplemod.keybindings.*;
import java.util.*;
import java.io.UncheckedIOException;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The editor uses its own logical coordinates, independent of Minecraft's pixel-font GUI scale. */
public final class BindingScreen extends Screen {
    private static final int TEXT = 0xffedf4f6, MUTED = 0xff9fb7c1, CYAN = 0xff57e8ff;
    private static final int PANEL = 0xe619282e, BORDER = 0xff425b66;
    private final Screen parent;
    private final BindingProfiles profiles = BindingProfiles.get();
    private final List<BindingAction> actions;
    private final EnumMap<BindingProfile, Editor> editors = new EnumMap<>(BindingProfile.class);
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private BehaviorOptions behaviorDraft;
    private final KeyboardDiagram diagram = new KeyboardDiagram();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> disabledHits = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private BindingVisuals visuals;
    private BindingProfile profile;
    private SmoothBindingSearch search;
    private float scale, originX, canvasWidth, canvasHeight, listTop, listBottom, contentHeight;
    private int listColumns;
    private float cardWidth;
    private boolean sideSettings;
    private double pointerX, pointerY;
    private boolean confirmReset, draggingScroll;
    private int focusIndex = -1;
    private String hoveredKey;
    private BindingAction hoveredAction;
    private boolean saveFailed;

    private static final class Editor {
        final LinkedHashSet<String> keys = new LinkedHashSet<>();
        final Map<String, InputBinding> drafts = new LinkedHashMap<>();
        final Set<String> collapsed = new HashSet<>();
        String selected, located, navigationSignature = "", query = "";
        InputBinding.Mode mode = InputBinding.Mode.CHORD;
        float scroll;
    }
    private record Snapshot(BindingProfile profile, Map<String, InputBinding> bindings, BehaviorOptions behavior) {}
    private record Hit(float x, float y, float w, float h, String label, Runnable action) {
        boolean contains(double px, double py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }
    private record Row(BindingAction action, String group, float y, int column) {}
    private record Conflicts(Set<String> actions, Set<String> keys) {}

    public BindingScreen(Screen parent) {
        super(Component.translatable("tacz_pubg.bindings.title"));
        this.parent = parent;
        profiles.ensureInitialized();
        profile = profiles.activeProfile();
        actions = profiles.actions();
        behaviorDraft = profiles.behavior();
        for (BindingProfile item : BindingProfile.values()) {
            Editor editor = new Editor();
            editors.put(item, editor);
        }
    }

    private Editor editor() { return editors.get(profile); }
    private static String tr(String id, Object... args) { return I18n.get("tacz_pubg.bindings." + id, args); }
    private String profileName(BindingProfile item) { return tr(item == BindingProfile.MATCH ? "match" : "general"); }

    @Override protected void init() {
        if (visuals == null) visuals = new BindingVisuals();
        // Keep the existing device/text scale; reclaim the former side margins for settings and actions.
        scale = Math.min(width / 1240f, height / 850f);
        originX = 0;
        canvasWidth = width / scale;
        canvasHeight = height / scale;
        sideSettings = canvasWidth - 1216 >= 200;
        listColumns = canvasWidth - 48 >= 1336 ? 4 : 3;
        cardWidth = (canvasWidth - 58) / listColumns - 10;
        search = addWidget(new SmoothBindingSearch(font, 24, 0, (int)(canvasWidth - 306), 34, Component.translatable("tacz_pubg.bindings.search")));
        search.setMaxLength(160);
        search.setValue(editor().query);
        search.setResponder(value -> { editor().query = value; editor().scroll = 0; });
    }

    private InputBinding value(BindingAction action) { return value(action.id()); }
    private InputBinding value(String id) { return editor().drafts.getOrDefault(id, profiles.binding(profile, id)); }
    private InputBinding selection() { return new InputBinding(List.copyOf(editor().keys), editor().mode); }
    private BindingAction selectedAction() {
        return actions.stream().filter(a -> a.id().equals(editor().selected)).findFirst().orElse(null);
    }
    private boolean changed() {
        return !editor().drafts.isEmpty() || !behaviorDraft.equals(profiles.behavior());
    }
    private void draft(String id, InputBinding binding) {
        if (same(binding, profiles.binding(profile, id))) editor().drafts.remove(id);
        else editor().drafts.put(id, binding);
    }
    private static boolean same(InputBinding a, InputBinding b) {
        return a.mode() == b.mode() && Set.copyOf(a.keys()).equals(Set.copyOf(b.keys()));
    }
    private void editSelection() { if (editor().selected != null) draft(editor().selected, selection()); }
    private void selectKey(String id) {
        if (id.endsWith("Any")) {
            String family = id.substring(0, id.length() - 3);
            editor().keys.remove(family + "Left"); editor().keys.remove(family + "Right");
        } else if (id.endsWith("Left") || id.endsWith("Right")) {
            editor().keys.remove(id.replace("Left", "Any").replace("Right", "Any"));
        }
        if (!editor().keys.remove(id)) editor().keys.add(id);
        editSelection();
        editor().located = null;
    }
    private void selectAction(BindingAction action) {
        Editor e = editor();
        boolean keyFirst = e.selected == null && !e.keys.isEmpty();
        e.selected = action.id();
        if (keyFirst) editSelection();
        else loadSelection(value(action));
        e.located = null;
    }
    private void loadSelection(InputBinding binding) {
        editor().keys.clear(); editor().keys.addAll(binding.keys()); editor().mode = binding.mode();
    }
    private void deselect() {
        editor().keys.clear(); editor().selected = null; editor().located = null;
        editor().navigationSignature = "";
        editor().mode = InputBinding.Mode.CHORD; setFocused(null); focusIndex = -1;
    }
    private void switchProfile(BindingProfile next) {
        if (profile == next) return;
        profile = next;
        float scroll = editor().scroll;
        search.setValue(editor().query); editor().scroll = scroll;
        setFocused(null); focusIndex = -1; saveFailed = false;
    }
    private void remember() {
        Map<String, InputBinding> values = new LinkedHashMap<>();
        actions.forEach(a -> values.put(a.id(), profiles.binding(profile, a.id())));
        undo.push(new Snapshot(profile, values, profiles.behavior()));
        while (undo.size() > 20) undo.removeLast();
    }
    private void save() {
        remember();
        try {
            profiles.save(profile, Map.copyOf(editor().drafts), behaviorDraft);
            editor().drafts.clear(); saveFailed = false; BindingInput.refresh();
            deselect();
        } catch (UncheckedIOException exception) {
            undo.pop(); reportSaveError(exception);
        }
    }
    private void cancel() {
        editor().drafts.clear(); behaviorDraft = profiles.behavior();
        if (editor().selected != null) loadSelection(value(editor().selected));
    }
    private void undo() {
        if (undo.isEmpty()) return;
        Snapshot snapshot = undo.peek();
        try {
            profiles.save(snapshot.profile(), snapshot.bindings(), snapshot.behavior());
            undo.pop(); switchProfile(snapshot.profile()); editor().drafts.clear(); behaviorDraft = snapshot.behavior();
            if (editor().selected != null) loadSelection(value(editor().selected));
            saveFailed = false; BindingInput.refresh();
        } catch (UncheckedIOException exception) { reportSaveError(exception); }
    }
    private void clearKeys() {
        InputBinding target = selection();
        for (BindingAction action : actions) {
            InputBinding binding = value(action);
            for (List<String> group : target.groups()) binding = binding.removeExact(group);
            draft(action.id(), binding);
        }
        if (editor().selected != null) loadSelection(value(editor().selected));
    }
    private void reset() {
        remember();
        try {
            profiles.reset(profile); editor().drafts.clear();
            behaviorDraft = profiles.behavior(); deselect(); confirmReset = false;
            saveFailed = false; BindingInput.refresh();
        } catch (UncheckedIOException exception) {
            undo.pop(); confirmReset = false; reportSaveError(exception);
        }
    }
    private void reportSaveError(UncheckedIOException exception) {
        saveFailed = true;
        com.example.examplemod.ExampleMod.LOGGER.error("Could not save edited key bindings", exception);
    }

    private static boolean appears(List<String> keys, String key) {
        if (keys.contains(key)) return true;
        if (key.endsWith("Left") || key.endsWith("Right")) {
            return keys.contains(key.replace("Left", "Any").replace("Right", "Any"));
        }
        return false;
    }
    private static List<Set<String>> physical(List<String> keys) {
        List<Set<String>> groups = new ArrayList<>(); groups.add(new HashSet<>());
        for (String key : keys) {
            List<String> options = switch (key) {
                case "ControlAny" -> List.of("ControlLeft", "ControlRight");
                case "ShiftAny" -> List.of("ShiftLeft", "ShiftRight");
                case "AltAny" -> List.of("AltLeft", "AltRight");
                default -> List.of(key);
            };
            List<Set<String>> next = new ArrayList<>();
            for (Set<String> group : groups) for (String option : options) {
                Set<String> copy = new HashSet<>(group); copy.add(option); next.add(copy);
            }
            groups = next;
        }
        return groups;
    }
    private Conflicts conflicts() {
        Map<Set<String>, Set<String>> owners = new HashMap<>();
        for (BindingAction action : actions) for (List<String> group : value(action).groups()) {
            for (Set<String> expanded : physical(group)) owners.computeIfAbsent(expanded, ignored -> new HashSet<>()).add(action.id());
        }
        Set<String> result = new HashSet<>(), keys = new HashSet<>();
        owners.forEach((group, ids) -> { if (ids.size() > 1) { result.addAll(ids); keys.addAll(group); } });
        for (String generic : List.of("ControlAny", "ShiftAny", "AltAny")) {
            if (BindingKeys.aliases(generic).stream().anyMatch(keys::contains)) keys.add(generic);
        }
        return new Conflicts(result, keys);
    }
    private boolean linked(InputBinding binding) {
        return selection().groups().stream().anyMatch(binding::containsExact);
    }
    private List<BindingAction> navigationMatches() { return actions.stream().filter(a -> linked(value(a))).toList(); }
    private void nextFunction() {
        List<BindingAction> matches = navigationMatches();
        if (matches.isEmpty()) return;
        Editor e = editor();
        String signature = selection().mode() + new TreeSet<>(e.keys).toString() + e.selected + matches.stream().map(BindingAction::id).toList();
        if (!signature.equals(e.navigationSignature)) { e.navigationSignature = signature; e.located = e.selected; }
        int current = -1;
        for (int i = 0; i < matches.size(); i++) if (matches.get(i).id().equals(e.located)) current = i;
        BindingAction next = matches.get((current + 1) % matches.size());
        e.located = next.id(); e.collapsed.remove(next.modName());
        if (!matchesQuery(next)) { search.setValue(""); e.query = ""; }
        buildRows();
        for (Row row : rows) if (row.action() != null && row.action().id().equals(next.id())) {
            e.scroll = Math.max(0, Math.min(contentHeight - (listBottom - listTop), row.y() - 36)); break;
        }
    }
    private boolean matchesQuery(BindingAction action) {
        String query = editor().query.strip().toLowerCase(Locale.ROOT);
        return query.isEmpty() || action.name().toLowerCase(Locale.ROOT).contains(query)
                || action.modName().toLowerCase(Locale.ROOT).contains(query);
    }
    private void buildRows() {
        rows.clear();
        LinkedHashMap<String, List<BindingAction>> groups = new LinkedHashMap<>();
        actions.stream().filter(this::matchesQuery).forEach(a -> groups.computeIfAbsent(a.modName(), ignored -> new ArrayList<>()).add(a));
        float y = 0;
        for (var group : groups.entrySet()) {
            rows.add(new Row(null, group.getKey(), y, 0)); y += 30;
            if (editor().collapsed.contains(group.getKey()) && editor().query.isBlank()) continue;
            int column = 0;
            for (BindingAction action : group.getValue()) {
                rows.add(new Row(action, group.getKey(), y, column));
                if (++column == listColumns) { column = 0; y += 62; }
            }
            if (column != 0) y += 62;
            y += 8;
        }
        contentHeight = y;
        editor().scroll = Math.max(0, Math.min(editor().scroll, contentHeight - (listBottom - listTop)));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level == null) renderPanorama(g, partialTick);
        renderBlurredBackground(partialTick);
        g.fill(0, 0, width, height, 0xb50a1317);
        pointerX = (mouseX - originX) / scale; pointerY = mouseY / scale;
        hits.clear(); disabledHits.clear(); hoveredAction = null;
        g.pose().pushPose(); g.pose().translate(originX, 0, 0); g.pose().scale(scale, scale, 1);
        visuals.roundRect(g, 8, 8, canvasWidth - 16, canvasHeight - 16, 14, 0xd5101b20, 0x50798e95);
        Conflicts conflicts = conflicts();
        Map<String, KeyboardDiagram.KeyState> states = new HashMap<>();
        Set<String> visibleKeys = new HashSet<>(diagram.keyIds());
        Set<String> boundKeys = new HashSet<>(), selectedKeys = new HashSet<>(editor().keys);
        for (BindingAction action : actions) for (String key : value(action).keys()) {
            visibleKeys.add(key); boundKeys.add(key); boundKeys.addAll(BindingKeys.aliases(key));
        }
        editor().keys.forEach(key -> selectedKeys.addAll(BindingKeys.aliases(key)));
        for (String key : visibleKeys) {
            states.put(key, new KeyboardDiagram.KeyState(boundKeys.contains(key), conflicts.keys().contains(key), selectedKeys.contains(key)));
        }
        hoveredKey = confirmReset ? null : diagram.hit(pointerX, pointerY);
        diagram.render(g, visuals, 24, 24, 1152, states, hoveredKey);
        float y = 24 + diagram.height(1152) + 6;
        if (sideSettings) renderProfileOptions(g, 24, true);
        else {
            renderProfileOptions(g, y + 3, false);
            y += 86;
        }
        String[] legend = {"unbound", "bound", "conflict", "selected"};
        int[] colors = {0xff0c1116, 0xffae9246, 0xffbd5d57, CYAN};
        for (int i = 0; i < 4; i++) {
            visuals.roundRect(g, 26 + i * 111, y + 4, 11, 11, 3, colors[i], 0xffeeeeee);
            visuals.text(g, tr(legend[i]), 44 + i * 111, y, 12, MUTED);
        }
        visuals.text(g, ellipsis(selectionText(), canvasWidth - 600, 13), 570, y, 13, CYAN);
        y += 29;
        renderEditor(g, y);
        y += 80;
        renderSearch(g, y);
        listTop = y + 43; listBottom = canvasHeight - 76;
        buildRows(); renderRows(g, conflicts.actions());
        renderFooter(g);
        if (!confirmReset) renderTooltip(g);
        if (focusIndex >= 0 && focusIndex < hits.size() && !confirmReset) {
            Hit hit = hits.get(focusIndex);
            visuals.roundRect(g, hit.x - 2, hit.y - 2, hit.w + 4, hit.h + 4, 7, 0, CYAN);
        }
        if (confirmReset) renderConfirmation(g);
        g.pose().popPose();
    }

    private void renderProfileOptions(GuiGraphics g, float top, boolean sidebar) {
        float x = sidebar ? 1192 : 24, w = sidebar ? canvasWidth - 1216 : 136;
        button(g, x, top, w, 34, profileName(BindingProfile.GENERAL), profile == BindingProfile.GENERAL, true, () -> switchProfile(BindingProfile.GENERAL));
        button(g, sidebar ? x : 166, sidebar ? top + 42 : top, w, 34,
                profileName(BindingProfile.MATCH), profile == BindingProfile.MATCH, true, () -> switchProfile(BindingProfile.MATCH));
        visuals.text(g, tr("global_changes"), x, sidebar ? top + 90 : top + 51, 13, MUTED);
        BehaviorOptions behavior = behaviorDraft;
        float behaviorWidth = sidebar ? w : (canvasWidth - 206) / 3;
        float bx = sidebar ? x : 166, by = sidebar ? top + 116 : top + 42;
        float dx = sidebar ? 0 : behaviorWidth + 8, dy = sidebar ? 48 : 0;
        button(g, bx, by, behaviorWidth, 34, tr("crouch") + " · " + tr(behavior.toggleCrouch() ? "toggle" : "hold"), false, true,
                () -> behaviorDraft = new BehaviorOptions(!behavior.toggleCrouch(), behavior.toggleSprint(), behavior.autoJump()));
        button(g, bx + dx, by + dy, behaviorWidth, 34, tr("sprint") + " · " + tr(behavior.toggleSprint() ? "toggle" : "hold"), false, true,
                () -> behaviorDraft = new BehaviorOptions(behavior.toggleCrouch(), !behavior.toggleSprint(), behavior.autoJump()));
        button(g, bx + 2 * dx, by + 2 * dy, behaviorWidth, 34, tr("auto_jump") + " · " + tr(behavior.autoJump() ? "on" : "off"), false, true,
                () -> behaviorDraft = new BehaviorOptions(behavior.toggleCrouch(), behavior.toggleSprint(), !behavior.autoJump()));
    }

    private String selectionText() {
        return editor().keys.isEmpty() ? tr("select_key") : tr("selected_keys", bindingText(selection()));
    }
    private String bindingText(InputBinding binding) {
        return binding.isEmpty() ? tr("unbound") : String.join(binding.mode() == InputBinding.Mode.CHORD ? " + " : " / ", binding.keys().stream().map(BindingKeys::label).toList());
    }
    private String ellipsis(String text, float max, float size) {
        if (visuals.textWidth(text, size) <= max) return text;
        int end = text.length();
        while (end > 0 && visuals.textWidth(text.substring(0, end) + "…", size) > max) end--;
        return text.substring(0, end) + "…";
    }
    private void renderEditor(GuiGraphics g, float y) {
        visuals.roundRect(g, 24, y, canvasWidth - 48, 68, 9, 0xee172b31, BORDER);
        List<BindingAction> matches = navigationMatches();
        BindingAction action = selectedAction();
        button(g, 34, y + 8, 348, 52, "", false, !matches.isEmpty(), this::nextFunction);
        String counter = tr("next");
        for (int i = 0; i < matches.size(); i++) if (matches.get(i).id().equals(editor().located)) counter = (i + 1) + " / " + matches.size() + " ↓";
        visuals.text(g, tr("current_function"), 47, y + 14, 11, MUTED);
        visuals.text(g, counter, 270, y + 14, 11, CYAN);
        visuals.text(g, ellipsis(action == null ? tr("select_function") : action.name(), 320, 16), 47, y + 33, 16, TEXT);
        visuals.text(g, ellipsis(bindingText(selection()), canvasWidth - 730, 17), 404, y + 25, 17, CYAN);
        button(g, canvasWidth - 306, y + 18, 128, 32, tr("chord"), editor().mode == InputBinding.Mode.CHORD, true, () -> { editor().mode = InputBinding.Mode.CHORD; editSelection(); });
        button(g, canvasWidth - 172, y + 18, 136, 32, tr("any"), editor().mode == InputBinding.Mode.ANY, true, () -> { editor().mode = InputBinding.Mode.ANY; editSelection(); });
    }
    private void renderSearch(GuiGraphics g, float y) {
        search.setY((int)y);
        visuals.roundRect(g, 24, y, canvasWidth - 306, 34, 6, 0xff0d181e, search.isFocused() ? CYAN : BORDER);
        visuals.text(g, "⌕", 36, y + 6, 18, MUTED);
        search.renderText(g, visuals, 62, y + 7, canvasWidth - 360, 14, TEXT, MUTED, tr("search"));
        hits.add(new Hit(24, y, canvasWidth - 306, 34, tr("search"), () -> { setFocused(search); search.moveCursorFromMouse(pointerX, hasShiftDown()); }));
        visuals.text(g, tr("count", actions.stream().filter(this::matchesQuery).count()), canvasWidth - 266, y + 9, 12, MUTED);
        button(g, canvasWidth - 132, y, 108, 34, tr("deselect"), false, true, this::deselect);
    }
    private void renderRows(GuiGraphics g, Set<String> conflicts) {
        g.enableScissor((int)(originX + 24 * scale), (int)(listTop * scale), (int)(originX + (canvasWidth - 23) * scale), (int)(listBottom * scale));
        for (Row row : rows) {
            float y = listTop + row.y - editor().scroll;
            float h = row.action == null ? 28 : 55;
            if (y + h <= listTop || y >= listBottom) continue;
            if (row.action == null) {
                visuals.text(g, (editor().collapsed.contains(row.group) && editor().query.isBlank() ? "▸  " : "▾  ") + row.group, 28, y + 4, 14, MUTED);
                addClippedHit(24, y, canvasWidth - 60, h, row.group, () -> { if (!editor().collapsed.remove(row.group)) editor().collapsed.add(row.group); });
            } else {
                BindingAction action = row.action;
                float x = 24 + row.column * (cardWidth + 10);
                boolean selected = action.id().equals(editor().selected), related = linked(value(action));
                boolean located = action.id().equals(editor().located);
                int border = located ? 0xfff2faff : selected || related ? CYAN : 0xff3c515b;
                int fill = selected ? 0xff214954 : related ? 0xff182f37 : 0xdd132127;
                if (pointerX >= x && pointerX < x + cardWidth && pointerY >= Math.max(y, listTop) && pointerY < Math.min(y + 55, listBottom)) {
                    hoveredAction = action;
                    if (!selected && !related) fill = 0xff26363e;
                }
                visuals.roundRect(g, x, y, cardWidth, 55, 6, fill, border);
                visuals.text(g, ellipsis(action.name(), cardWidth - 36, 14), x + 12, y + 7, 14, TEXT);
                visuals.text(g, ellipsis(bindingText(value(action)), cardWidth - 36, 12), x + 12, y + 30, 12,
                        selected || related ? CYAN : conflicts.contains(action.id()) ? 0xfff77d78 : value(action).isEmpty() ? MUTED : 0xffe3c56e);
                if (editor().drafts.containsKey(action.id())) visuals.roundRect(g, x + cardWidth - 14, y + 8, 5, 5, 2, CYAN, 0);
                addClippedHit(x, y, cardWidth, 55, action.name(), () -> selectAction(action));
            }
        }
        g.disableScissor();
        if (rows.isEmpty()) visuals.text(g, tr("no_results"), 36, listTop + 20, 15, MUTED);
        if (contentHeight > listBottom - listTop) {
            float view = listBottom - listTop, thumb = Math.max(25, view * view / contentHeight);
            float top = listTop + editor().scroll / (contentHeight - view) * (view - thumb);
            visuals.roundRect(g, canvasWidth - 29, listTop, 5, view, 2, 0xff15252e, 0);
            visuals.roundRect(g, canvasWidth - 29, top, 5, thumb, 2, 0xff74909c, 0);
        }
    }
    private void addClippedHit(float x, float y, float w, float h, String label, Runnable action) {
        float top = Math.max(listTop, y), bottom = Math.min(listBottom, y + h);
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, label, action));
    }
    private void renderTooltip(GuiGraphics g) {
        List<String> lines = new ArrayList<>();
        if (hoveredAction != null) {
            lines.add(hoveredAction.name()); lines.add(bindingText(value(hoveredAction)));
        } else if (hoveredKey != null) {
            lines.add(BindingKeys.label(hoveredKey));
            List<BindingAction> owners = actions.stream().filter(a -> appears(value(a).keys(), hoveredKey)).toList();
            for (BindingAction action : owners.stream().limit(6).toList()) lines.add(action.name() + " · " + bindingText(value(action)));
            if (owners.isEmpty()) lines.add(tr("unbound"));
            if (owners.size() > 6) lines.add("+ " + (owners.size() - 6));
        }
        if (lines.isEmpty()) return;
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            String remaining = line;
            while (visuals.textWidth(remaining, 13) > 440) {
                int count = remaining.length() - 1;
                while (count > 1 && visuals.textWidth(remaining.substring(0, count), 13) > 440) count--;
                wrapped.add(remaining.substring(0, count)); remaining = remaining.substring(count);
            }
            wrapped.add(remaining);
        }
        float w = Math.min(464, (float)wrapped.stream().mapToDouble(line -> visuals.textWidth(line, 13)).max().orElse(80) + 24);
        float h = wrapped.size() * 20 + 16;
        float x = Math.max(24, Math.min(canvasWidth - 24 - w, (float)pointerX + 14));
        float y = Math.max(16, Math.min(canvasHeight - h - 18, (float)pointerY + 24));
        visuals.roundRect(g, x, y, w, h, 6, 0xf80c1c23, 0xff7897a3);
        for (int i = 0; i < wrapped.size(); i++) visuals.text(g, wrapped.get(i), x + 12, y + 8 + 20 * i, 13, i == 0 ? TEXT : MUTED);
    }
    private void renderFooter(GuiGraphics g) {
        float y = canvasHeight - 57;
        button(g, 24, y, 132, 34, tr("clear_function"), false, editor().selected != null,
                () -> { editor().keys.clear(); editSelection(); });
        button(g, 164, y, 132, 34, tr("clear_keys"), false, !editor().keys.isEmpty(), this::clearKeys);
        button(g, 304, y, 120, 34, tr("reset"), false, true, () -> { confirmReset = true; setFocused(null); focusIndex = 0; });
        button(g, 432, y, 76, 34, tr("undo"), false, !undo.isEmpty(), this::undo);
        if (changed() || editor().selected != null && !editor().keys.isEmpty()) {
            visuals.text(g, saveFailed ? tr("save_error") : changed() ? tr("unsaved") : "", 535, y + 9, 12, saveFailed ? 0xfff77d78 : MUTED);
            button(g, canvasWidth - 350, y, 94, 34, tr("cancel"), false, true, this::cancel);
            button(g, canvasWidth - 248, y, 100, 34, tr("save"), true, true, this::save);
        }
        button(g, canvasWidth - 140, y, 116, 34, tr("done"), false, true, this::onClose);
    }
    private void renderConfirmation(GuiGraphics g) {
        hits.clear(); disabledHits.clear();
        visuals.roundRect(g, 0, 0, canvasWidth, canvasHeight, 0, 0xbb00080c, 0);
        float y = canvasHeight / 2 - 96;
        float center = canvasWidth / 2;
        visuals.roundRect(g, center - 260, y, 520, 192, 12, 0xff172a32, 0xff759ca9);
        visuals.text(g, tr("reset_title", profileName(profile)), center - 236, y + 24, 21, TEXT);
        visuals.text(g, tr("reset_description"), center - 236, y + 69, 14, MUTED);
        button(g, center - 30, y + 128, 122, 36, tr("cancel"), false, true, () -> confirmReset = false);
        button(g, center + 104, y + 128, 132, 36, tr("confirm_reset"), true, true, this::reset);
    }
    private void button(GuiGraphics g, float x, float y, float w, float h, String label, boolean selected, boolean enabled, Runnable action) {
        boolean hover = enabled && pointerX >= x && pointerX < x + w && pointerY >= y && pointerY < y + h;
        visuals.roundRect(g, x, y, w, h, 5, selected ? 0xff285766 : hover ? 0xff2b414b : 0xff1c3039,
                selected ? CYAN : enabled ? 0xff567480 : 0xff31454d);
        if (!label.isEmpty()) visuals.textCentered(g, label, x + w / 2, y + (h - 14) / 2 - 1, 14, enabled ? TEXT : 0xff66808c);
        if (enabled) hits.add(new Hit(x, y, w, h, label, action));
        else disabledHits.add(new Hit(x, y, w, h, label, () -> {}));
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = (mouseX - originX) / scale, y = mouseY / scale;
        if (button != 0) return true;
        if (!confirmReset && x >= canvasWidth - 34 && x <= canvasWidth - 19 && y >= listTop && y <= listBottom) {
            draggingScroll = true; scrollTo(y); return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) {
            Hit hit = hits.get(i); setFocused(null); focusIndex = -1; hit.action.run(); return true;
        }
        if (disabledHits.stream().anyMatch(hit -> hit.contains(x, y))) return true;
        if (confirmReset) return true;
        String key = diagram.hit(x, y);
        if (key != null) { setFocused(null); focusIndex = -1; selectKey(key); return true; }
        deselect(); return true;
    }
    private void scrollTo(double y) {
        float view = listBottom - listTop;
        editor().scroll = (float)Math.max(0, Math.min(contentHeight - view, (y - listTop) / view * contentHeight - view / 2));
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingScroll) { scrollTo(y / scale); return true; }
        return search.isFocused() && search.mouseDragged((x - originX) / scale, y / scale, button, dx / scale, dy / scale);
    }
    @Override public boolean mouseReleased(double x, double y, int button) { draggingScroll = false; return true; }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (confirmReset) return true;
        double y = mouseY / scale, x = (mouseX - originX) / scale;
        if (x >= 24 && x <= canvasWidth - 20 && y >= listTop && y <= listBottom) {
            editor().scroll = (float)Math.max(0, Math.min(contentHeight - (listBottom - listTop), editor().scroll - vertical * 48));
        }
        return true;
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (confirmReset) confirmReset = false;
            else if (search.isFocused()) setFocused(null);
            else onClose();
            return true;
        }
        if (!confirmReset && search.isFocused() && key != GLFW.GLFW_KEY_TAB) return search.keyPressed(key, scanCode, modifiers);
        if (key == GLFW.GLFW_KEY_TAB && !hits.isEmpty()) {
            setFocused(null);
            focusIndex = Math.floorMod(focusIndex + (hasShiftDown() ? -1 : 1), hits.size());
            minecraft.getNarrator().sayNow(Component.literal(hits.get(focusIndex).label)); return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) && focusIndex >= 0 && focusIndex < hits.size()) {
            hits.get(focusIndex).action.run(); return true;
        }
        if (!confirmReset && hasControlDown() && key == GLFW.GLFW_KEY_F) { setFocused(search); return true; }
        return true;
    }
    @Override public boolean charTyped(char character, int modifiers) { return !confirmReset && search.isFocused() && search.charTyped(character, modifiers); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { if (visuals != null) { visuals.close(); visuals = null; } }
}
