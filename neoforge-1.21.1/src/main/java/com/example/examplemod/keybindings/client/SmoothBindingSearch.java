package com.example.examplemod.keybindings.client;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Keeps vanilla text editing and IME focus while drawing with the binding screen's smooth font. */
public final class SmoothBindingSearch extends EditBox {
    private int selectionAnchor;
    private int viewStart;
    private int lastCursor = -1, lastAnchor = -1;
    private String lastValue = "";
    private long blinkStart;
    private BindingVisuals visuals;
    private float textX, size = 14, availableWidth;
    private int renderedEnd;

    public SmoothBindingSearch(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        setBordered(false);
    }

    @Override public void setHighlightPos(int position) {
        super.setHighlightPos(position);
        selectionAnchor = Math.max(0, Math.min(position, getValue().length()));
    }

    @Override public void setFocused(boolean focused) {
        if (focused != isFocused()) blinkStart = Util.getMillis();
        super.setFocused(focused);
    }

    /** Frame and search icon are drawn by the parent; all coordinates here are its logical coordinates. */
    public void renderText(GuiGraphics graphics, BindingVisuals visuals, float x, float y,
                           float maxWidth, float size, int textColor, int placeholderColor, String hint) {
        this.visuals = visuals;
        this.textX = x;
        this.size = size;
        availableWidth = maxWidth;
        String value = getValue();
        int cursor = getCursorPosition();
        if (cursor != lastCursor || selectionAnchor != lastAnchor || !value.equals(lastValue)) blinkStart = Util.getMillis();
        lastCursor = cursor; lastAnchor = selectionAnchor; lastValue = value;
        viewStart = Math.min(viewStart, value.length());
        if (cursor < viewStart) viewStart = cursor;
        while (viewStart < cursor && visuals.textWidth(value.substring(viewStart, cursor), size) > maxWidth - 4) {
            viewStart = value.offsetByCodePoints(viewStart, 1);
        }
        // Return towards the beginning when deleting near the right edge leaves available space.
        while (viewStart > 0) {
            int previous = value.offsetByCodePoints(viewStart, -1);
            if (visuals.textWidth(value.substring(previous, cursor), size) > maxWidth - 4) break;
            viewStart = previous;
        }
        renderedEnd = viewStart;
        while (renderedEnd < value.length()) {
            int next = value.offsetByCodePoints(renderedEnd, 1);
            if (visuals.textWidth(value.substring(viewStart, next), size) > maxWidth - 2) break;
            renderedEnd = next;
        }
        int startSelection = Math.max(viewStart, Math.min(cursor, selectionAnchor));
        int endSelection = Math.min(renderedEnd, Math.max(cursor, selectionAnchor));
        if (isFocused() && endSelection > startSelection) {
            float startX = x + visuals.textWidth(value.substring(viewStart, startSelection), size);
            float endX = x + visuals.textWidth(value.substring(viewStart, endSelection), size);
            visuals.roundRect(graphics, startX, y - 1, endX - startX, size * 1.4f, 1, 0x8847AFC5, 0);
        }
        if (value.isEmpty() && !isFocused()) {
            visuals.text(graphics, visuals.ellipsize(hint, size, maxWidth), x, y, size, placeholderColor);
        } else {
            visuals.text(graphics, value.substring(viewStart, renderedEnd), x, y, size, textColor);
        }
        if (isFocused() && (Util.getMillis() - blinkStart) / 300L % 2L == 0L) {
            float caretX = x + Math.min(maxWidth - 1, visuals.textWidth(value.substring(viewStart, cursor), size));
            visuals.roundRect(graphics, caretX, y + 1, 1.25f, size * 1.2f, .5f, 0xFFB4F5FF, 0);
        }
    }

    public void moveCursorFromMouse(double logicalX, boolean selecting) {
        if (visuals == null) return;
        String value = getValue();
        viewStart = Math.min(viewStart, value.length());
        int position = viewStart;
        float target = (float) logicalX - textX;
        int end = Math.min(renderedEnd, value.length());
        for (int next = position; next < end;) {
            int after = value.offsetByCodePoints(next, 1);
            float beforeWidth = visuals.textWidth(value.substring(viewStart, next), size);
            float afterWidth = visuals.textWidth(value.substring(viewStart, after), size);
            if (target < (beforeWidth + afterWidth) / 2) break;
            position = after; next = after;
        }
        // Continuing a drag outside the field also reveals the remaining text.
        if (selecting && target > availableWidth && position < value.length()) position = value.offsetByCodePoints(position, 1);
        if (selecting && target < 0 && position > 0) position = value.offsetByCodePoints(position, -1);
        moveCursorTo(position, selecting);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        setFocused(true);
        moveCursorFromMouse(mouseX, Screen.hasShiftDown());
        return true;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button != 0 || !isFocused()) return false;
        moveCursorFromMouse(mouseX, true);
        return true;
    }

    /** The parent invokes renderText inside its scaled pose; avoid the vanilla pixel-font renderer. */
    @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
}
