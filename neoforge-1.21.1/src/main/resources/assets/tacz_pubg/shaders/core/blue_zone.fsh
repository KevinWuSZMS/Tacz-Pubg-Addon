#version 150

in vec4 vertexColor;
in vec2 originalDepth;
uniform vec4 ColorModulator;
out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    if (color.a <= 0.0) {
        discard;
    }
    // Keep the scene's original depth mapping even on quads crossing its far plane.
    // Distant fragments sit at the clear-sky depth and remain hidden by nearer terrain.
    gl_FragDepth = clamp(0.5 * originalDepth.x / originalDepth.y + 0.5, 0.0, 1.0);
    fragColor = color;
}
