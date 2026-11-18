package com.example.examplemod.inventory.client;

import com.tacz.guns.api.item.attachment.AttachmentType;
import static com.tacz.guns.api.item.attachment.AttachmentType.*;

public final class PubgInventoryLayoutChecks {
    public static void main(String[] args) {
        int checks = 0;
        for (int width : new int[]{320, 427, 640, 854, 1280, 1920, 2560}) {
            for (int height : new int[]{240, 360, 400, 480, 720, 1080, 1600}) {
                float scale = Math.min(1f, Math.min(width / 640f, height / 400f));
                var layout = PubgInventoryLayout.of(Math.round(width / scale), Math.round(height / scale));
                require(layout.margin() + layout.listWidth() < layout.inventoryX(), "nearby overlaps inventory");
                require(layout.inventoryX() + layout.listWidth() < layout.equipmentX(), "inventory overlaps armor");
                require(layout.equipmentX() + layout.equipmentSize() < layout.weaponX(), "armor overlaps weapons");
                require(layout.weaponX() + layout.weaponWidth() <= layout.width(), "weapon panel outside viewport");
                require(layout.equipmentY(4) + layout.equipmentSize() < layout.listBottom(), "fifth slot outside viewport");
                require(layout.weaponTop(3) < layout.listBottom() - 30, "melee and throwable inaccessible");
                require(layout.visibleRows() >= 6, "inventory rows inaccessible");
                require(layout.capacityX() > layout.inventoryX() + layout.listWidth()
                        && layout.capacityX() + 3 < layout.equipmentX(), "capacity bar overlaps inventory or armor");
                require(layout.capacityTop() + layout.capacityHeight() + 16 < layout.listBottom(), "capacity label clipped");
                require(layout.overNearby(layout.margin(), layout.listTop()), "nearby leading edge cannot accept a drop");
                require(!layout.overNearby(layout.margin() - 1, layout.listTop()), "left margin accidentally drops items");
                require(!layout.overNearby(layout.margin() + layout.listWidth(), layout.listTop()), "column gap accidentally drops items");
                require(!layout.overNearby(layout.margin(), layout.listTop() - 1), "header accidentally drops items");
                require(!layout.overNearby(layout.margin(), layout.listBottom()), "footer accidentally drops items");
                checks += 14;
                for (int gun = 0; gun < 3; gun++) {
                    int x = layout.weaponX(), y = layout.weaponTop(gun), w = layout.weaponWidth(), h = layout.weaponHeight();
                    var anchors = PubgAttachmentLayout.of(x, y, w, h);
                    var muzzle = anchors.slot(MUZZLE); var laser = anchors.slot(LASER);
                    var grip = anchors.slot(GRIP); var scope = anchors.slot(SCOPE);
                    var mag = anchors.slot(EXTENDED_MAG); var stock = anchors.slot(STOCK);
                    require(muzzle.x() < grip.x() && grip.x() < mag.x() && mag.x() < laser.x() && laser.x() < stock.x(),
                            "bottom row must be muzzle, grip, magazine, laser, stock without moving unsupported positions");
                    require(Math.abs(muzzle.x() + stock.x() - 2 * mag.x()) <= 1
                            && Math.abs(grip.x() + laser.x() - 2 * mag.x()) <= 1, "five fixed columns are not balanced");
                    require(muzzle.y() == grip.y() && grip.y() == mag.y() && mag.y() == laser.y() && laser.y() == stock.y(),
                            "all five lower attachments must share the bottom row");
                    require(scope.y() < muzzle.y() && Math.abs(scope.x() + scope.size() / 2f - (x + w * .622f)) < 1.5f,
                            "sight must sit separately near 62% card width");
                    var model = anchors.model();
                    require(model.width() >= w * .88f && model.height() >= h * .72f,
                            "gun preview was squeezed into the gap between attachment rows again");
                    require(model.y() + model.height() > mag.y(), "gun must extend behind lower slot overlays");
                    require(model.x() >= x && model.x() + model.width() <= x + w
                            && model.y() >= y && model.y() + model.height() < anchors.dividerY(), "gun clipped by card divider");
                    require(anchors.dividerY() < y + h - 3, "weapon rows need space after their divider");
                    checks += 8;
                    AttachmentType[] types = {MUZZLE, LASER, SCOPE, GRIP, EXTENDED_MAG, STOCK};
                    for (int i = 0; i < types.length; i++) {
                        var slot = anchors.slot(types[i]);
                        require(slot.x() >= x && slot.x() + slot.size() <= x + w, "attachment outside weapon card");
                        require(slot.y() >= y && slot.y() + slot.size() < anchors.dividerY(), "attachment crosses its card divider");
                        require(slot.size() >= 16 && slot.contains(slot.x(), slot.y())
                                && !slot.contains(slot.x() + slot.size(), slot.y()), "attachment hit region inaccessible");
                        checks += 3;
                        for (int j = i + 1; j < types.length; j++) {
                            var other = anchors.slot(types[j]);
                            require(slot.x() + slot.size() <= other.x() || other.x() + other.size() <= slot.x()
                                    || slot.y() + slot.size() <= other.y() || other.y() + other.size() <= slot.y(),
                                    "attachment hit regions overlap: " + types[i] + " and " + types[j]);
                            checks++;
                        }
                    }
                }
            }
        }
        System.out.println("PUBG inventory layout: " + checks + " viewport checks passed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
