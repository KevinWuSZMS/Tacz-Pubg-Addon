#version 150

in vec3 Position;
in vec4 Color;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 LodTransform;
out vec4 vertexColor;
out vec2 originalDepth;
out vec4 lodProjected;

void main() {
    vec4 projected = ProjMat * ModelViewMat * vec4(Position, 1.0);
    originalDepth = projected.zw;
    lodProjected = LodTransform * vec4(Position, 1.0);
    gl_Position = projected;
    if (projected.w > 0.0) {
        gl_Position.z = min(projected.z, projected.w);
    }
    vertexColor = Color;
}
