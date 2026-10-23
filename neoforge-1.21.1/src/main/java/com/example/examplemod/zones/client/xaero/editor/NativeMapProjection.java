package com.example.examplemod.zones.client.xaero.editor;

/** GuiMap's actual framebuffer projection, with invertible GUI/input scaling. */
public record NativeMapProjection(double cameraX, double cameraZ, double pixelsPerBlock,
        int framebufferWidth, int framebufferHeight, int guiWidth, int guiHeight) {
    public record Point(double x, double z) {}

    public boolean valid() {
        return Double.isFinite(cameraX) && Double.isFinite(cameraZ) && Double.isFinite(pixelsPerBlock)
                && pixelsPerBlock > 0 && framebufferWidth > 0 && framebufferHeight > 0 && guiWidth > 0 && guiHeight > 0;
    }

    public Point screen(double worldX, double worldZ) {
        return new Point(((worldX - cameraX) * pixelsPerBlock + framebufferWidth * .5) * guiWidth / framebufferWidth,
                ((worldZ - cameraZ) * pixelsPerBlock + framebufferHeight * .5) * guiHeight / framebufferHeight);
    }

    public Point world(double guiX, double guiY) {
        return new Point(cameraX + (guiX * framebufferWidth / guiWidth - framebufferWidth * .5) / pixelsPerBlock,
                cameraZ + (guiY * framebufferHeight / guiHeight - framebufferHeight * .5) / pixelsPerBlock);
    }

    public double tolerance(double guiPixels) {
        return guiPixels * Math.max((double) framebufferWidth / guiWidth, (double) framebufferHeight / guiHeight) / pixelsPerBlock;
    }
    /** Scale with terrain zoom, capped in framebuffer pixels rather than enlarged GUI pixels. */
    public int stagingMarkerRadiusPixels() {
        return valid() ? (int) Math.round(Math.clamp(2 * pixelsPerBlock, 2, 5)) : 2;
    }
}
