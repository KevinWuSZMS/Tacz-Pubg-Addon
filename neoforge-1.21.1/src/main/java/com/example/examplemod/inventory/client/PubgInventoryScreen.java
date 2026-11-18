package com.example.examplemod.inventory.client;

import com.example.examplemod.backpack.client.BackpackClient;
import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.backpack.BackpackWeights;
import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.inventory.MatchInventoryService;
import com.example.examplemod.originalicons.client.OriginalIconClient;
import com.example.examplemod.vitals.client.CombatHudNumbers;
import com.example.examplemod.vitals.client.CombatHudTextures;
import com.example.examplemod.vitals.client.WeaponHudData;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static com.example.examplemod.inventory.client.PubgInventoryInteractionRules.*;

/** A view over real inventory slots. All transfers are confirmed by the server. */
public final class PubgInventoryScreen extends Screen {
    private static final int TEXT = 0xffeeeeee, MUTED = 0xffb6b6b6, LINE = 0x407e858a;
    private static final int[] EQUIPMENT = {39, 38, 37, 36, 41};
    private static final String[] EQUIPMENT_NAMES = {"helmet", "chest", "legs", "feet", "backpack"};
    private final List<Hit> hits = new ArrayList<>();
    private PubgInventoryLayout layout;
    private boolean recent;
    private int inventoryScroll, groundScroll, ticks;
    private Row pressed;
    private boolean dragging;
    private double pressX, pressY;
    private ItemStack tooltip = ItemStack.EMPTY;
    private Component actionTooltip;
    private Row previewSource;
    private Target previewTarget = INVALID;
    private float viewScale = 1;
    private int viewWidth, viewHeight;
    private record Row(boolean ground, int source, ItemStack item, int count, long order,
                       AttachmentType attachmentType, ItemStack ownerGun) {
        Row(boolean ground, int source, ItemStack item, int count, long order) {
            this(ground, source, item, count, order, null, ItemStack.EMPTY);
        }
        boolean mounted() { return attachmentType != null; }
    }
    private record Hit(int x, int y, int w, int h, Row row, int target, String action, AttachmentType attachmentType) {
        Hit(int x, int y, int w, int h, Row row, int target, String action) {
            this(x, y, w, h, row, target, action, null);
        }
        boolean contains(double mx, double my) { return mx >= x && my >= y && mx < x + w && my < y + h; }
    }
    public PubgInventoryScreen() { super(tr("title")); }
    private static Component tr(String key) { return Component.translatable("screen.tacz_pubg.inventory.pubg." + key); }
    @Override protected void init() {
        viewScale = Math.min(1f, Math.min(width / 640f, height / 400f));
        viewWidth = Math.round(width / viewScale); viewHeight = Math.round(height / viewScale);
        layout = PubgInventoryLayout.of(viewWidth, viewHeight);
        PubgInventoryState.requestSnapshot();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) { }
    @Override protected void renderBlurredBackground(float delta) { }
    @Override public void tick() {
        if (minecraft == null || minecraft.player == null || !minecraft.player.isAlive()) { onClose(); return; }
        if (++ticks % 5 == 0) PubgInventoryState.requestSnapshot();
    }
    private ItemStack stack(int slot) {
        if (slot == 41) return PubgInventoryState.backpack();
        return minecraft.player.getInventory().getItem(slot);
    }
    private List<Row> inventoryRows() {
        List<Row> rows = new ArrayList<>();
        var rules = InventoryConfig.current(minecraft.player);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = stack(slot);
            if (item.isEmpty()) continue;
            if (!rules.inPubgInventoryList(slot)) continue;
            int existing = -1;
            for (int i = 0; i < rows.size(); i++)
                if (ItemStack.isSameItemSameComponents(rows.get(i).item, item)) { existing = i; break; }
            if (existing >= 0) {
                Row before = rows.get(existing);
                rows.set(existing, new Row(false, before.source, before.item,
                        before.count + item.getCount(), Math.max(before.order, PubgInventoryState.recent(item))));
            } else rows.add(new Row(false, slot, item.copy(), item.getCount(), PubgInventoryState.recent(item)));
        }
        Comparator<Row> stable = Comparator.comparingInt((Row row) -> PubgInventoryOrder.rank(row.item))
                .thenComparing(row -> row.item.getHoverName().getString()).thenComparingInt(Row::source);
        rows.sort(recent ? Comparator.comparingLong(Row::order).reversed().thenComparing(stable) : stable);
        return rows;
    }
    private List<Row> groundRows() {
        return PubgInventoryState.nearby().stream()
                .map(value -> new Row(true, value.entityId(), value.item().copy(), value.quantity(), 0))
                .sorted(Comparator.comparingInt((Row row) -> PubgInventoryOrder.rank(row.item))
                        .thenComparing(row -> row.item.getHoverName().getString()).thenComparingInt(Row::source)).toList();
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        if (minecraft == null || minecraft.player == null) return;
        if (pressed != null && !sourceStillMatches(pressed)) { pressed = null; dragging = false; }
        g.fillGradient(0, 0, width, height, 0x63313b43, 0xa011151b);
        int screenMouseX = mx, screenMouseY = my;
        mx = Math.round(mx / viewScale); my = Math.round(my / viewScale);
        g.pose().pushPose(); g.pose().scale(viewScale, viewScale, 1);
        try {
        hits.clear(); tooltip = ItemStack.EMPTY; actionTooltip = null;
        int m = layout.margin(), ix = layout.inventoryX(), top = layout.listTop();
        title(g, tr("nearby"), m, top - 30);
        title(g, tr("title"), ix, top - 30);
        text(g, tr("ground"), m, top - 13, MUTED);
        tab(g, tr("type"), ix, top - 15, !recent, "type", mx, my);
        tab(g, tr("recent"), ix + 30, top - 15, recent, "recent", mx, my);
        g.fill(ix - m * 2, top + 3, ix - m * 2 + 1, layout.listBottom(), LINE);
        g.fill(layout.weaponX() - m * 2, top - 32, layout.weaponX() - m * 2 + 1, layout.listBottom(), LINE);
        List<Row> ground = groundRows(), inventory = inventoryRows();
        groundScroll = Math.clamp(groundScroll, 0, Math.max(0, ground.size() - layout.visibleRows()));
        inventoryScroll = Math.clamp(inventoryScroll, 0, Math.max(0, inventory.size() - layout.visibleRows()));
        previewSource = dragging ? pressed : sourceAt(mx, my, ground, inventory);
        previewTarget = previewSource == null ? INVALID
                : dragging ? dragTarget(previewSource, mx, my) : defaultTarget(previewSource);
        list(g, ground, groundScroll, m, mx, my);
        list(g, inventory, inventoryScroll, ix, mx, my);
        if (dragging && pressed != null && previewTarget.valid()) {
            if (overInventory(mx, my)) dashedOutline(g, ix - 2, top - 2, layout.listWidth() + 4,
                    layout.listBottom() - top + 4, TEXT);
            else if (layout.overNearby(mx, my)) dashedOutline(g, m - 2, top - 2,
                    layout.listWidth() + 4, layout.listBottom() - top + 4, TEXT);
        }
        equipment(g, mx, my);
        capacity(g);
        {
            int left = layout.equipmentX() + layout.equipmentSize() + 7;
            int right = layout.weaponX() - m * 4;
            int bottom = viewHeight - 42;
            PubgInventoryCharacterRenderer.render(g, left, top - 8, right, bottom,
                    viewScale, mx, my, minecraft.player);
        }
        for (int gun = 0; gun < 3; gun++) weapon(g, gun, mx, my);
        var settings = InventoryConfig.current(minecraft.player);
        int lowerY = layout.weaponTop(3), half = (layout.weaponWidth() - 10) / 2;
        int melee = settings.slot("melee"), throwable = settings.slot("throwable");
        if (melee >= 0) smallWeapon(g, melee, layout.weaponX(), lowerY, half, tr("melee"), mx, my);
        if (throwable >= 0) smallWeapon(g, throwable, layout.weaponX() + half + 10, lowerY, half, tr("throwable"), mx, my);
        footer(g, mx, my);
        } finally { g.pose().popPose(); }
        // Active use stays in front of the character; cursor items and tooltips remain above it.
        com.example.examplemod.vitals.client.InventoryActionHud.renderForeground(g);
        if (pressed != null && dragging) {
            g.pose().pushPose(); g.pose().translate(0, 0, 1400); g.pose().scale(viewScale, viewScale, 1);
            dragGhost(g, mx, my);
            g.pose().popPose();
        }
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, 1400);
            if (!dragging && !tooltip.isEmpty()) g.renderTooltip(font, tooltip, screenMouseX, screenMouseY);
            else if (!dragging && actionTooltip != null) g.renderTooltip(font, actionTooltip, screenMouseX, screenMouseY);
        } finally { g.pose().popPose(); }
    }
    private boolean canStore(ItemStack item) { return !MatchInventoryService.weapon(item); }
    private boolean overInventory(double x, double y) {
        return inside(x, y, layout.inventoryX(), layout.listTop(), layout.listWidth(), layout.listBottom() - layout.listTop());
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }
    /** Read this frame's rows, not last frame's mouse hit list: sorting and server updates can move a row. */
    private Row sourceAt(int mx, int my, List<Row> ground, List<Row> inventory) {
        for (int column = 0; column < 2; column++) {
            int x = column == 0 ? layout.margin() : layout.inventoryX();
            if (inside(mx, my, x, layout.listTop(), layout.listWidth(), layout.listBottom() - layout.listTop())) {
                int visible = (my - layout.listTop()) / layout.rowHeight();
                int index = visible + (column == 0 ? groundScroll : inventoryScroll);
                List<Row> rows = column == 0 ? ground : inventory;
                return visible < layout.visibleRows() && index < rows.size()
                        && (my - layout.listTop()) % layout.rowHeight() < layout.rowHeight() - 1 ? rows.get(index) : null;
            }
        }
        for (int slot = 0; slot < 3; slot++) {
            ItemStack gun = stack(slot);
            var positions = PubgAttachmentLayout.of(layout.weaponX(), layout.weaponTop(slot), layout.weaponWidth(), layout.weaponHeight());
            for (AttachmentType type : PubgAttachmentSlots.supportedTypes(gun)) {
                if (!positions.slot(type).contains(mx, my)) continue;
                return PubgAttachmentSlots.canRemove(gun, type)
                        ? new Row(false, slot, PubgAttachmentSlots.installed(gun, type), 1, 0, type, gun.copy()) : null;
            }
        }
        int slot = targetSlotAt(mx, my);
        return slot >= 0 && !stack(slot).isEmpty() ? new Row(false, slot, stack(slot).copy(), stack(slot).getCount(), 0) : null;
    }
    private int targetSlotAt(double x, double y) {
        for (int i = 0; i < EQUIPMENT.length; i++)
            if (inside(x, y, layout.equipmentX(), layout.equipmentY(i), layout.equipmentSize(), layout.equipmentSize())) return EQUIPMENT[i];
        for (int slot = 0; slot < 3; slot++) {
            var positions = PubgAttachmentLayout.of(layout.weaponX(), layout.weaponTop(slot), layout.weaponWidth(), layout.weaponHeight());
            if (inside(x, y, layout.weaponX(), layout.weaponTop(slot), layout.weaponWidth(), positions.dividerY() - layout.weaponTop(slot))) return slot;
        }
        int top = layout.weaponTop(3), half = (layout.weaponWidth() - 10) / 2;
        int height = Math.max(35, layout.listBottom() - top - 8);
        var settings = InventoryConfig.current(minecraft.player);
        if (inside(x, y, layout.weaponX(), top, half, height)) return settings.slot("melee");
        if (inside(x, y, layout.weaponX() + half + 10, top, half, height)) return settings.slot("throwable");
        return -1;
    }
    private boolean removable(ItemStack item) {
        return minecraft.player.isCreative() || !EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }
    private boolean acceptsItem(int slot, ItemStack item) {
        if (slot < 0 || slot == 40 || slot > 41) return false;
        if (slot == 41) return BackpackService.isBackpack(item) && removable(stack(slot));
        if (slot >= 36) {
            EquipmentSlot armor = minecraft.player.getEquipmentSlotForItem(item);
            return !BackpackService.isBackpack(item) && armor.getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                    && armor.getIndex() == slot - 36 && removable(stack(slot));
        }
        return MatchInventoryService.acceptsStorageSlot(minecraft.player, slot, item);
    }
    private Target itemTarget(int slot, ItemStack item) {
        return acceptsItem(slot, item) ? new Target(Operation.EQUIP, slot, !stack(slot).isEmpty()) : INVALID;
    }
    private Target defaultTarget(Row source) {
        if (source.ground && !InventoryConfig.current(minecraft.player).pubgDirectEquip()) return canStore(source.item) ? STORE : INVALID;
        if (source.mounted() || !source.ground && source.source >= 36) return removable(source.item) ? STORE : INVALID;
        var attachment = IAttachment.getIAttachmentOrNull(source.item);
        if (attachment != null) {
            AttachmentType type = attachment.getType(source.item);
            return attachmentDefault(minecraft.player.getInventory().selected, source.ground,
                    InventoryConfig.current(minecraft.player).pubgDirectEquip(),
                    candidate -> PubgAttachmentSlots.accepts(stack(candidate), type, source.item),
                    candidate -> !PubgAttachmentSlots.installed(stack(candidate), type).isEmpty());
        }
        if (BackpackService.isBackpack(source.item)) return itemTarget(41, source.item);
        EquipmentSlot armor = minecraft.player.getEquipmentSlotForItem(source.item);
        if (armor.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return itemTarget(36 + armor.getIndex(), source.item);
        if (source.ground) {
            if (!MatchInventoryService.weapon(source.item)) return STORE;
            return groundWeaponDefault(minecraft.player.getInventory().selected,
                    candidate -> acceptsItem(candidate, source.item), candidate -> stack(candidate).isEmpty());
        }
        if (source.item.getItem() instanceof com.example.examplemod.vitals.PubgMedicineItem)
            return new Target(Operation.USE, -1, false);
        if (MatchInventoryService.weapon(source.item)) return INVALID;
        int target = InventoryConfig.current(minecraft.player).slot(MatchInventoryService.role(source.item));
        return target != source.source ? itemTarget(target, source.item) : INVALID;
    }
    private Target dragTarget(Row source, double x, double y) {
        if (!source.ground && source.source >= 36 && !removable(source.item)) return INVALID;
        int slot = targetSlotAt(x, y);
        var attachment = IAttachment.getIAttachmentOrNull(source.item);
        AttachmentType type = attachment == null ? null : attachment.getType(source.item);
        boolean attach = slot >= 0 && slot < 3 && attachment != null && PubgAttachmentSlots.accepts(stack(slot), type, source.item);
        boolean occupied = slot >= 0 && (attachment != null && slot < 3
                ? !PubgAttachmentSlots.installed(stack(slot), type).isEmpty() : !stack(slot).isEmpty());
        boolean inList = !source.ground && !source.mounted() && InventoryConfig.current(minecraft.player).inPubgInventoryList(source.source);
        return PubgInventoryInteractionRules.drag(new Source(source.ground, source.mounted(), source.source, attachment != null, canStore(source.item), inList),
                new Destination(layout.overNearby(x, y), overInventory(x, y), slot, attach, acceptsItem(slot, source.item), occupied));
    }
    private boolean previewsEquip() {
        return previewSource != null && (dragging || !previewSource.ground || InventoryConfig.current(minecraft.player).pubgDirectEquip());
    }
    private boolean preferred(int slot, Operation operation) {
        return previewTarget.slot() == slot && previewTarget.operation() == operation;
    }
    private String rowAction(Row row) {
        if (row.mounted()) return "action.unload";
        Target target = defaultTarget(row);
        return switch (target.operation()) {
            case ATTACH -> "action.attach";
            case EQUIP -> "action.equip";
            case USE -> "action.use";
            case STORE -> row.ground ? "action.pickup" : "action.unload";
            default -> "";
        };
    }
    private void targetMarker(GuiGraphics g, int x, int y, int size, boolean primary) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.drawCenteredString(font, "∨", x + size / 2, y - 7, TEXT);
        if (primary) dashedOutline(g, x, y, size, size, TEXT);
        g.pose().popPose();
    }
    private void dragGhost(GuiGraphics g, int mx, int my) {
        int x = mx - 13, y = my - 13, size = 27;
        int color = previewTarget.valid() ? TEXT : 0xffe25151;
        g.fill(x, y, x + size, y + size, 0x55383d40);
        icon(g, pressed.item, x + 2, y + 2, size - 4);
        g.pose().pushPose(); g.pose().translate(0, 0, 300);
        int quantity = pressed.ground
                ? previewTarget.operation() == Operation.ATTACH || previewTarget.operation() == Operation.EQUIP ? 1 : pressed.count
                : pressed.item.getCount();
        if (quantity > 1) {
            String count = Integer.toString(quantity);
            g.drawString(font, count, x + size - font.width(count) - 2, y + size - 10, TEXT, true);
        }
        if (previewTarget.valid()) dashedOutline(g, x, y, size, size, color);
        else outline(g, x, y, size, size, color);
        Badge badge = previewTarget.badge();
        if (badge != Badge.NONE) {
            int bx = x + size + 2, by = y + size - 9;
            g.fill(bx, by, bx + 11, by + 11, 0xba16191c);
            outline(g, bx, by, 11, 11, color);
            if (badge == Badge.BACKPACK) CombatHudTextures.draw(g, CombatHudTextures.backpack(0), bx + 1, by + 1, 9, 9, TEXT);
            else {
                String glyph = switch (badge) { case INVALID -> "×"; case MINUS -> "−"; case SWAP -> "⇄"; default -> "∨"; };
                g.drawCenteredString(font, glyph, bx + 5, by + 1, color);
            }
        }
        g.pose().popPose();
    }
    private void title(GuiGraphics g, Component title, int x, int y) {
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(1.45f, 1.45f, 1);
        g.drawString(font, title, 0, 0, TEXT, true); g.pose().popPose();
    }
    private void text(GuiGraphics g, Component label, int x, int y, int color) { g.drawString(font, label, x, y, color, true); }
    private void tab(GuiGraphics g, Component label, int x, int y, boolean selected, String action, int mx, int my) {
        int w = font.width(label);
        Hit hit = new Hit(x - 2, y - 3, w + 4, 14, null, -1, action);
        hits.add(hit);
        text(g, label, x, y, selected || hit.contains(mx, my) ? TEXT : MUTED);
        if (selected) g.fill(x, y + 11, x + w, y + 12, 0xcceeeeee);
    }
    private void list(GuiGraphics g, List<Row> rows, int scroll, int x, int mx, int my) {
        int rowH = layout.rowHeight(), width = layout.listWidth();
        for (int index = scroll; index < Math.min(rows.size(), scroll + layout.visibleRows()); index++) {
            Row row = rows.get(index); int y = layout.listTop() + (index - scroll) * rowH;
            Hit hit = new Hit(x, y, width, rowH - 1, row, -1, "item"); hits.add(hit);
            boolean hover = !dragging && hit.contains(mx, my);
            g.fill(x, y, x + width, y + rowH - 1, hover ? 0x998d9396 : 0x43596065);
            g.fill(x, y, x + rowH - 1, y + rowH - 1, 0x224b5358);
            icon(g, row.item, x + 2, y + 2, rowH - 5);
            String count = Integer.toString(row.count);
            int countWidth = row.count > 1 ? CombatHudNumbers.width(count, 11) + 5 : 0;
            String name = font.plainSubstrByWidth(row.item.getHoverName().getString(), Math.max(8, width - rowH - countWidth - 7));
            g.drawString(font, name, x + rowH + 3, y + (rowH - 8) / 2, TEXT, true);
            if (row.count > 1) CombatHudNumbers.draw(g, count, x + width - countWidth, y + (rowH - 11) / 2f, 11, TEXT);
            if (hover) {
                tooltip = row.item;
                g.fill(x, y + rowH - 2, x + width, y + rowH - 1, TEXT);
                String action = rowAction(row);
                if (!action.isEmpty()) {
                    g.pose().pushPose(); g.pose().translate(0, 0, 600);
                    g.fill(x, y, x + rowH - 1, y + rowH - 1, 0x66363b3e);
                    Component label = tr(action);
                    float scale = Math.min(1, (rowH - 3f) / Math.max(1, font.width(label)));
                    g.pose().translate(x + (rowH - 1) / 2f, y + (rowH - 8 * scale) / 2f, 0);
                    g.pose().scale(scale, scale, 1);
                    g.drawCenteredString(font, label, 0, 0, TEXT);
                    g.pose().popPose();
                }
            }
        }
        if (rows.size() > layout.visibleRows()) {
            int track = layout.visibleRows() * rowH;
            int thumb = Math.max(12, track * layout.visibleRows() / rows.size());
            int y = layout.listTop() + (track - thumb) * scroll / Math.max(1, rows.size() - layout.visibleRows());
            g.fill(x + width + 3, y, x + width + 5, y + thumb, 0x99eeeeee);
        }
    }
    private void equipment(GuiGraphics g, int mx, int my) {
        int size = layout.equipmentSize(), x = layout.equipmentX();
        for (int i = 0; i < EQUIPMENT.length; i++) {
            int slot = EQUIPMENT[i], y = layout.equipmentY(i);
            ItemStack item = stack(slot);
            Row row = item.isEmpty() ? null : new Row(false, slot, item.copy(), item.getCount(), 0);
            Hit hit = new Hit(x, y, size, size, row, slot, "equipment"); hits.add(hit);
            boolean compatible = previewsEquip() && !previewSource.mounted() && acceptsItem(slot, previewSource.item);
            boolean primary = compatible && preferred(slot, Operation.EQUIP);
            g.fill(x, y, x + size, y + size, primary ? 0xcce4e5e5 : 0x302f3538);
            outline(g, x, y, size, size, hit.contains(mx, my) ? 0xbbeeeeee : 0x66888e91);
            if (item.isEmpty()) {
                Component label = tr(EQUIPMENT_NAMES[i]);
                g.drawCenteredString(font, label, x + size / 2, y + (size - 8) / 2, MUTED);
            } else {
                icon(g, item, x + 2, y + 2, size - 4);
                if (item.isDamageableItem()) {
                    int fill = Math.max(0, Math.round((size - 2) * (1 - (float) item.getDamageValue() / item.getMaxDamage())));
                    g.fill(x + 1, y + size - 2, x + 1 + fill, y + size - 1, 0xffd5d5cf);
                }
                if (hit.contains(mx, my)) tooltip = item;
            }
            if (compatible) targetMarker(g, x, y, size, primary);
        }
    }
    private void weapon(GuiGraphics g, int slot, int mx, int my) {
        int x = layout.weaponX(), y = layout.weaponTop(slot), w = layout.weaponWidth(), h = layout.weaponHeight();
        ItemStack item = stack(slot);
        var attachmentLayout = PubgAttachmentLayout.of(x, y, w, h);
        int cardHeight = attachmentLayout.dividerY() - y;
        Hit hit = new Hit(x, y, w, cardHeight, item.isEmpty() ? null : new Row(false, slot, item.copy(), 1, 0), slot, "weapon");
        hits.add(hit);
        var gun = IGun.getIGunOrNull(item);
        var supported = PubgAttachmentSlots.supportedTypes(item);
        boolean overAttachment = supported.stream().anyMatch(type -> attachmentLayout.slot(type).contains(mx, my));
        boolean hover = hit.contains(mx, my) && !overAttachment, refittable = gun != null && !gun.hasAttachmentLock(item);
        if (hover && refittable && !dragging) {
            g.fill(x, y, x + w, y + cardHeight, 0x42696e71);
            outline(g, x, y, w, cardHeight, 0x99d2d6d7);
        }
        var model = attachmentLayout.model();
        if (gun != null) {
            com.example.examplemod.lootspawn.client.GroundGunModelRenderer.renderInventory(item, g,
                    model.x(), model.y(), model.width(), model.height(), 0);
        } else if (!item.isEmpty()) icon(g, item, x + w / 2 - 16, model.y(), Math.min(32, model.height()));
        // PUBG's labels and slots overlay the large gun instead of reserving an empty box around it.
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        weaponHeading(g, slot, item, x, y, w);
        g.fill(x, attachmentLayout.dividerY(), x + w, attachmentLayout.dividerY() + 1, LINE);
        g.pose().popPose();
        if (hover && !dragging && !item.isEmpty()) tooltip = item;
        if (hover && refittable && !dragging) {
            g.pose().pushPose(); g.pose().translate(0, 0, 250);
            g.drawString(font, tr("refit_hint"), x + 2, y + Math.clamp(Math.round(w * .063f), 12, 20) + 4, TEXT, true);
            g.pose().popPose();
        }
        if (previewsEquip() && !previewSource.mounted() && MatchInventoryService.weapon(previewSource.item)
                && acceptsItem(slot, previewSource.item)) {
            boolean primary = preferred(slot, Operation.EQUIP);
            g.pose().pushPose(); g.pose().translate(0, 0, 300);
            if (primary) dashedOutline(g, x, y, w, cardHeight, TEXT);
            g.drawString(font, "∨", x + 2, y - 9, TEXT, true);
            g.pose().popPose();
        }
        for (AttachmentType type : supported) attachment(g, slot, item, type, attachmentLayout.slot(type), mx, my);
    }
    private void weaponHeading(GuiGraphics g, int slot, ItemStack item, int x, int y, int w) {
        int indexSize = Math.clamp(Math.round(w * .063f), 12, 20);
        outline(g, x + 2, y, indexSize, indexSize + 1, 0xffbfc2c3);
        CombatHudNumbers.drawCentered(g, Integer.toString(slot + 1), x + 2 + indexSize / 2f, y + 3, indexSize - 5, TEXT);
        String label = item.isEmpty() ? tr(slot == 2 ? "handgun" : "primary").getString()
                : net.minecraft.ChatFormatting.stripFormatting(item.getHoverName().getString());
        int nameX = x + indexSize + 7;
        int nameWidth = Math.max(12, Math.round(w * .55f) - (nameX - x) - 3);
        g.drawString(font, font.plainSubstrByWidth(label, nameWidth), nameX, y + 3, item.isEmpty() ? MUTED : TEXT, true);
        var ammo = WeaponHudData.read(minecraft.player, item);
        if (ammo.present()) {
            float loadedHeight = Math.clamp(w * .044f, 9, 18), reserveHeight = loadedHeight * .72f;
            int reserveWidth = CombatHudNumbers.width(ammo.reserveText(), reserveHeight);
            float reserveX = x + w - reserveWidth - 2;
            CombatHudNumbers.draw(g, ammo.reserveText(), reserveX, y + 2 + loadedHeight - reserveHeight, reserveHeight, MUTED);
            CombatHudNumbers.draw(g, Integer.toString(ammo.loaded()),
                    reserveX - 2 - CombatHudNumbers.width(Integer.toString(ammo.loaded()), loadedHeight), y + 2, loadedHeight, TEXT);
        }
        var kind = PubgInventoryAmmoPresentation.read(item);
        if (kind.present()) ammunitionType(g, kind, x, y, w, Math.clamp(w * .044f, 9, 18));
    }
    private void ammunitionType(GuiGraphics g, PubgInventoryAmmoPresentation.Ammo ammo, int x, int y, int w, float countHeight) {
        // Keep the right-aligned caption clear of the sight at 62% of the weapon card.
        int available = Math.max(24, Math.round(w * .28f));
        int iconSize = Math.clamp(Math.round(w * .052f), 10, 18);
        int gap = 2;
        float textScale = Math.clamp((available - iconSize - gap) / (float) Math.max(1, font.width(ammo.name())), .6f, 1);
        int labelWidth = Math.max(1, (int) ((available - iconSize - gap) / textScale));
        String label = font.plainSubstrByWidth(ammo.name().getString(), labelWidth);
        int actualTextWidth = Math.round(font.width(label) * textScale);
        int left = x + w - 2 - actualTextWidth - iconSize - gap;
        int top = y + Math.round(countHeight) + 4;
        icon(g, ammo.icon(), left, top - 2, iconSize);
        g.pose().pushPose();
        g.pose().translate(left + iconSize + gap, top + (iconSize - 8 * textScale) / 2f - 2, 0);
        g.pose().scale(textScale, textScale, 1);
        g.drawString(font, label, 0, 0, TEXT, true);
        g.pose().popPose();
    }
    private void attachment(GuiGraphics g, int gunSlot, ItemStack gun, AttachmentType type,
                            PubgAttachmentLayout.Slot position, int mx, int my) {
        ItemStack installed = PubgAttachmentSlots.installed(gun, type);
        Row row = PubgAttachmentSlots.canRemove(gun, type)
                ? new Row(false, gunSlot, installed, 1, 0, type, gun.copy()) : null;
        Hit hit = new Hit(position.x(), position.y(), position.size(), position.size(), row, gunSlot, "attachment", type);
        hits.add(hit);
        boolean hover = hit.contains(mx, my);
        boolean compatible = previewsEquip() && (!previewSource.mounted() || previewSource.source != gunSlot)
                && PubgAttachmentSlots.accepts(gun, type, previewSource.item);
        boolean primary = compatible && preferred(gunSlot, Operation.ATTACH);
        PubgAttachmentSlots.draw(g, gun, type, hit.x, hit.y, hit.w, hover && !dragging, compatible, primary);
        if (compatible) targetMarker(g, hit.x, hit.y, hit.w, primary);
        if (hover) {
            actionTooltip = null;
            tooltip = PubgAttachmentSlots.displayed(gun, type);
            if (tooltip.isEmpty()) actionTooltip = tr("slot." + type.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
    private void smallWeapon(GuiGraphics g, int slot, int x, int y, int w, Component label, int mx, int my) {
        ItemStack item = stack(slot); int h = Math.max(35, layout.listBottom() - y - 8);
        Hit hit = new Hit(x, y, w, h, item.isEmpty() ? null : new Row(false, slot, item.copy(), item.getCount(), 0), slot, "weapon");
        hits.add(hit);
        CombatHudNumbers.draw(g, Integer.toString(slot + 1), x + 4, y + 1, 12, TEXT);
        g.drawString(font, font.plainSubstrByWidth((item.isEmpty() ? label : item.getHoverName()).getString(), w - 22), x + 20, y + 3, TEXT, true);
        if (!item.isEmpty()) {
            int size = Math.max(20, Math.min(w - 12, h - 24));
            icon(g, item, x + (w - size) / 2, y + 19, size);
            if (hit.contains(mx, my)) tooltip = item;
        }
        g.fill(x, y + h, x + w, y + h + 1, LINE);
        if (previewsEquip() && !previewSource.mounted() && acceptsItem(slot, previewSource.item)) {
            g.pose().pushPose(); g.pose().translate(0, 0, 300);
            if (preferred(slot, Operation.EQUIP)) dashedOutline(g, x, y, w, h, TEXT);
            g.drawString(font, "∨", x + 3, y - 9, TEXT, true);
            g.pose().popPose();
        }
    }
    private void capacity(GuiGraphics g) {
        var state = BackpackClient.current();
        if (state != null) {
            int x = layout.capacityX(), y = layout.capacityTop(), h = layout.capacityHeight();
            g.fill(x, y, x + 3, y + h, 0x803c4448);
            int used = (int) Math.min(h, (long) state.used() * h / Math.max(1, state.capacity()));
            g.fill(x, y + h - used, x + 3, y + h,
                    state.used() > state.capacity() ? 0xffc7645d : 0xffd2d3c8);
            if (previewSource != null && !previewSource.ground && !previewSource.mounted()
                    && previewSource.source < 36 && canStore(previewSource.item) && used > 0) {
                // Mark only the real source stack's existing cost; merged row totals are not a pending transaction.
                int selected = Math.min(state.used(), BackpackWeights.weight(minecraft.player, previewSource.item));
                if (selected > 0) {
                    int segment = Math.min(used, Math.max(1, (int) ((long) selected * h / Math.max(1, state.capacity()))));
                    g.fill(x, y + h - used, x + 3, y + h - used + segment, 0xffd46e70);
                }
            }
            g.drawString(font, BackpackClient.units(state.used()) + " / " + BackpackClient.units(state.capacity()),
                    x, y + h + 7, TEXT, true);
        }
    }
    private void footer(GuiGraphics g, int mx, int my) {
        Component vanilla = tr("vanilla");
        tab(g, vanilla, viewWidth - font.width(vanilla) - layout.margin(), viewHeight - 18, false, "vanilla", mx, my);
    }
    private void icon(GuiGraphics g, ItemStack item, int x, int y, int size) {
        if (size <= 0 || item.isEmpty()) return;
        if (OriginalIconClient.drawGui(g, item, x, y, size)) return;
        g.pose().pushPose(); g.pose().translate(x, y, 0); float scale = size / 16f;
        g.pose().scale(scale, scale, scale); g.renderItem(item, 0, 0); g.pose().popPose();
    }
    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color); g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color); g.fill(x + w - 1, y, x + w, y + h, color);
    }
    private static void dashedOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
        for (int dx = 0; dx < w; dx += 5) {
            int end = Math.min(w, dx + 3);
            g.fill(x + dx, y, x + end, y + 1, color);
            g.fill(x + dx, y + h - 1, x + end, y + h, color);
        }
        for (int dy = 0; dy < h; dy += 5) {
            int end = Math.min(h, dy + 3);
            g.fill(x, y + dy, x + 1, y + end, color);
            g.fill(x + w - 1, y + dy, x + w, y + end, color);
        }
    }
    private Hit hit(double x, double y) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) return hits.get(i);
        return null;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        x /= viewScale; y /= viewScale;
        Hit hit = hit(x, y);
        if (hit == null) return true;
        if (button == 1) {
            if (hit.row != null) {
                if (!sourceStillMatches(hit.row)) return true;
                if (hit.row.mounted()) PubgInventoryState.unloadAttachment(hit.row.source, hit.row.attachmentType.name());
                else if (hit.row.ground) PubgInventoryState.pickupGround(hit.row.source);
                else if (hit.row.source == 41) PubgInventoryState.removeBackpack();
                else PubgInventoryState.rightClickInventory(hit.row.source);
            }
            return true;
        }
        if (button != 0) return true;
        switch (hit.action) {
            case "type" -> { recent = false; inventoryScroll = 0; }
            case "recent" -> { recent = true; inventoryScroll = 0; }
            case "vanilla" -> PubgInventoryInput.openVanilla();
            default -> {
                if (hit.row != null) {
                    pressed = hit.row; dragging = false; pressX = x; pressY = y;
                }
            }
        }
        return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        x /= viewScale; y /= viewScale;
        if (button == 0 && pressed != null && Math.hypot(x - pressX, y - pressY) > 3) dragging = true;
        return true;
    }
    private boolean sourceStillMatches(Row row) {
        if (row.mounted()) return ItemStack.matches(stack(row.source), row.ownerGun)
                && PubgAttachmentSlots.canRemove(stack(row.source), row.attachmentType)
                && ItemStack.matches(PubgAttachmentSlots.installed(stack(row.source), row.attachmentType), row.item);
        if (row.ground) return PubgInventoryState.nearby().stream().anyMatch(value -> value.entityId() == row.source
                && value.quantity() == row.count && ItemStack.isSameItemSameComponents(value.item(), row.item));
        return ItemStack.matches(stack(row.source), row.item);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        x /= viewScale; y /= viewScale;
        if (button != 0 || pressed == null) return true;
        Row source = pressed; boolean wasDragging = dragging; pressed = null; dragging = false;
        if (!sourceStillMatches(source)) return true;
        if (!wasDragging) {
            Hit released = hit(x, y);
            if (!source.ground && !source.mounted() && source.source < 3 && source.item.getItem() instanceof IGun
                    && released != null && released.action.equals("weapon") && released.target == source.source)
                PubgInventoryInput.openRefit(source.source);
            return true;
        }
        Target target = dragTarget(source, x, y);
        switch (target.operation()) {
            case DROP -> {
                if (source.mounted()) PubgInventoryState.transferAttachment(source.source, source.attachmentType.name(), -2);
                else PubgInventoryState.dropInventory(source.source);
            }
            case ATTACH -> {
                String type = IAttachment.getIAttachmentOrNull(source.item).getType(source.item).name();
                if (source.mounted()) PubgInventoryState.transferAttachment(source.source, type, target.slot());
                else if (source.ground) PubgInventoryState.attachGround(source.source, target.slot(), type);
                else PubgInventoryState.attachInventory(source.source, target.slot(), type);
            }
            case EQUIP -> {
                if (source.ground) PubgInventoryState.equipGround(source.source, target.slot());
                else PubgInventoryState.moveInventory(source.source, target.slot());
            }
            case STORE -> {
                if (source.mounted()) PubgInventoryState.unloadAttachment(source.source, source.attachmentType.name());
                else if (source.ground) PubgInventoryState.equipGround(source.source, -1);
                else if (source.source == 41) PubgInventoryState.removeBackpack();
                else PubgInventoryState.moveInventory(source.source, -1);
            }
            default -> { } // Invalid destinations and returning to the source never mutate storage.
        }
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        x /= viewScale; y /= viewScale;
        int amount = vertical > 0 ? -1 : vertical < 0 ? 1 : 0;
        if (x < layout.inventoryX()) groundScroll = Math.max(0, groundScroll + amount);
        else if (x < layout.inventoryX() + layout.listWidth() + 8) inventoryScroll = Math.max(0, inventoryScroll + amount);
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && pressed != null) { pressed = null; dragging = false; return true; }
        if (PubgInventoryInput.keyPressed(key, scan, modifiers)) return true;
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        return PubgInventoryInput.keyReleased(key, scan, modifiers) || super.keyReleased(key, scan, modifiers);
    }
    @Override public void removed() { pressed = null; dragging = false; PubgInventoryInput.release(); }
}
