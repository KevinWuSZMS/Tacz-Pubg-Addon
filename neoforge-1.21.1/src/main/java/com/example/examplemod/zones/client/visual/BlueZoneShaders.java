package com.example.examplemod.zones.client.visual;

import com.example.examplemod.ExampleMod;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/** Dedicated render state; no global fog, terrain projection or chunk visibility changes. */
public final class BlueZoneShaders {
    private static ShaderInstance shader;
    private static ShaderInstance lodShader;
    private BlueZoneShaders() {}
    static ShaderInstance shader() { return shader; }
    static ShaderInstance lodShader() { return lodShader; }

    public static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "blue_zone"),
                    DefaultVertexFormat.POSITION_COLOR), loaded -> shader = loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load PUBG blue-zone shader", exception);
        }
        lodShader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "blue_zone_lod"),
                    DefaultVertexFormat.POSITION_COLOR), loaded -> lodShader = loaded);
        } catch (IOException | RuntimeException exception) {
            ExampleMod.LOGGER.warn("Cannot load optional Voxy blue-zone shader; retaining original renderer", exception);
        }
    }
}
