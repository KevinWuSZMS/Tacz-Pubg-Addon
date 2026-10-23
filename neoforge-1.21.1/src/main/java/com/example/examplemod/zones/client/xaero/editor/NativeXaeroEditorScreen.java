package com.example.examplemod.zones.client.xaero.editor;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.zones.client.MapZoneConfigScreen;
import com.example.examplemod.zones.client.xaero.editor.NativeMapEditModel.Kind;
import com.example.examplemod.zones.client.xaero.editor.NativeMapEditModel.Tool;
import com.example.examplemod.zones.client.xaero.editor.NativeMapProjection.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.glfw.GLFW;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;
import xaero.map.gui.MapMouseButtonPress;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

/** Real GuiMap terrain rendering and navigation, with a local PUBG geometry editor on top. */
public final class NativeXaeroEditorScreen extends GuiMap {
    private static final NativeFields FIELDS = new NativeFields();
    private final Screen parent;
    private final NativeMapEditModel model;
    private final Consumer<MapDefinition> onAccept;
    private final Runnable onDeleteMap;
    private final List<Button> editorButtons = new ArrayList<>();
    private final List<Button> toolButtons = new ArrayList<>();
    private final List<Tool> availableTools = new ArrayList<>();
    private long lootRevision = Long.MIN_VALUE;
    private List<com.example.examplemod.spawn.LootMapShading.Column> lootColumns = List.of();
    private final List<Button> inspectorButtons = new ArrayList<>();
    private final List<EditBox> inspectorInputs = new ArrayList<>();
    private final Map<String, String> propertyText = new HashMap<>(), propertyErrors = new HashMap<>();
    private record Property(String key, String title, String value, Consumer<String> edit) {}
    private List<Property> properties = List.of();
    private final double originalZoom;
    private final boolean originalAttached, originalHidden;
    private double editorZoom;
    private boolean initialized, nativePan;
    private boolean terrainMatches = true;
    private int topHeight, propertyPage;
    private boolean inspectorOpen;
    private String inspectorSelection = "";
    private Point lastMouse;
    private NativeMapEditorLayout layout;
    private Button undoButton, redoButton, deleteButton, acceptButton, gameButton;
    private NativeMapProjection projection;

    private NativeXaeroEditorScreen(Screen parent, MapDefinition draft, Consumer<MapDefinition> onAccept,
            Runnable onDeleteMap, MapProcessor processor) {
        super(parent, parent, processor, Minecraft.getInstance().player);
        this.parent = parent; this.model = new NativeMapEditModel(draft);
        model.zoneTools(ConfigUi.developerMode());
        model.tool(draft.id() == 0 ? Tool.MAP : Tool.SELECT);
        this.onAccept = onAccept; this.onDeleteMap = onDeleteMap;
        originalZoom = FIELDS.number(FIELDS.destScale, null);
        originalAttached = FIELDS.flag(FIELDS.attachedCamera, null);
        originalHidden = GuiMap.hiddenUI;
        editorZoom = originalZoom;
        inspectorOpen = minecraftWidth() >= 580;
    }

    private static int minecraftWidth() { return Minecraft.getInstance().getWindow().getGuiScaledWidth(); }

    /** Invoked reflectively by the optional facade after exact-version validation. */
    public static boolean open(Screen parent, MapDefinition draft, Consumer<MapDefinition> onAccept, Runnable onDeleteMap) {
        var minecraft = Minecraft.getInstance();
        var session = WorldMapSession.getCurrentSession();
        if (minecraft.player == null || minecraft.level == null || session == null || !session.isUsable()
                || session.getMapProcessor() == null || session.getMapProcessor().getMapWorld() == null) {
            NativeMapEditor.unavailable(Texts.tr("screen.tacz_pubg.map_editor.enter_world_wait_xaero_terrain_map_load_then")); return false;
        }
        var processor = session.getMapProcessor();
        var dimension = processor.getMapWorld().getCurrentDimensionId();
        if (dimension == null || !dimension.location().equals(draft.dimension())) {
            NativeMapEditor.unavailable(Texts.tr("screen.tacz_pubg.map_editor.switch_xaero_map_before_opening_editor", draft.dimension())); return false;
        }
        if (!MapZoneModel.validate(draft).isEmpty()) {
            NativeMapEditor.unavailable(Texts.tr("screen.tacz_pubg.map_editor.fix_map_configuration_first", MapZoneModel.validate(draft).getFirst())); return false;
        }
        minecraft.setScreen(new NativeXaeroEditorScreen(parent, draft, onAccept, onDeleteMap, processor));
        return true;
    }

    @Override public void init() {
        super.init();
        // Keep GuiMap's render/navigation internals, replace only authoring-screen buttons.
        // Removing export/settings/waypoint buttons prevents an editing drag from mutating those stores.
        // ScreenBase also maintains a private dropdown list; its removeWidget removes both lists.
        for (var widget : List.copyOf(children())) removeWidget(widget);
        clearWidgets(); editorButtons.clear(); toolButtons.clear(); inspectorButtons.clear(); inspectorInputs.clear();
        waypointMenu = false; playersMenu = false; closeRightClick();
        FIELDS.write(FIELDS.attachedCamera, null, false); GuiMap.hiddenUI = false;
        FIELDS.write(FIELDS.destScale, null, editorZoom);
        FIELDS.write(FIELDS.userScale, this, editorZoom);
        FIELDS.write(FIELDS.zoomAnim, this, null);
        FIELDS.write(FIELDS.shouldResetCameraPos, this, false);
        NativeMapEditor.attach(this, model::draft);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.example.examplemod.spawn.SpawnAreaPayloads.ToolRequest(
                com.example.examplemod.spawn.SpawnAreaPayloads.ToolAction.MAP_PREVIEW, model.draft().dimension()));
        layout = NativeMapEditorLayout.of(width, height, inspectorOpen);
        // Dedicated child authoring page: only map tools, without parent configuration tabs.
        availableTools.clear();
        for (Tool tool : Tool.values()) if (ConfigUi.developerMode() || tool != Tool.SAFE && tool != Tool.BOMB) availableTools.add(tool);
        int columns = Math.max(6, availableTools.size());
        int buttonWidth = (width - 36) / columns;
        int index = 0;
        for (var tool : availableTools) {
            int x = 8 + index * (buttonWidth + 4), y = 42;
            var button = button(x, y, buttonWidth, tool.title(), () -> activateTool(tool));
            button.setTooltip(Tooltip.create(Texts.component((index + 1) + "：" + tool.title())));
            toolButtons.add(button); index++;
        }
        int row = 65;
        undoButton = button(8, row, buttonWidth, Texts.tr("screen.tacz_pubg.map_editor.undo"), () -> { model.undo(); resetProperties(); });
        redoButton = button(12 + buttonWidth, row, buttonWidth, Texts.tr("screen.tacz_pubg.map_editor.redo"), () -> { model.redo(); resetProperties(); });
        deleteButton = button(16 + buttonWidth * 2, row, buttonWidth, Texts.tr("screen.tacz_pubg.map_config.delete"), this::deleteSelected);
        button(20 + buttonWidth * 3, row, buttonWidth, Texts.tr("screen.tacz_pubg.map_editor.frame"), this::fit);
        button(24 + buttonWidth * 4, row, buttonWidth, Texts.tr("screen.tacz_pubg.map_editor.player"), this::centerPlayer);
        button(28 + buttonWidth * 5, row, buttonWidth, inspectorOpen ? Texts.tr("screen.tacz_pubg.map_editor.hide_properties") : Texts.tr("screen.tacz_pubg.map_editor.properties"), () -> {
            inspectorOpen = !inspectorOpen; init(); fit();
        });
        topHeight = layout.top();
        int bottomButtonWidth = (width - 28) / 4;
        acceptButton = button(20 + bottomButtonWidth * 3, height - 25, bottomButtonWidth, Texts.tr("screen.tacz_pubg.configuration.save"), this::accept);
        gameButton = button(12 + bottomButtonWidth, height - 25, bottomButtonWidth, Texts.tr("screen.tacz_pubg.map_editor.use_match"), this::createGame);
        button(16 + bottomButtonWidth * 2, height - 25, bottomButtonWidth, Texts.tr("screen.tacz_pubg.map_list.title"), this::mapList);
        button(8, height - 25, bottomButtonWidth, Texts.tr("screen.tacz_pubg.map_editor.cancel_editing"), this::discard);
        if (parent instanceof MapZoneConfigScreen map && map.entityRecordingPending(model.draft().id()))
            button(width - 148, height - 50, 140, Texts.tr("screen.tacz_pubg.map_entities.choose"),
                    () -> map.openEntityRecordingChoice(this, model.draft()));
        rebuildInspector();
        if (!initialized) { fit(); initialized = true; }
        else model.cancelDrag();
    }

    private Button button(int x, int y, int width, String title, Runnable action) {
        var button = ConfigUi.button(Texts.component(title), ignored -> action.run()).bounds(x, y, width, 20).build();
        editorButtons.add(button);
        // Narration/focus are native Screen widgets; render after GuiMap has restored its GUI pose.
        addWidget(button); return button;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        var world = getMapProcessor().getMapWorld();
        terrainMatches = world != null && world.getCurrentDimensionId() != null
                && world.getCurrentDimensionId().location().equals(model.draft().dimension());
        if (!terrainMatches) { model.cancelDrag(); stopNativePan(); }
        var window = Minecraft.getInstance().getWindow();
        projection = new NativeMapProjection(FIELDS.number(FIELDS.cameraX, this), FIELDS.number(FIELDS.cameraZ, this),
                FIELDS.number(FIELDS.scale, this), window.getWidth(), window.getHeight(), width, height);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 600);
        if (projection.valid() && terrainMatches) {
            graphics.enableScissor(0, topHeight, layout.mapRight(), layout.bottom());
            graphics.flush();
            drawLoot(graphics);
            drawStaging(graphics, 0xff66ddcc);
            drawSelection(graphics);
            graphics.flush();
            graphics.disableScissor();
        }
        renderMenuBackground(graphics, 0, 0, width, topHeight);
        renderMenuBackground(graphics, 0, layout.bottom(), width, height - layout.bottom());
        String status = !propertyErrors.isEmpty() ? propertyErrors.values().iterator().next()
                : terrainMatches ? model.message() : Texts.tr("screen.tacz_pubg.map_editor.base_map_dimension_changed_saving_editing_locked_switch");
        ConfigUi.renderHeader(graphics, font, width, Texts.tr("screen.tacz_pubg.map_editor.create_map_xaero_terrain"), status,
                !propertyErrors.isEmpty() || !terrainMatches ? 0xffff8888 : 0xffc5defa);
        if (projection.valid() && layout.inMap(mouseX, mouseY)) lastMouse = projection.world(mouseX, mouseY);
        var map = model.draft();
        String mouse = lastMouse == null ? Texts.tr("screen.tacz_pubg.map_editor.mouse_x_z") : Texts.tr("screen.tacz_pubg.map_editor.mouse_x_z.render", number(lastMouse.x()), number(lastMouse.z()));
        readout(graphics, mouse + Texts.tr("screen.tacz_pubg.map_editor.width") + number(map.maxX() - map.minX()) + Texts.tr("screen.tacz_pubg.map_editor.depth") + number(map.maxZ() - map.minZ()), height - 68, 0xfff9e699);
        readout(graphics, "A(min) " + number(map.minX()) + ", " + number(map.minZ()) + "   B(max) "
                + number(map.maxX()) + ", " + number(map.maxZ()), height - 57, 0xffc5defa);
        readout(graphics, model.describeSelection(), height - 46, 0xffffdf57);
        if (inspectorOpen) renderInspector(graphics, mouseX, mouseY, partialTick);
        undoButton.active = terrainMatches && model.canUndo(); redoButton.active = terrainMatches && model.canRedo();
        deleteButton.active = terrainMatches && model.selected() != null;
        acceptButton.active = terrainMatches && propertyErrors.isEmpty() && !model.dragging() && MapZoneModel.validate(model.draft()).isEmpty();
        acceptButton.visible = model.dirty() || model.draft().id() == 0;
        gameButton.active = acceptButton.active && parent instanceof MapZoneConfigScreen;
        for (int i = 0; i < toolButtons.size(); i++) {
            toolButtons.get(i).active = terrainMatches;
            toolButtons.get(i).setMessage(Texts.component(
                    (model.tool() == availableTools.get(i) ? "● " : "") + availableTools.get(i).title()));
        }
        for (var button : editorButtons) button.render(graphics, mouseX, mouseY, partialTick);
        graphics.pose().popPose(); graphics.flush();
    }

    private void readout(GuiGraphics graphics, String text, int y, int color) {
        float available = width - 16f - (parent instanceof MapZoneConfigScreen map && map.entityRecordingPending(model.draft().id()) ? 150 : 0);
        float scale = Math.min(1f, available / Math.max(1, font.width(Texts.resolve(text))));
        graphics.pose().pushPose();
        graphics.pose().translate(8, y, 0); graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, Texts.resolve(text), 0, 0, color);
        graphics.pose().popPose();
    }

    private void resetProperties() {
        propertyText.clear(); propertyErrors.clear(); propertyPage = 0;
        rebuildInspector();
    }

    private void rebuildInspector() {
        for (var input : inspectorInputs) removeWidget(input);
        inspectorInputs.clear();
        for (var widget : inspectorButtons) { removeWidget(widget); editorButtons.remove(widget); }
        inspectorButtons.clear();
        var map = model.draft();
        var selected = model.selected();
        inspectorSelection = String.valueOf(selected) + "/" + map.bombingZones().size();
        if (!inspectorOpen || layout == null) return;
        var list = new ArrayList<Property>();
        if (selected != null && selected.kind() == Kind.MAP) {
            list.add(new Property("name", Texts.tr("screen.tacz_pubg.map_config.map_name"), map.name(), value -> model.properties(value, model.draft().blueDamagePerSecond())));
            list.add(new Property("mapA", Texts.tr("screen.tacz_pubg.map_editor.minimum_x_z"), number(map.minX()) + ", " + number(map.minZ()), null));
            list.add(new Property("mapB", Texts.tr("screen.tacz_pubg.map_editor.maximum_x_z"), number(map.maxX()) + ", " + number(map.maxZ()), null));
            list.add(new Property("mapSize", Texts.tr("screen.tacz_pubg.map_editor.map_width_depth"), number(map.maxX() - map.minX()) + " × " + number(map.maxZ() - map.minZ()), null));
        }
        if (selected != null && selected.kind() == Kind.SAFE) {
            list.add(new Property("blueDamage", Texts.tr("screen.tacz_pubg.map_editor.blue_zone_damage_outside_safe_zone_hp_s"), Double.toString(map.blueDamagePerSecond()),
                    value -> model.properties(model.draft().name(), finite(value, Texts.tr("screen.tacz_pubg.map_editor.blue_zone_damage")))));
        }
        if (selected != null && selected.kind() == Kind.SPAWN && map.stagingSpawn() != null) {
            var spawn = map.stagingSpawn();
            list.add(new Property("stagingCenter", Texts.tr("screen.tacz_pubg.map_staging.center"),
                    number(spawn.centerX()) + ", " + number(spawn.centerZ()), null));
            list.add(new Property("stagingRadius", Texts.tr("screen.tacz_pubg.map_staging.radius"),
                    Double.toString(spawn.radius()), value -> model.stagingRadius(finite(value, Texts.tr("screen.tacz_pubg.map_staging.radius")))));
        }
        if (selected != null) {
            var circle = selected.kind() == Kind.SAFE ? map.safeZone() : selected.kind() == Kind.BOMB
                    && selected.index() < map.bombingZones().size() ? map.bombingZones().get(selected.index()).area() : null;
            if (circle != null) {
                list.add(new Property("center", Texts.tr("screen.tacz_pubg.map_editor.center_x_z_drag_adjust"), number(circle.centerX()) + ", " + number(circle.centerZ()), null));
                list.add(new Property("radius", selected.kind() == Kind.BOMB ? Texts.tr("screen.tacz_pubg.map_editor.bombardment_zone_radius_drag_adjust") : Texts.tr("screen.tacz_pubg.map_editor.safe_zone_radius_drag_adjust"), number(circle.radius()), null));
            }
            if (selected.kind() == Kind.BOMB && selected.index() < map.bombingZones().size()) {
                int index = selected.index();
                var bomb = map.bombingZones().get(index);
                list.add(new Property("bomb." + index + ".missiles", Texts.tr("screen.tacz_pubg.map_editor.missiles_per_minute"), Integer.toString(bomb.missilesPerMinute()),
                        value -> updateBombProperties(index)));
                list.add(new Property("bombProperties", Texts.tr("screen.tacz_pubg.map_editor.bombardment_properties_shared_colors"), Texts.tr("screen.tacz_pubg.map_editor.adjust_general_settings"), null));
            }
            if (selected.kind() == Kind.FLIGHT && map.flightPath() != null) {
                var path = map.flightPath();
                list.add(new Property("flightA", Texts.tr("screen.tacz_pubg.map_editor.route_start_x_z"), number(path.startX()) + ", " + number(path.startZ()), null));
                list.add(new Property("flightB", Texts.tr("screen.tacz_pubg.map_editor.route_end_x_z"), number(path.endX()) + ", " + number(path.endZ()), null));
            }
        }
        if (selected == null) list.add(new Property("selection", Texts.tr("screen.tacz_pubg.map_editor.object_properties"), Texts.tr("screen.tacz_pubg.map_editor.select_map_boundary_flight_route"), null));
        properties = List.copyOf(list);
        int rows = layout.propertyRows(), pages = Math.max(1, (properties.size() + rows - 1) / rows);
        propertyPage = Math.clamp(propertyPage, 0, pages - 1);
        int x = layout.mapRight() + 7, fieldWidth = Math.max(30, layout.inspectorWidth() - 14);
        for (int i = propertyPage * rows; i < Math.min(properties.size(), (propertyPage + 1) * rows); i++) {
            var property = properties.get(i);
            int y = topHeight + 14 + (i % rows) * 38;
            var input = ConfigUi.editBox(font, x, y, fieldWidth, 20, Texts.component(property.title()));
            input.setMaxLength(property.key().equals("name") ? 80 : 40);
            input.setValue(Texts.resolve(property.edit() == null ? property.value() : propertyText.getOrDefault(property.key(), property.value())));
            input.setEditable(property.edit() != null);
            input.setTooltip(Tooltip.create(Texts.component(property.key().startsWith("staging")
                    ? Texts.tr(property.key().equals("stagingRadius") ? "screen.tacz_pubg.map_staging.radius_hint" : "screen.tacz_pubg.map_staging.tool_hint")
                    : property.key().equals("bombProperties")
                    ? Texts.tr("screen.tacz_pubg.map_editor.general_settings_bombardment_display_colors_adjust_zone_positions")
                    : property.key().equals("radius") && model.selected() != null && model.selected().kind() == Kind.BOMB
                    ? Texts.tr("screen.tacz_pubg.map_editor.bombardment_zone_radius_determines_map_area_impacts_drag") : property.title() + (property.edit() == null
                    ? Texts.tr("screen.tacz_pubg.map_editor.drag_base_map_adjust_geometry_developer_mode_supports") : Texts.tr("screen.tacz_pubg.map_editor.local_draft_validated_again_by_server_save")))));
            if (property.edit() != null) input.setResponder(value -> {
                propertyText.put(property.key(), value);
                try { property.edit().accept(value); propertyErrors.remove(property.key()); }
                catch (IllegalArgumentException error) { propertyErrors.put(property.key(), error.getMessage()); }
            });
            addWidget(input); inspectorInputs.add(input);
        }
        int pagerWidth = Math.max(25, (fieldWidth - 4) / 2);
        var previous = button(x, layout.bottom() - 20, pagerWidth, "< " + (propertyPage + 1), () -> { propertyPage--; rebuildInspector(); });
        previous.active = propertyPage > 0; inspectorButtons.add(previous);
        var next = button(x + pagerWidth + 4, layout.bottom() - 20, pagerWidth, pages + " >", () -> { propertyPage++; rebuildInspector(); });
        next.active = propertyPage + 1 < pages; inspectorButtons.add(next);
    }

    private void updateBombProperties(int index) {
        var bomb = model.draft().bombingZones().get(index);
        String prefix = "bomb." + index + ".";
        int missiles;
        try { missiles = Integer.parseInt(propertyText.getOrDefault(prefix + "missiles", Integer.toString(bomb.missilesPerMinute())).trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.map_editor.missiles_per_minute_integer")); }
        model.bombingProperties(index, missiles, bomb.missileDamage(), bomb.blastRadius());
        propertyErrors.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static double finite(String value, String label) {
        try {
            double result = Double.parseDouble(value.trim());
            if (Double.isFinite(result)) return result;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.map_editor.finite_number_input_retained"));
    }

    private void renderInspector(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        String selection = String.valueOf(model.selected()) + "/" + model.draft().bombingZones().size();
        if (!selection.equals(inspectorSelection) && !model.dragging()) { propertyPage = 0; rebuildInspector(); }
        renderMenuBackground(graphics, layout.mapRight(), topHeight, width - layout.mapRight(), layout.bottom() - topHeight);
        graphics.fill(layout.mapRight(), topHeight, layout.mapRight() + 1, layout.bottom(), 0xff808080);
        int rows = layout.propertyRows();
        for (int i = propertyPage * rows; i < Math.min(properties.size(), (propertyPage + 1) * rows); i++) {
            var property = properties.get(i);
            graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.resolve(property.title()), layout.inspectorWidth() - 14)),
                    layout.mapRight() + 7, topHeight + 2 + (i % rows) * 38,
                    propertyErrors.containsKey(property.key()) ? 0xffff8888 : 0xffc5defa);
        }
        for (var input : inspectorInputs) input.render(graphics, mouseX, mouseY, partialTick);
    }

    private void navigate(int index) {
        if (index == 2 || !propertyErrors.isEmpty()) return;
        stopNativePan(); model.cancelDrag();
        if (parent instanceof MapZoneConfigScreen map) map.navigateNativeDraft(model.draft(), index);
    }

    private void drawLoot(GuiGraphics graphics) {
        var stateRevision = com.example.examplemod.spawn.client.SpawnAreaClientState.geometryRevision();
        if (stateRevision != lootRevision) {
            lootColumns = com.example.examplemod.spawn.LootMapShading.columns(com.example.examplemod.spawn.client.SpawnAreaClientState.mapFloors(model.draft().dimension()));
            lootRevision = stateRevision;
        }
        var style = model.draft().visualStyle();
        boolean global = com.example.examplemod.spawn.client.SpawnAreaClientState.globalLootPalette();
        var palette = global ? com.example.examplemod.spawn.client.SpawnAreaClientState.lootPalette() : style.lootTierRgb();
        int layers = global ? com.example.examplemod.spawn.client.SpawnAreaClientState.overlapBlackLayers() : style.lootOverlapBlackLayers();
        for (var column : lootColumns) {
            var a = projection.screen(column.x(), column.z());
            var b = projection.screen(column.x() + 1, column.z() + 1);
            int x1 = Math.max(0, (int) Math.floor(Math.min(a.x(), b.x()))), x2 = Math.min(layout.mapRight(), (int) Math.ceil(Math.max(a.x(), b.x())));
            int z1 = Math.max(topHeight, (int) Math.floor(Math.min(a.z(), b.z()))), z2 = Math.min(layout.bottom(), (int) Math.ceil(Math.max(a.z(), b.z())));
            if (x2 > x1 && z2 > z1) graphics.fill(x1, z1, x2, z2, com.example.examplemod.spawn.LootMapShading.color(column, palette, layers));
        }
    }

    private void drawSelection(GuiGraphics graphics) {
        var selected = model.selected();
        if (selected == null) return;
        var map = model.draft();
        int color = 0xffffdf57;
        switch (selected.kind()) {
            case SPAWN -> drawStaging(graphics, color);
            case MAP -> {
                line(graphics, new Point(map.minX(), map.minZ()), new Point(map.maxX(), map.minZ()), color);
                line(graphics, new Point(map.maxX(), map.minZ()), new Point(map.maxX(), map.maxZ()), color);
                line(graphics, new Point(map.maxX(), map.maxZ()), new Point(map.minX(), map.maxZ()), color);
                line(graphics, new Point(map.minX(), map.maxZ()), new Point(map.minX(), map.minZ()), color);
            }
            case SAFE, BOMB -> {
                var circle = selected.kind() == Kind.SAFE ? map.safeZone()
                        : selected.index() < map.bombingZones().size() ? map.bombingZones().get(selected.index()).area() : null;
                if (circle != null) {
                    Point previous = new Point(circle.centerX() + circle.radius(), circle.centerZ());
                    // A fixed upper bound covers an arbitrarily large world circle.
                    for (int i = 1; i <= 192; i++) {
                        double angle = i * Math.PI * 2 / 192;
                        Point next = new Point(circle.centerX() + Math.cos(angle) * circle.radius(), circle.centerZ() + Math.sin(angle) * circle.radius());
                        line(graphics, previous, next, color); previous = next;
                    }
                }
            }
            case FLIGHT -> {
                var path = map.flightPath();
                if (path != null) line(graphics, new Point(path.startX(), path.startZ()), new Point(path.endX(), path.endZ()), color);
            }
        }
        for (var handle : model.handles()) {
            var p = projection.screen(handle.point().x(), handle.point().z());
            if (p.x() < -8 || p.x() > layout.mapRight() + 8 || p.z() < topHeight - 8 || p.z() > layout.bottom() + 8) continue;
            int x = (int) Math.round(p.x()), y = (int) Math.round(p.z());
            graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xff222222);
            graphics.fill(x - 3, y - 3, x + 4, y + 4, color);
        }
    }

    private void drawStaging(GuiGraphics graphics, int color) {
        var circle = model.draft().stagingSpawn();
        if (circle == null) return;
        Point previous = new Point(circle.centerX() + circle.radius(), circle.centerZ());
        for (int i = 1; i <= 96; i++) {
            double angle = i * Math.PI * 2 / 96;
            Point next = new Point(circle.centerX() + circle.radius() * Math.cos(angle),
                    circle.centerZ() + circle.radius() * Math.sin(angle));
            line(graphics, previous, next, color); previous = next;
        }
        var center = projection.screen(circle.centerX(), circle.centerZ());
        int x = (int) Math.round(center.x()), y = (int) Math.round(center.z());
        if (center.x() >= -8 && center.x() <= layout.mapRight() + 8 && center.z() >= topHeight - 8 && center.z() <= layout.bottom() + 8) {
            int radius = projection.stagingMarkerRadiusPixels();
            graphics.pose().pushPose();
            graphics.pose().translate(center.x(), center.z(), 0);
            graphics.pose().scale((float) projection.guiWidth() / projection.framebufferWidth(),
                    (float) projection.guiHeight() / projection.framebufferHeight(), 1);
            graphics.fill(-radius, 0, radius + 1, 1, color);
            graphics.fill(0, -radius, 1, radius + 1, color);
            graphics.pose().popPose();
        }
    }

    private void activateTool(Tool tool) {
        stopNativePan(); model.tool(tool);
        if (tool == Tool.SPAWN && !inspectorOpen) { inspectorOpen = true; init(); }
    }

    private void line(GuiGraphics graphics, Point worldA, Point worldB, int color) {
        Point a = projection.screen(worldA.x(), worldA.z()), b = projection.screen(worldB.x(), worldB.z());
        // Liang-Barsky first; no huge-coordinate float vertices or unbounded pixel stepping.
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double[] range = {0, 1};
        if (!clip(-dx, a.x(), range) || !clip(dx, layout.mapRight() - a.x(), range)
                || !clip(-dz, a.z() - topHeight, range) || !clip(dz, layout.bottom() - a.z(), range)) return;
        double x1 = a.x() + range[0] * dx, z1 = a.z() + range[0] * dz;
        double x2 = a.x() + range[1] * dx, z2 = a.z() + range[1] * dz;
        double length = Math.hypot(x2 - x1, z2 - z1);
        if (length < .001) return;
        double nx = -(z2 - z1) / length, nz = (x2 - x1) / length;
        var out = graphics.bufferSource().getBuffer(RenderType.gui());
        var pose = graphics.pose().last().pose();
        // Same front-face winding as GuiGraphics.fill; each clipped segment is one bounded quad.
        out.addVertex(pose, (float) (x1 + nx), (float) (z1 + nz), 0).setColor(color);
        out.addVertex(pose, (float) (x2 + nx), (float) (z2 + nz), 0).setColor(color);
        out.addVertex(pose, (float) (x2 - nx), (float) (z2 - nz), 0).setColor(color);
        out.addVertex(pose, (float) (x1 - nx), (float) (z1 - nz), 0).setColor(color);
    }
    private static boolean clip(double p, double q, double[] range) {
        if (p == 0) return q >= 0;
        double r = q / p;
        if (p < 0) { if (r > range[1]) return false; range[0] = Math.max(range[0], r); }
        else { if (r < range[0]) return false; range[1] = Math.min(range[1], r); }
        return true;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!layout.inMap(mouseX, mouseY)) {
            for (var input : inspectorInputs) if (input.mouseClicked(mouseX, mouseY, button)) { setFocused(input); return true; }
            for (var widget : editorButtons) if (widget.mouseClicked(mouseX, mouseY, button)) { setFocused(widget); return true; }
            return true;
        }
        setFocused(null);
        if (button == 1) { model.cancelDrag(); stopNativePan(); return true; }
        if (button != 0 || projection == null || !projection.valid() || !terrainMatches) return true;
        // Stop a just-started native zoom animation while authoring a shape; subsequent mouse
        // coordinates then use one stable native projection throughout this drag.
        if (model.tool() != Tool.PAN) {
            editorZoom = FIELDS.number(FIELDS.userScale, this);
            FIELDS.write(FIELDS.destScale, null, editorZoom); FIELDS.write(FIELDS.zoomAnim, this, null);
            FIELDS.write(FIELDS.cameraDestinationAnimX, this, null); FIELDS.write(FIELDS.cameraDestinationAnimZ, this, null);
        }
        if (model.begin(projection.world(mouseX, mouseY), projection.tolerance(7))) {
            if (model.tool() == Tool.SPAWN) resetProperties();
            return true;
        }
        nativePan = true; return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && model.dragging() && projection != null) { model.update(worldAt(x, y), hasShiftDown()); return true; }
        if (nativePan && !layout.inMap(x, y)) { stopNativePan(); return true; }
        return nativePan && super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && model.dragging() && projection != null) { model.finish(worldAt(x, y), hasShiftDown()); rebuildInspector(); return true; }
        if (nativePan) { stopNativePan(); return true; }
        for (var widget : editorButtons) widget.mouseReleased(x, y, button);
        for (var input : inspectorInputs) input.mouseReleased(x, y, button);
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!layout.inMap(x, y) || model.dragging() || !terrainMatches) return true;
        super.mouseScrolled(x, y, horizontal, vertical); return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_TAB) {
            var focus = new ArrayList<net.minecraft.client.gui.components.events.GuiEventListener>();
            focus.addAll(editorButtons); focus.addAll(inspectorInputs);
            int next = focus.indexOf(getFocused()) + (hasShiftDown() ? -1 : 1);
            setFocused(focus.get(Math.floorMod(next, focus.size()))); return true;
        }
        if (getFocused() instanceof EditBox input && key != GLFW.GLFW_KEY_ESCAPE) {
            input.keyPressed(key, scan, modifiers); return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { if (!model.cancelDrag()) discard(); return true; }
        if (!terrainMatches) return true;
        if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) { deleteSelected(); return true; }
        if (hasControlDown() && key == GLFW.GLFW_KEY_Z) { if (hasShiftDown()) model.redo(); else model.undo(); resetProperties(); return true; }
        if (hasControlDown() && key == GLFW.GLFW_KEY_Y) { model.redo(); resetProperties(); return true; }
        if (key >= GLFW.GLFW_KEY_1 && key < GLFW.GLFW_KEY_1 + availableTools.size()) {
            activateTool(availableTools.get(key - GLFW.GLFW_KEY_1)); return true;
        }
        if (key == GLFW.GLFW_KEY_F) { fit(); return true; }
        if (getFocused() != null) return getFocused().keyPressed(key, scan, modifiers);
        // Consume native waypoint/export/dimension shortcuts in an authoring session.
        return true;
    }
    @Override public boolean charTyped(char character, int modifiers) {
        return getFocused() instanceof EditBox input && input.charTyped(character, modifiers);
    }
    @Override public boolean keyReleased(int key, int scan, int modifiers) { return true; }
    @Override public void mapClicked(int button, int x, int y) { /* Native waypoint/context actions are disabled in the editor. */ }
    @Override public void onClose() { discard(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        if (Minecraft.getInstance().getConnection() != null)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.example.examplemod.spawn.SpawnAreaPayloads.ToolRequest(
                    com.example.examplemod.spawn.SpawnAreaPayloads.ToolAction.END_MAP_PREVIEW));
        editorZoom = FIELDS.number(FIELDS.destScale, null);
        NativeMapEditor.detach(this);
        FIELDS.write(FIELDS.destScale, null, originalZoom);
        FIELDS.write(FIELDS.attachedCamera, null, originalAttached);
        GuiMap.hiddenUI = originalHidden;
        super.removed();
        nativePan = false;
    }
    private Point worldAt(double x, double y) {
        return projection.world(Math.clamp(x, 0, layout.mapRight() - .01), Math.clamp(y, topHeight, layout.bottom() - .01));
    }
    private void stopNativePan() {
        if (!nativePan) return;
        nativePan = false;
        // GuiMap.mouseReleased also executes configurable waypoint key actions. End only the
        // native navigation state here, so rebound mouse buttons can never create a waypoint.
        var left = (MapMouseButtonPress) FIELDS.read(FIELDS.leftMouseButton, this);
        left.isDown = false; left.clicked = false; left.pressedAtX = -1; left.pressedAtY = -1;
        FIELDS.write(FIELDS.mouseDownPosX, this, -1); FIELDS.write(FIELDS.mouseDownPosY, this, -1);
    }
    private void accept() {
        if (!terrainMatches || !propertyErrors.isEmpty() || model.dragging() || !MapZoneModel.validate(model.draft()).isEmpty()) return;
        Minecraft.getInstance().setScreen(parent); onAccept.accept(model.draft());
    }
    private void createGame() {
        if (!terrainMatches || !propertyErrors.isEmpty() || model.dragging() || !MapZoneModel.validate(model.draft()).isEmpty()) return;
        if (parent instanceof MapZoneConfigScreen map) {
            Minecraft.getInstance().setScreen(parent);
            map.saveNativeDraft(model.draft(), true);
        }
    }
    private void mapList() {
        if (!propertyErrors.isEmpty()) return;
        stopNativePan(); model.cancelDrag();
        if (parent instanceof MapZoneConfigScreen map) map.listNativeDraft(model.draft());
        else discard();
    }
    private void discard() {
        if (model.cancelDrag()) return;
        stopNativePan();
        boolean parentDirty = parent instanceof MapZoneConfigScreen map && map.hasUnsavedChanges();
        if (!model.dirty() && !parentDirty && propertyErrors.isEmpty()) { returnToMapList(); return; }
        Minecraft.getInstance().setScreen(new ConfirmScreen(yes -> {
            if (yes) returnToMapList();
            else Minecraft.getInstance().setScreen(this);
        }, Texts.of("screen.tacz_pubg.map_editor.discard_confirmation"), Texts.of("screen.tacz_pubg.map_editor.discard_map_draft_return_map_list"),
                Texts.of("screen.tacz_pubg.map_editor.discard_return_list"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }
    private void returnToMapList() {
        if (parent instanceof MapZoneConfigScreen map) map.discardNativeDraft();
        else Minecraft.getInstance().setScreen(parent);
    }
    private void deleteSelected() {
        if (model.selected() != null && model.selected().kind() == Kind.MAP && onDeleteMap != null) {
            if (!propertyErrors.isEmpty()) return;
            stopNativePan(); model.cancelDrag();
            if (parent instanceof MapZoneConfigScreen map) map.deleteNativeDraft(model.draft());
            else { Minecraft.getInstance().setScreen(parent); onDeleteMap.run(); }
        } else { model.deleteSelection(); resetProperties(); }
    }
    private void fit() {
        if (width <= 0 || height <= 0) return;
        stopNativePan(); model.cancelDrag();
        var map = model.draft(); var window = Minecraft.getInstance().getWindow();
        double availableWidth = layout.mapRight() * .86 * window.getWidth() / width;
        double availableHeight = Math.max(10, layout.bottom() - topHeight - 16) * (double) window.getHeight() / height;
        double scale = Math.min(availableWidth / (map.maxX() - map.minX()), availableHeight / (map.maxZ() - map.minZ()));
        double nativeMultiplier = Math.max(1, Math.min(window.getWidth(), window.getHeight()) / 1080.0);
        editorZoom = Math.clamp(scale / nativeMultiplier, .0625, 50);
        FIELDS.write(FIELDS.destScale, null, editorZoom); FIELDS.write(FIELDS.userScale, this, editorZoom);
        FIELDS.write(FIELDS.zoomAnim, this, null);
        // GuiMap's origin remains the whole framebuffer center; compensate asymmetric toolbars.
        double projectedScale = editorZoom * nativeMultiplier;
        double viewportMiddle = (topHeight + layout.bottom()) * .5;
        FIELDS.write(FIELDS.cameraX, this, (map.minX() + map.maxX()) * .5
                - (layout.mapRight() * .5 - width * .5) * window.getWidth() / width / projectedScale);
        FIELDS.write(FIELDS.cameraZ, this, (map.minZ() + map.maxZ()) * .5
                - (viewportMiddle - height * .5) * window.getHeight() / height / projectedScale);
        FIELDS.write(FIELDS.shouldResetCameraPos, this, false);
        FIELDS.write(FIELDS.cameraDestinationAnimX, this, null); FIELDS.write(FIELDS.cameraDestinationAnimZ, this, null);
    }
    private void centerPlayer() {
        stopNativePan(); model.cancelDrag();
        var player = Minecraft.getInstance().player;
        if (player == null || !player.level().dimension().location().equals(model.draft().dimension())) return;
        var window = Minecraft.getInstance().getWindow();
        double scale = FIELDS.number(FIELDS.scale, this);
        if (!Double.isFinite(scale) || scale <= 0) return;
        FIELDS.write(FIELDS.cameraX, this, player.getX() - (layout.mapRight() * .5 - width * .5) * window.getWidth() / width / scale);
        FIELDS.write(FIELDS.cameraZ, this, player.getZ() - ((topHeight + layout.bottom()) * .5 - height * .5) * window.getHeight() / height / scale);
    }
    private static String number(double value) { return String.format(java.util.Locale.ROOT, "%.1f", value); }

    /** Cached field adapter for the exact installed GuiMap 1.47.0 bytecode contract. */
    private static final class NativeFields {
        final Field cameraX = field("cameraX"), cameraZ = field("cameraZ"), scale = field("scale"), userScale = field("userScale"),
                destScale = field("destScale"), shouldResetCameraPos = field("shouldResetCameraPos"), attachedCamera = field("attachedCamera"),
                zoomAnim = field("zoomAnim"), cameraDestinationAnimX = field("cameraDestinationAnimX"), cameraDestinationAnimZ = field("cameraDestinationAnimZ"),
                leftMouseButton = field("leftMouseButton"), mouseDownPosX = field("mouseDownPosX"), mouseDownPosY = field("mouseDownPosY");
        private static Field field(String name) {
            try { var field = GuiMap.class.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(Texts.tr("error.tacz_pubg.map_editor.unsupported_xaero_guimap_field") + name, error); }
        }
        double number(Field field, Object owner) {
            try { return field.getDouble(owner); } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        boolean flag(Field field, Object owner) {
            try { return field.getBoolean(owner); } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        Object read(Field field, Object owner) {
            try { return field.get(owner); } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        void write(Field field, Object owner, Object value) {
            try { field.set(owner, value); } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
    }
}
