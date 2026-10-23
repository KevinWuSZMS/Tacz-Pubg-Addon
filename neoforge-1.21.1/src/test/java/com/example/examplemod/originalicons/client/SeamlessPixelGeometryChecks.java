package com.example.examplemod.originalicons.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.math.Transformation;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.SimpleModelState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Cached quad data, shared UV continuity and origin transforms; no textures, GPU or game startup. */
public final class SeamlessPixelGeometryChecks {
    private static int checks;
    private static final float[] FACE = {16,0,0,0,0, 16,16,0,0,16, 16,16,16,16,16, 16,0,16,16,0};
    public static void main(String[] args) {
        check(SeamlessPixelGeometry.read(model(FACE)).quadCount() == 1, "Loader reads a valid outward-facing rectangle");
        float[] triangle = FACE.clone();
        System.arraycopy(triangle, 10, triangle, 15, 5);
        check(SeamlessPixelGeometry.read(model(triangle)).quadCount() == 1, "Fourth vertex may repeat the third for an exact triangle");
        rejects(() -> SeamlessPixelGeometry.read(new JsonObject()), "Missing precomputed geometry is rejected");
        JsonObject empty = new JsonObject(); empty.add("seamless_quads", new JsonArray());
        rejects(() -> SeamlessPixelGeometry.read(empty), "Empty geometry is rejected");
        JsonObject unknown = model(FACE); unknown.getAsJsonArray("seamless_quads").get(0).getAsJsonObject().addProperty("face", "sideways");
        rejects(() -> SeamlessPixelGeometry.read(unknown), "Unknown normal is rejected");
        rejects(() -> SeamlessPixelGeometry.read(model(new float[19])), "Vertex packing must have exactly twenty components");
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -.01f, 16.01f}) {
            float[] values = FACE.clone(); values[3] = invalid;
            rejects(() -> SeamlessPixelGeometry.read(model(values)), "Nonfinite or out-of-atlas values are rejected");
        }
        float[] bent = FACE.clone(); bent[5] = 15;
        rejects(() -> SeamlessPixelGeometry.read(model(bent)), "Vertices must remain on their declared pixel-face plane");
        float[] inward = new float[20];
        for (int corner = 0; corner < 4; corner++) System.arraycopy(FACE, (3 - corner) * 5, inward, corner * 5, 5);
        rejects(() -> SeamlessPixelGeometry.read(model(inward)), "Inward winding is rejected instead of becoming an invisible culled model");
        float[] zero = FACE.clone();
        for (int corner = 1; corner < 4; corner++) System.arraycopy(zero, 0, zero, corner * 5, 5);
        rejects(() -> SeamlessPixelGeometry.read(model(zero)), "Empty triangles cannot become invalid normals");
        JsonObject tooMany = model(FACE);
        var repeated = tooMany.getAsJsonArray("seamless_quads"); var one = repeated.get(0);
        for (int count = 1; count <= SeamlessPixelGeometry.MAX_QUADS; count++) repeated.add(one);
        rejects(() -> SeamlessPixelGeometry.read(tooMany), "Model size is bounded before allocating baked vertex data");

        var quad = new SeamlessPixelGeometry.Quad(Direction.EAST, FACE);
        int[] packed = pack(quad, new Matrix4f());
        check(packed.length == IQuadTransformer.STRIDE * 4, "Baked vertices use the active NeoForge block-vertex stride");
        for (int vertex = 0; vertex < 4; vertex++) {
            int at = vertex * IQuadTransformer.STRIDE;
            near(value(packed, at + IQuadTransformer.POSITION), 1, "Model units are divided by sixteen");
            check(packed[at + IQuadTransformer.COLOR] == -1, "Unmodified opaque white tint preserves authored atlas colors");
            check((packed[at + IQuadTransformer.NORMAL] & 255) == 127, "Packed per-vertex normal follows declared east face");
        }
        near(value(packed, IQuadTransformer.UV0), .25, "Zero UV uses exact authored atlas edge, with no per-quad inset");
        near(value(packed, 2 * IQuadTransformer.STRIDE + IQuadTransformer.UV0), .75, "Sixteen UV uses the exact opposite atlas edge");
        int[] tri = pack(new SeamlessPixelGeometry.Quad(Direction.EAST, triangle), new Matrix4f());
        for (int channel = 0; channel < IQuadTransformer.STRIDE; channel++)
            check(tri[2 * IQuadTransformer.STRIDE + channel] == tri[3 * IQuadTransformer.STRIDE + channel],
                    "Triangle fourth point is preserved, never reconstructed as a rectangle");

        // A split in a neighboring face must retain exactly the same packed position and UV.
        float[] shared = {16,0,8,8,0, 16,16,8,8,16, 16,16,16,16,16, 16,0,16,16,0};
        float[] adjacent = {16,0,0,0,0, 16,16,0,0,16, 16,16,8,8,16, 16,0,8,8,0};
        int[] first = pack(new SeamlessPixelGeometry.Quad(Direction.EAST, shared), new Matrix4f());
        int[] second = pack(new SeamlessPixelGeometry.Quad(Direction.EAST, adjacent), new Matrix4f());
        for (int channel : new int[]{IQuadTransformer.POSITION, IQuadTransformer.POSITION + 1,
                IQuadTransformer.POSITION + 2, IQuadTransformer.UV0, IQuadTransformer.UV0 + 1})
            check(first[channel] == second[3 * IQuadTransformer.STRIDE + channel],
                    "Conforming neighboring faces keep bit-identical edge coordinates and UVs");

        var root = new Transformation(new Matrix4f().translation(.2f, .1f, -.1f));
        Matrix4f placed = SeamlessPixelGeometry.positionTransform(new SimpleModelState(Transformation.identity()), root);
        Vector3f origin = placed.transformPosition(new Vector3f());
        near(origin.x, .2, "Context root translation is retained"); near(origin.y, .1, "Context root Y translation is retained");
        var rotation = new Transformation(new Matrix4f().rotateY((float) Math.PI / 2));
        Matrix4f turned = SeamlessPixelGeometry.positionTransform(new SimpleModelState(rotation), Transformation.identity());
        Vector3f center = turned.transformPosition(new Vector3f(.5f, .5f, .5f));
        near(center.x, .5, "ModelState rotates about the block center"); near(center.z, .5, "Center is not displaced by ModelState rotation");
        int[] transformed = pack(quad, turned);
        int normal = transformed[IQuadTransformer.NORMAL];
        check(Math.abs((byte) (normal & 255)) <= 1 && (byte) ((normal >> 16) & 255) < -125,
                "ModelState rotation transforms cardinal normals along with positions");
        int[] reflected = pack(quad, new Matrix4f().scaling(-1, 1, 1));
        check((byte) (reflected[IQuadTransformer.NORMAL] & 255) == -127,
                "Mirrored roots transform the outward normal");
        Vector3f edgeA = bakedPosition(reflected, 1).sub(bakedPosition(reflected, 0));
        Vector3f edgeB = bakedPosition(reflected, 2).sub(bakedPosition(reflected, 0));
        check(edgeA.cross(edgeB).x < 0, "Mirrored roots reverse corner winding so outward faces remain visible");
        verifyBundledMeshes();
        System.out.println("SeamlessPixelGeometryChecks: " + checks + " checks passed; no renderer or game launched.");
    }
    private static void verifyBundledMeshes() {
        int found = 0;
        for (String id : com.example.examplemod.modelscale.ModelScaleSettings.ITEMS) {
            String path = "/assets/tacz_pubg/models/item/" + id.substring(id.indexOf(':') + 1) + ".json";
            try (var stream = SeamlessPixelGeometryChecks.class.getResourceAsStream(path)) {
                if (stream == null) continue;
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (!json.has("loader") || !json.get("loader").getAsString().equals(SeamlessPixelGeometry.LOADER_ID.toString())) continue;
                check(SeamlessPixelGeometry.read(json).quadCount() > 0, "Actual bundled geometry satisfies runtime validation: " + id);
                found++;
            } catch (java.io.IOException error) { throw new AssertionError("Unable to check bundled mesh " + path, error); }
        }
        check(found > 0, "The new loader is exercised by actual installed model resources");
    }
    private static Vector3f bakedPosition(int[] packed, int vertex) {
        int at = vertex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
        return new Vector3f(value(packed, at), value(packed, at + 1), value(packed, at + 2));
    }
    private static int[] pack(SeamlessPixelGeometry.Quad quad, Matrix4f transform) {
        return SeamlessPixelGeometry.pack(quad, transform, new Matrix3f(transform).invert().transpose(), new Matrix4f(),
                u -> .25 + .5 * u, v -> .125 + .75 * v);
    }
    private static float value(int[] packed, int index) { return Float.intBitsToFloat(packed[index]); }
    private static JsonObject model(float[] values) {
        var object = new JsonObject(); var array = new JsonArray(); var quad = new JsonObject(); var vertices = new JsonArray();
        quad.addProperty("face", "east"); for (float value : values) vertices.add(value);
        quad.add("vertices", vertices); array.add(quad); object.add("seamless_quads", array); return object;
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); } catch (JsonParseException expected) { checks++; return; }
        throw new AssertionError(reason);
    }
    private static void near(double actual, double expected, String reason) { check(Math.abs(actual - expected) < 1e-6, reason); }
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
}
