package com.example.examplemod.inventory.client;

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
                checks += 7;
            }
        }
        System.out.println("PUBG inventory layout: " + checks + " viewport checks passed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
