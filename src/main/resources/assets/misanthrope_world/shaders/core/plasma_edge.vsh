#version 150

// Plasma edge vertex shader.
// Vertices are supplied already in world space (no model matrix needed).
// Position.y is stored in the TEXCOORD1.y slot as trailT — how far along
// the trail this vertex is (0 = face surface, 1 = trail tip).

in vec3  Position;    // world-space position of the vertex
in vec2  UV0;         // .x = across-face U, .y = trailT (0=face, 1=tip)
in vec4  Color;       // per-vertex Mach-tint color packed by CPU

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2  trailUV;    // passed to fsh: .x = face U, .y = trailT
out vec4  vColor;     // Mach-tint color from CPU
out float fragDist;   // view-space depth for fog

void main() {
    vec4 viewPos   = ModelViewMat * vec4(Position, 1.0);
    gl_Position    = ProjMat * viewPos;
    fragDist       = length(viewPos.xyz);
    trailUV        = UV0;
    vColor         = Color;
}
