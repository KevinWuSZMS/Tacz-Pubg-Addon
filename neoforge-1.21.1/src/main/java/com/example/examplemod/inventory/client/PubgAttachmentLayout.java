package com.example.examplemod.inventory.client;

import com.tacz.guns.api.item.attachment.AttachmentType;

/** PC inventory proportions: five fixed lower positions, a separate sight, and a large gun behind them. */
public record PubgAttachmentLayout(int x, int y, int width, int height, int size) {
    public record Slot(int x, int y, int size) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        }
    }
    public record Model(int x, int y, int width, int height) { }

    public static PubgAttachmentLayout of(int x, int y, int width, int height) {
        // The native unhovered frame crops one texel on each side; its visible square is ~10% W.
        int size = Math.clamp(Math.round(width * .114f), 16, Math.max(16, Math.round(height * .26f)));
        return new PubgAttachmentLayout(x, y, width, height, size);
    }

    public Slot slot(AttachmentType type) {
        if (type == AttachmentType.SCOPE)
            return new Slot(x + Math.round(width * .622f) - size / 2, y + Math.round(height * .09f), size);
        int column = switch (type) {
            case MUZZLE -> 0;
            case GRIP -> 1;
            case EXTENDED_MAG -> 2;
            case LASER -> 3;
            case STOCK -> 4;
            default -> throw new IllegalArgumentException("No inventory position for " + type);
        };
        int inset = Math.max(1, Math.round(width * .01f));
        return new Slot(x + inset + Math.round((width - 2 * inset - size) * column / 4f),
                y + Math.round(height * .625f), size);
    }

    /** Slot overlays intentionally share this vertical region; they must never shrink the gun viewport. */
    public Model model() {
        return new Model(x + Math.round(width * .055f), y + Math.round(height * .12f),
                Math.round(width * .89f), Math.round(height * .73f));
    }
    public int dividerY() { return y + Math.round(height * .90f); }
}
