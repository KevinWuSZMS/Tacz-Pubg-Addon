package com.example.examplemod.inventory.client;

/** Proportions follow the PC inventory; coordinates stay in Minecraft GUI units. */
public record PubgInventoryLayout(int width, int height, int margin, int listWidth, int inventoryX,
                                  int listTop, int listBottom, int rowHeight, int equipmentX,
                                  int equipmentTop, int equipmentSize, int weaponX, int weaponWidth) {
    public static PubgInventoryLayout of(int width, int height) {
        int margin = Math.max(5, width / 160);
        int top = Math.max(48, height * 11 / 100);
        int row = Math.max(25, Math.min(40, height * 56 / 1000));
        int slot = Math.max(22, Math.min(40, height * 56 / 1000));
        int weapons = width * 71 / 100;
        return new PubgInventoryLayout(width, height, margin, width * 135 / 1000,
                width * 16 / 100, top, height - 36, row, width * 33 / 100,
                Math.max(top + 15, height * 25 / 100), slot, weapons, width - weapons - margin);
    }
    public int visibleRows() { return Math.max(1, (listBottom - listTop) / rowHeight); }
    public int equipmentY(int index) { return equipmentTop + index * (equipmentSize + 7); }
    public int weaponTop(int slot) { return listTop + slot * weaponHeight(); }
    public int weaponHeight() { return Math.max(54, (listBottom - listTop) * 25 / 100); }
}
