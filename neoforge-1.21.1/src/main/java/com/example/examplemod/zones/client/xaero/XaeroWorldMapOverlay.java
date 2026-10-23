package com.example.examplemod.zones.client.xaero;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.zones.client.MapOverlayClient;
import com.example.examplemod.zones.client.xaero.editor.NativeMapEditor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import xaero.map.WorldMap;
import xaero.map.element.render.*;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Native world-map element, with no Xaero GUI injection, waypoint mutation or chunk cache writes. */
final class XaeroWorldMapOverlay extends ElementRenderer<Boolean, XaeroWorldMapOverlay.Context, XaeroWorldMapOverlay> {
    private static final Set<Object> REGISTERED = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean disabled;
    private XaeroWorldMapOverlay() { super(new Context(), new Provider(), new Reader()); }
    public static void install() {
        var handler = WorldMap.mapElementRenderHandler;
        if (handler == null || REGISTERED.contains(handler)) return;
        handler.add(new XaeroWorldMapOverlay()); REGISTERED.add(handler);
        ExampleMod.LOGGER.info("Registered PUBG overlay with Xaero World Map native element renderer");
    }
    @Override public int getOrder() { return -500; }
    @Override public boolean shouldBeDimScaled() { return false; }
    @Override public boolean shouldRenderHovered(boolean shadow) { return false; }
    @Override public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
        return !disabled && !shadow && location == ElementRenderLocation.WORLD_MAP
                && (!NativeMapEditor.allMaps().isEmpty() || XaeroLootOverlay.holdingFlag());
    }
    @Override public void preRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider textures, boolean shadow) { getContext().info = info; }
    @Override public void postRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider textures, boolean shadow) {}
    @Override public void renderElementShadow(Boolean element, boolean hovered, float optionalScale,
            double partialX, double partialZ, ElementRenderInfo info, GuiGraphics graphics,
            MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textures) {}
    @Override public boolean renderElement(Boolean element, boolean hovered, double depth, float optionalScale,
            double partialX, double partialZ, ElementRenderInfo info, GuiGraphics graphics,
            MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textures) {
        if (info.mapDimension == null) return false;
        var maps = NativeMapEditor.allMaps().stream().filter(m -> m.dimension().equals(info.mapDimension.location())).toList();
        boolean loot = XaeroLootOverlay.holdingFlag() && !NativeMapEditor.isEditing();
        if (maps.isEmpty() && !loot) return false;
        try {
            var window = Minecraft.getInstance().getWindow();
            // GuiMap.render offsets 3501..3546 undo Window.getGuiScale and center on raw framebuffer pixels;
            // offsets 7340..7353 apply map zoom, then MapElementRenderHandler.render 194..212 undo it.
            // The callback plane is raw pixels, not GUI-scaled pixels. info.scale is raw pixels per block.
            var projection = new XaeroOverlayGeometry.Projection(info.renderPos.x, info.renderPos.z, info.scale, 1, 0);
            var viewport = new XaeroOverlayGeometry.Viewport(window.getWidth() * .5, window.getHeight() * .5, false);
            for (var map : maps) XaeroOverlayDraw.draw(graphics, map, projection, viewport, depth, true);
            // The native editor already paints its draft's loot layer.
            if (loot) XaeroLootOverlay.draw(graphics, info.mapDimension.location(), maps, projection, viewport, depth + .06);
            return true;
        } catch (LinkageError | RuntimeException error) {
            if (!disabled) ExampleMod.LOGGER.warn("Xaero World Map PUBG overlay disabled after render adapter failure", error);
            disabled = true; return false;
        }
    }
    static final class Context { ElementRenderInfo info; boolean available; }
    private static final class Provider extends ElementRenderProvider<Boolean, Context> {
        @Override public void begin(ElementRenderLocation l, Context c) { c.available = c.info != null; }
        @Override public boolean hasNext(ElementRenderLocation l, Context c) { return c.available; }
        @Override public Boolean getNext(ElementRenderLocation l, Context c) { c.available = false; return Boolean.TRUE; }
        @Override public void end(ElementRenderLocation l, Context c) {}
    }
    private static final class Reader extends ElementReader<Boolean, Context, XaeroWorldMapOverlay> {
        @Override public boolean isHidden(Boolean e, Context c) { return false; }
        @Override public double getRenderX(Boolean e, Context c, float p) { return c.info.renderPos.x; }
        @Override public double getRenderZ(Boolean e, Context c, float p) { return c.info.renderPos.z; }
        @Override public boolean isInteractable(ElementRenderLocation l, Boolean e) { return false; }
        @Override public boolean isRightClickValid(Boolean e) { return false; }
        @Override public int getInteractionBoxLeft(Boolean e, Context c, float p) { return 0; }
        @Override public int getInteractionBoxRight(Boolean e, Context c, float p) { return 0; }
        @Override public int getInteractionBoxTop(Boolean e, Context c, float p) { return 0; }
        @Override public int getInteractionBoxBottom(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxLeft(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxRight(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxTop(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxBottom(Boolean e, Context c, float p) { return 0; }
        @Override public int getLeftSideLength(Boolean e, Minecraft m) { return 0; }
        @Override public String getMenuName(Boolean e) { return "PUBG"; }
        @Override public String getFilterName(Boolean e) { return "PUBG"; }
        @Override public int getMenuTextFillLeftPadding(Boolean e) { return 0; }
        @Override public int getRightClickTitleBackgroundColor(Boolean e) { return 0; }
        @Override public boolean shouldScaleBoxWithOptionalScale() { return false; }
    }
}
