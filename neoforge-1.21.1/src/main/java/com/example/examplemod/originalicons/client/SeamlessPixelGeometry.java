package com.example.examplemod.originalicons.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockMath;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Bakes the offline conforming pixel surface into ordinary item quads once per resource reload.
 * Explicit shared positions/UVs avoid FaceBakery's rectangle rewinding and per-face UV shrinking.
 */
public final class SeamlessPixelGeometry implements IUnbakedGeometry<SeamlessPixelGeometry> {
    public static final int MAX_QUADS = 20_000;
    public static final ResourceLocation LOADER_ID = ResourceLocation.fromNamespaceAndPath("tacz_pubg", "seamless_pixel");
    public static final IGeometryLoader<SeamlessPixelGeometry> LOADER = new Loader();
    private static final ResourceLocation CUTOUT = ResourceLocation.withDefaultNamespace("cutout");
    private final List<Quad> quads;

    record Quad(Direction face, float[] vertices) {
        Quad { vertices = vertices.clone(); }
    }
    private SeamlessPixelGeometry(List<Quad> quads) { this.quads = List.copyOf(quads); }
    int quadCount() { return quads.size(); }

    static SeamlessPixelGeometry read(JsonObject object) {
        if (!object.has("seamless_quads") || !object.get("seamless_quads").isJsonArray())
            throw new JsonParseException("seamless_pixel requires a seamless_quads array");
        var array = object.getAsJsonArray("seamless_quads");
        if (array.isEmpty() || array.size() > MAX_QUADS)
            throw new JsonParseException("seamless_pixel requires 1.." + MAX_QUADS + " quads");
        var result = new ArrayList<Quad>(array.size());
        for (int index = 0; index < array.size(); index++) {
            try {
                var quad = array.get(index).getAsJsonObject();
                String name = quad.get("face").getAsString();
                Direction face = Direction.byName(name);
                if (face == null || !face.getSerializedName().equals(name)) throw new IllegalArgumentException("Unknown face " + name);
                var vertices = quad.getAsJsonArray("vertices");
                if (vertices.size() != 20) throw new IllegalArgumentException("Expected exactly four x,y,z,u,v vertices");
                float[] values = new float[20];
                for (int at = 0; at < values.length; at++) {
                    var value = vertices.get(at);
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                        throw new IllegalArgumentException("Expected numeric coordinates");
                    double coordinate = value.getAsDouble();
                    if (!Double.isFinite(coordinate) || coordinate < 0 || coordinate > 16)
                        throw new IllegalArgumentException("Position and UV coordinates must be finite and within 0..16");
                    values[at] = (float) coordinate;
                }
                validateFace(face, values);
                result.add(new Quad(face, values));
            } catch (RuntimeException invalid) {
                throw new JsonParseException("Invalid seamless pixel quad " + index + ": " + invalid.getMessage(), invalid);
            }
        }
        return new SeamlessPixelGeometry(result);
    }

    private static void validateFace(Direction face, float[] vertices) {
        int axis = switch (face.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
        for (int vertex = 1; vertex < 4; vertex++)
            if (Math.abs(vertices[vertex * 5 + axis] - vertices[axis]) > 1e-5)
                throw new IllegalArgumentException("Vertices are not on the declared face plane");
        Vector3f first = position(vertices, 0);
        Vector3f area = new Vector3f();
        // Fourth == third is a valid triangle; do not re-expand it to an enclosing rectangle.
        for (int corner = 1; corner < 3; corner++)
            area.add(position(vertices, corner).sub(first).cross(position(vertices, corner + 1).sub(first)));
        var normal = face.getNormal();
        if (!area.isFinite() || area.dot(normal.getX(), normal.getY(), normal.getZ()) <= 1e-10)
            throw new IllegalArgumentException("Degenerate or inward-facing seamless quad");
    }
    private static Vector3f position(float[] vertices, int index) {
        int at = index * 5;
        return new Vector3f(vertices[at], vertices[at + 1], vertices[at + 2]);
    }

    @Override public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
        var sprite = spriteGetter.apply(context.getMaterial("0"));
        var particle = context.hasMaterial("particle") ? spriteGetter.apply(context.getMaterial("particle")) : sprite;
        var builder = new SimpleBakedModel.Builder(context.useAmbientOcclusion(), context.useBlockLight(),
                context.isGui3d(), context.getTransforms(), overrides).particle(particle);
        Matrix4f positions = positionTransform(modelState, context.getRootTransform());
        Matrix3f normals = new Matrix3f(positions).invert().transpose();
        if (!positions.isFinite() || !normals.isFinite()) throw new JsonParseException("Invalid seamless model transform");
        var uvTransforms = new EnumMap<Direction, Matrix4f>(Direction.class);
        for (var quad : quads) {
            Matrix4f uvs = uvTransforms.computeIfAbsent(quad.face, face -> modelState.isUvLocked()
                    ? BlockMath.getUVLockTransform(modelState.getRotation(), face).getMatrix() : new Matrix4f());
            int[] packed = pack(quad, positions, normals, uvs, u -> sprite.getU((float) u), v -> sprite.getV((float) v));
            var normal = transformedNormal(quad.face, normals);
            builder.addUnculledFace(new BakedQuad(packed, -1, Direction.getNearest(normal.x, normal.y, normal.z),
                    sprite, true, context.useAmbientOcclusion()));
        }
        return builder.build(context.getRenderType(CUTOUT));
    }

    /** Same origin and root/state composition as NeoForge ElementsModel/FaceBakery. */
    static Matrix4f positionTransform(ModelState state, com.mojang.math.Transformation root) {
        var composed = UnbakedGeometryHelper.composeRootTransformIntoModelState(state, root).getRotation();
        return new Matrix4f().translation(.5f, .5f, .5f).mul(composed.getMatrix()).translate(-.5f, -.5f, -.5f);
    }

    static int[] pack(Quad quad, Matrix4f positions, Matrix3f normals, Matrix4f uvs,
            DoubleUnaryOperator atlasU, DoubleUnaryOperator atlasV) {
        int[] packed = new int[IQuadTransformer.STRIDE * 4];
        Vector3f normal = transformedNormal(quad.face, normals);
        int packedNormal = ((byte) Math.round(normal.x * 127) & 255)
                | (((byte) Math.round(normal.y * 127) & 255) << 8)
                | (((byte) Math.round(normal.z * 127) & 255) << 16);
        boolean mirrored = positions.determinant3x3() < 0;
        for (int corner = 0; corner < 4; corner++) {
            int source = (mirrored && corner > 0 ? 4 - corner : corner) * 5;
            int target = corner * IQuadTransformer.STRIDE;
            Vector3f position = positions.transformPosition(new Vector3f(quad.vertices[source] / 16,
                    quad.vertices[source + 1] / 16, quad.vertices[source + 2] / 16));
            Vector3f uv = uvs.transformPosition(new Vector3f(quad.vertices[source + 3] / 16, quad.vertices[source + 4] / 16, 0));
            packed[target + IQuadTransformer.POSITION] = Float.floatToRawIntBits(position.x);
            packed[target + IQuadTransformer.POSITION + 1] = Float.floatToRawIntBits(position.y);
            packed[target + IQuadTransformer.POSITION + 2] = Float.floatToRawIntBits(position.z);
            packed[target + IQuadTransformer.COLOR] = -1;
            // Atlas conversion is applied to the authored coordinate directly. There is deliberately
            // no per-quad inset/shrink: neighboring subdivisions retain exactly the same shared UV.
            packed[target + IQuadTransformer.UV0] = Float.floatToRawIntBits((float) atlasU.applyAsDouble(uv.x));
            packed[target + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits((float) atlasV.applyAsDouble(uv.y));
            packed[target + IQuadTransformer.NORMAL] = packedNormal;
        }
        return packed;
    }
    private static Vector3f transformedNormal(Direction face, Matrix3f matrix) {
        var direction = face.getNormal();
        Vector3f result = matrix.transform(new Vector3f(direction.getX(), direction.getY(), direction.getZ())).normalize();
        if (!result.isFinite()) throw new JsonParseException("Degenerate seamless model normal");
        return result;
    }
    private static final class Loader implements IGeometryLoader<SeamlessPixelGeometry> {
        @Override public SeamlessPixelGeometry read(JsonObject object, JsonDeserializationContext deserializer) {
            return SeamlessPixelGeometry.read(object);
        }
    }
}
