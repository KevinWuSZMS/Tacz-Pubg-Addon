package com.example.examplemod.zones.client.xaero;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.zones.client.MapOverlayClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import xaero.common.HudMod;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.hud.minimap.element.render.*;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Native OVER_MINIMAP element works in Xaero's normal FBO and safe rendering modes. */
final class XaeroMinimapOverlay extends MinimapElementRenderer<Boolean, XaeroMinimapOverlay.Context> {
    private static final Set<Object> REGISTERED = Collections.newSetFromMap(new WeakHashMap<>());
    private final Object handler;
    private final XaeroMinimapStateFields stateFields;
    private boolean disabled;

    private XaeroMinimapOverlay(Object handler) throws ReflectiveOperationException {
        super(new Reader(), new Provider(), new Context()); this.handler = handler;
        // Xaero 26.6.0 returns common...over.MinimapElementOverMapRendererHandler, while the fields
        // are private in its hud...over superclass. getDeclaredField on the runtime class cannot find them.
        stateFields = new XaeroMinimapStateFields(handler.getClass());
    }
    public static void install() throws ReflectiveOperationException {
        var mod = HudMod.INSTANCE;
        if (mod == null || !mod.isLoadedClient() || mod.getMinimap() == null) return;
        // Public getter and public extension registration; no replacement of Xaero render code.
        MinimapElementRendererHandler handler = mod.getMinimap().getOverMapRendererHandler();
        if (handler == null || REGISTERED.contains(handler)) return;
        handler.add(new XaeroMinimapOverlay(handler)); REGISTERED.add(handler);
        ExampleMod.LOGGER.info("Registered PUBG overlay with Xaero Minimap native element renderer ({})", handler.getClass().getName());
    }
    @Override public int getOrder() { return -500; }
    @Override public boolean shouldRender(MinimapElementRenderLocation location) {
        return !disabled && location == MinimapElementRenderLocation.OVER_MINIMAP
                && (!MapOverlayClient.allMaps().isEmpty() || XaeroLootOverlay.holdingFlag());
    }
    @Override public void preRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider textures) { getContext().info = info; }
    @Override public void postRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider textures) {}
    @Override public boolean renderElement(Boolean element, boolean highlighted, boolean outside, double depth,
            float optionalScale, double partialX, double partialZ, MinimapElementRenderInfo info,
            GuiGraphics graphics, MultiBufferSource.BufferSource buffers) {
        if (info.mapDimension == null) return false;
        var maps = MapOverlayClient.allMaps().stream().filter(m -> m.dimension().equals(info.mapDimension.location())).toList();
        if (maps.isEmpty() && !XaeroLootOverlay.holdingFlag()) return false;
        try {
            var frame = stateFields.read(handler);
            var projection = new XaeroOverlayGeometry.Projection(info.renderPos.x, info.renderPos.z,
                    frame.zoom(), frame.ps(), frame.pc());
            var viewport = new XaeroOverlayGeometry.Viewport(frame.halfWidth(), frame.halfHeight(), frame.circle());
            for (var map : maps) XaeroOverlayDraw.draw(graphics, map, projection, viewport, depth, false);
            XaeroLootOverlay.draw(graphics, info.mapDimension.location(), maps, projection, viewport, depth + .06);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            if (!disabled) ExampleMod.LOGGER.warn("Xaero Minimap PUBG overlay disabled after render adapter failure", error);
            disabled = true; return false;
        }
    }
    static final class Context { MinimapElementRenderInfo info; boolean available; }
    private static final class Provider extends MinimapElementRenderProvider<Boolean, Context> {
        @Override public void begin(MinimapElementRenderLocation location, Context context) { context.available = context.info != null; }
        @Override public boolean hasNext(MinimapElementRenderLocation location, Context context) { return context.available; }
        @Override public Boolean getNext(MinimapElementRenderLocation location, Context context) { context.available = false; return Boolean.TRUE; }
        @Override public void end(MinimapElementRenderLocation location, Context context) {}
    }
    private static final class Reader extends MinimapElementReader<Boolean, Context> {
        @Override public boolean isHidden(Boolean e, Context c) { return false; }
        @Override public double getRenderX(Boolean e, Context c, float ticks) { return c.info.renderPos.x; }
        @Override public double getRenderY(Boolean e, Context c, float ticks) { return c.info.renderPos.y; }
        @Override public double getRenderZ(Boolean e, Context c, float ticks) { return c.info.renderPos.z; }
        @Override public double getCoordinateScale(Boolean e, Context c, MinimapElementRenderInfo info) { return info.backgroundCoordinateScale; }
        @Override public boolean isInteractable(MinimapElementRenderLocation l, Boolean e) { return false; }
        @Override public int getInteractionBoxLeft(Boolean e, Context c, float p) { return 0; }
        @Override public int getInteractionBoxRight(Boolean e, Context c, float p) { return 0; }
        @Override public int getInteractionBoxTop(Boolean e, Context c, float p) { return 0; }
        @Override public int getInteractionBoxBottom(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxLeft(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxRight(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxTop(Boolean e, Context c, float p) { return 0; }
        @Override public int getRenderBoxBottom(Boolean e, Context c, float p) { return 0; }
        @Override public int getLeftSideLength(Boolean e, net.minecraft.client.Minecraft m) { return 0; }
        @Override public String getMenuName(Boolean e) { return "PUBG"; }
        @Override public String getFilterName(Boolean e) { return "PUBG"; }
        @Override public int getMenuTextFillLeftPadding(Boolean e) { return 0; }
        @Override public int getRightClickTitleBackgroundColor(Boolean e) { return 0; }
        @Override public boolean shouldScaleBoxWithOptionalScale() { return false; }
    }
}
