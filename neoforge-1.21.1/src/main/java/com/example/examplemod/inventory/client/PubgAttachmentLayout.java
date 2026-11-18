package com.example.examplemod.inventory.client;

import com.tacz.guns.api.item.attachment.AttachmentType;

/** Balanced attachment anchors inside a weapon card, beneath its name and ammunition header. */
public record PubgAttachmentLayout(int centerX, int centerY, int horizontalStep, int verticalStep, int size) {
    public record Slot(int x, int y, int size) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        }
    }

    public static PubgAttachmentLayout of(int x, int y, int width, int height) {
        int size = Math.clamp(height / 5, 16, 24);
        int top = y + 20, bottom = y + height - 6;
        int centerY = (top + bottom) / 2;
        return new PubgAttachmentLayout(x + width / 2, centerY, width / 5,
                Math.max(0, (bottom - top - size) / 2), size);
    }

    public Slot slot(AttachmentType type) {
        int column = switch (type) {
            case MUZZLE -> -2;
            case LASER -> -1;
            case GRIP -> 0;
            case SCOPE, EXTENDED_MAG -> 1;
            case STOCK -> 2;
            default -> throw new IllegalArgumentException("No inventory position for " + type);
        };
        int row = switch (type) {
            case SCOPE -> -1;
            case MUZZLE, STOCK -> 0;
            case LASER, GRIP, EXTENDED_MAG -> 1;
            default -> throw new IllegalArgumentException("No inventory position for " + type);
        };
        return new Slot(centerX + column * horizontalStep - size / 2,
                centerY + row * verticalStep - size / 2, size);
    }
}
