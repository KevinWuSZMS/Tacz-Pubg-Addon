package com.example.examplemod.hud.client;

/** Xaero 26.6: module has 18 native padding units, frame adds 4 around the content. */
public final class XaeroMapFrameGeometry {
    private XaeroMapFrameGeometry() {}
    public static int frameBorder(int frame) { return frame >= 3 ? 0 : frame == 2 ? 1 : 4; }
    public static HudLayoutModel.Rect visible(int moduleX, int moduleY, int contentSize, double scale, int border) {
        int inset = 9 - border;
        int localX = (int) (moduleX / scale), localY = (int) (moduleY / scale);
        int left = (int) Math.round((localX + inset) * scale), top = (int) Math.round((localY + inset) * scale);
        int right = (int) Math.round((localX + contentSize + 18 - inset) * scale);
        int bottom = (int) Math.round((localY + contentSize + 18 - inset) * scale);
        return new HudLayoutModel.Rect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }
    public static int moduleCoordinate(int visible, int contentSize, double scale, int border) {
        int candidate = (int) Math.round(visible - (9 - border) * scale);
        int result = candidate, error = Integer.MAX_VALUE, search = (int) Math.ceil(scale) + 2;
        for (int at = candidate - search; at <= candidate + search; at++) {
            int distance = Math.abs(visible(at, 0, contentSize, scale, border).x() - visible);
            if (distance < error || distance == error && Math.abs(at - candidate) < Math.abs(result - candidate)) {
                error = distance; result = at;
            }
        }
        return result;
    }
}
