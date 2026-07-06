#version 150

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float u_age;

out vec4 v_color;
out vec3 v_normal;
out float v_rim;

void main() {
    vec4 worldPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * worldPos;

    // Rim lighting: dot(normal, view direction)
    // Normal is the unit-sphere outward normal = vertex position on unit sphere
    // View direction in view space is (0,0,-1) (camera looks down -Z)
    vec3 viewNormal = normalize(mat3(ModelViewMat) * Normal);
    v_rim   = 1.0 - abs(dot(viewNormal, vec3(0.0, 0.0, 1.0)));

    v_color  = Color;
    v_normal = Normal;
}
