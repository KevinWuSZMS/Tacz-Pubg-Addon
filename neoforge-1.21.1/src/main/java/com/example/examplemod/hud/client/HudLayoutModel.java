package com.example.examplemod.hud.client;

/** GUI-coordinate geometry, independent of rendering, registries and window pixel density. */
public final class HudLayoutModel {
    private HudLayoutModel() {}
    public record Position(double x, double y) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Non-finite HUD position");
            x = Math.clamp(x, 0, 1);
            y = Math.clamp(y, 0, 1);
        }
    }
    public record Rect(int x, int y, int width, int height) {
        public Rect { width = Math.max(1, width); height = Math.max(1, height); }
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < (long) x + width && py < (long) y + height;
        }
    }
    public record Menu(Rect panel, java.util.List<Rect> buttons, int messageY) {
        public Menu { buttons = java.util.List.copyOf(buttons); }
    }
    public static Menu menu(int width, int height) {
        int margin = Math.min(8, Math.max(0, Math.min(width, height) / 10));
        int panelWidth = Math.max(1, Math.min(252, width - margin * 2));
        int panelHeight = Math.max(1, Math.min(172, height - margin * 2));
        Rect panel = new Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
        int padding = Math.min(10, panelWidth / 8), header = Math.min(23, panelHeight / 7);
        int footer = Math.min(40, panelHeight / 4), gap = Math.min(6, panelHeight / 24);
        int buttonHeight = Math.max(1, Math.min(20, (panelHeight - header - footer - gap * 3) / 4));
        var buttons = new java.util.ArrayList<Rect>();
        for (int i = 0; i < 4; i++) buttons.add(new Rect(panel.x() + padding,
                panel.y() + header + i * (buttonHeight + gap), Math.max(1, panelWidth - padding * 2), buttonHeight));
        Rect last = buttons.getLast();
        return new Menu(panel, buttons, last.y() + last.height() + Math.min(8, footer / 4));
    }
    public static Rect resolve(Position position, int screenWidth, int screenHeight, Rect fallback) {
        if (position == null) return fallback;
        int travelX = Math.max(0, screenWidth - fallback.width());
        int travelY = Math.max(0, screenHeight - fallback.height());
        return new Rect((int) Math.round(position.x() * travelX), (int) Math.round(position.y() * travelY),
                fallback.width(), fallback.height());
    }
    public static Position normalize(double x, double y, int screenWidth, int screenHeight, int width, int height) {
        int travelX = Math.max(0, screenWidth - Math.max(1, width));
        int travelY = Math.max(0, screenHeight - Math.max(1, height));
        return new Position(travelX == 0 ? 0 : x / travelX, travelY == 0 ? 0 : y / travelY);
    }
}
