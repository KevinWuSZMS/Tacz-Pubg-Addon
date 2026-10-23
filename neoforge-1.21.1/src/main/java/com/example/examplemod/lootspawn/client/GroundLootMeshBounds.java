package com.example.examplemod.lootspawn.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Bounds of drawable quads, excluding TaCZ's missing-face placeholder at the bone origin. */
final class GroundLootMeshBounds implements VertexConsumer {
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
    private final double[][] quad = new double[4][3];
    private int next;

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        quad[next][0] = x; quad[next][1] = y; quad[next][2] = z;
        if (++next == 4) {
            next = 0;
            if (GroundLootQuadGeometry.hasArea(quad)) {
                for (double[] v : quad) {
                    minX = Math.min(minX, v[0]); minY = Math.min(minY, v[1]); minZ = Math.min(minZ, v[2]);
                    maxX = Math.max(maxX, v[0]); maxY = Math.max(maxY, v[1]); maxZ = Math.max(maxZ, v[2]);
                }
            }
        }
        return this;
    }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
}
