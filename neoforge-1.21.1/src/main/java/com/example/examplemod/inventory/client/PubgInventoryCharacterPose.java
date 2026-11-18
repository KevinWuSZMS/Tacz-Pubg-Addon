package com.example.examplemod.inventory.client;

/** Mouse response and size limits for the enlarged vanilla inventory character. */
public record PubgInventoryCharacterPose(float angleX, float angleY, float scale) {
    public static final int DEPTH_OFFSET = 400;
    public static final float MAX_SCALE = 420;

    public static PubgInventoryCharacterPose of(int left, int top, int right, int bottom,
                                                double mouseX, double mouseY, float entityHeight) {
        double width = Math.max(1, right - left), height = Math.max(1, bottom - top);
        float horizontal = angle((left + right) / 2.0 - mouseX, Math.max(80, width * .8), .5f);
        float vertical = angle((top + bottom) / 2.0 - mouseY, Math.max(100, height * .7), .35f);
        float scale = (float) Math.min(MAX_SCALE, Math.min(Math.max(1, width - 12) / 1.25,
                Math.max(1, height - 16) / Math.max(2.2, entityHeight + .4)));
        return new PubgInventoryCharacterPose(horizontal, vertical, Math.max(1, scale));
    }

    private static float angle(double offset, double responseDistance, float limit) {
        if (!Double.isFinite(offset)) return 0;
        return (float) Math.clamp(Math.atan(offset / responseDistance), -limit, limit);
    }
}
