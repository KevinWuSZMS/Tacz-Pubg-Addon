package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.operatorpermissions.OperatorPermissions;
import com.example.examplemod.operatorpermissions.OperatorPermissions.Permission;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native Minecraft child screen; committing uses the root's revision check and save acknowledgement. */
final class OperatorPermissionsScreen extends Screen {
    private static final int TOP = 48, ROW = 38;
    private final LootConfigScreen parent;
    private final Consumer<OperatorPermissions> apply;
    private final OperatorPermissions baseline;
    private OperatorPermissions draft;
    private final List<Button> toggles = new ArrayList<>();
    private Button save;
    private double scroll;

    OperatorPermissionsScreen(LootConfigScreen parent, OperatorPermissions source, Consumer<OperatorPermissions> apply) {
        super(Texts.of("screen.tacz_pubg.operator_permissions.pubg_op_permissions"));
        this.parent = parent; this.apply = apply; baseline = source; draft = source;
    }

    @Override protected void init() {
        toggles.clear();
        for (Permission permission : Permission.values()) {
            Button toggle = addRenderableWidget(ConfigUi.button(label(permission), ignored -> {
                draft = draft.with(permission, !draft.value(permission));
                update();
            }).bounds(10, TOP, width - 28, 20)
                    .tooltip(Tooltip.create(Texts.component(permission.description() + "\n/gamerule " + permission.gameRule()))).build());
            toggles.add(toggle);
        }
        save = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.configuration.save"), ignored -> commit())
                .bounds(width - 100, height - 26, 90, 20).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.operator_permissions.cancel_back"), ignored -> onClose())
                .bounds(10, height - 26, 96, 20).build());
        update();
    }

    private Component label(Permission permission) {
        return Texts.component(permission.label() + "：" + (draft.value(permission) ? Texts.tr("screen.tacz_pubg.common.enabled") : Texts.tr("screen.tacz_pubg.common.disabled")));
    }
    private int bottom() { return height - 54; }
    private void update() {
        scroll = Math.clamp(scroll, 0, Math.max(0, Permission.values().length * ROW - (bottom() - TOP)));
        for (int i = 0; i < toggles.size(); i++) {
            Button button = toggles.get(i);
            int y = TOP + i * ROW - (int) scroll;
            button.setY(y); button.setMessage(label(Permission.values()[i]));
            button.visible = y >= TOP && y + 20 <= bottom(); button.active = button.visible;
        }
        if (save != null) save.visible = !draft.equals(baseline);
    }
    private void commit() {
        apply.accept(draft);
        minecraft.setScreen(parent);
        parent.saveConfiguration();
    }
    @Override public void onClose() {
        if (draft.equals(baseline)) minecraft.setScreen(parent);
        else minecraft.setScreen(new ConfirmScreen(discard -> minecraft.setScreen(discard ? parent : this),
                Texts.of("screen.tacz_pubg.operator_permissions.cancel_op_permission_changes"), Texts.of("screen.tacz_pubg.operator_permissions.discard_page_s_unsaved_changes_keep_other_drafts"),
                Texts.of("screen.tacz_pubg.gun_ammo_supply.discard_page_changes"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (y >= TOP && y < bottom()) { scroll -= dy * ROW; update(); return true; }
        return super.mouseScrolled(x, y, dx, dy);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 12, 0xFFFFFFFF);
        graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.of("screen.tacz_pubg.operator_permissions.only_level_ops_saved_world_synchronized_gamerules").getString(), width - 20)), 10, 32, 0xFFDDDDDD);
        graphics.enableScissor(8, TOP, width - 8, bottom());
        for (int i = 0; i < toggles.size(); i++) {
            int y = TOP + i * ROW - (int) scroll + 23;
            graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.resolve(Permission.values()[i].gameRule()), width - 28)), 12, y, 0xFFAAAAAA);
        }
        graphics.disableScissor();
        graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.of("screen.tacz_pubg.operator_permissions.hover_details_disabling_extra_permission_does_not_disable").getString(), width - 20)), 10, height - 44, 0xFFAAAAAA);
        int contentHeight = Permission.values().length * ROW, viewport = bottom() - TOP;
        if (contentHeight > viewport) {
            int thumb = Math.max(12, viewport * viewport / contentHeight);
            int y = TOP + (int) ((viewport - thumb) * scroll / (contentHeight - viewport));
            graphics.fill(width - 7, TOP, width - 3, bottom(), 0xFF333333);
            graphics.fill(width - 7, y, width - 3, y + thumb, 0xFFAAAAAA);
        }
    }
}
