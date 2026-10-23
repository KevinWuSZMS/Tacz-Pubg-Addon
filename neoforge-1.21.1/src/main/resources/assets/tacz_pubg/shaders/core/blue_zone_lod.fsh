#version 150

in vec4 vertexColor;
in vec2 originalDepth;
in vec4 lodProjected;
uniform vec4 ColorModulator;
uniform sampler2D LodDepth;
uniform vec2 MainTargetSize;
out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    if (color.a <= 0.0) {
        discard;
    }
    // Voxy uses its own near/far planes. Its original texture retains distance even
    // when the shader pack excludes LODs from vanilla depth, or the depth blit saturates.
    ivec2 size = textureSize(LodDepth, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy / MainTargetSize * vec2(size)), ivec2(0), size - 1);
    float sceneDepth = texelFetch(LodDepth, pixel, 0).r;
    // Zero is Voxy's native-terrain mask; one is sky. Neither describes LOD terrain.
    if (sceneDepth > 0.0 && sceneDepth < 1.0 && lodProjected.w > 0.0) {
        float curtainDepth = 0.5 * lodProjected.z / lodProjected.w + 0.5;
        if (curtainDepth > sceneDepth + 1.0 / 16777215.0) {
            discard;
        }
    }
    // Match Voxy's maximum D24 blit depth, leaving vanilla LEQUAL to hide this
    // overlay behind native terrain and entities. This pass never writes depth.
    gl_FragDepth = clamp(0.5 * originalDepth.x / originalDepth.y + 0.5,
            0.0, 1.0 - 1.0 / 16777215.0);
    fragColor = color;
}
