#version 150

in vec3 Position;
in vec4 Color;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec4 vertexColor;
out vec2 originalDepth;

void main() {
    vec4 projected = ProjMat * ModelViewMat * vec4(Position, 1.0);
    originalDepth = projected.zw;
    gl_Position = projected;
    // Admit only this curtain beyond the terrain far plane. Near and side clipping stay intact.
    if (projected.w > 0.0) {
        gl_Position.z = min(projected.z, projected.w);
    }
    vertexColor = Color;
}
