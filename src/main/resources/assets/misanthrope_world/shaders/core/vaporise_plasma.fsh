#version 150

uniform float u_age;
uniform float u_alpha;

in vec4  v_color;
in vec3  v_normal;
in float v_rim;

out vec4 fragColor;

void main() {
    // Base colour comes from the CPU (age-driven gradient uploaded per-front)
    vec3 baseColor = v_color.rgb;

    // Rim glow: edges of the sphere glow brighter than the centre
    // This makes the sphere look like a shell rather than a filled ball
    float rimPow = pow(v_rim, 1.8);

    // Inner shell transparency: make the centre slightly transparent so the
    // sphere looks hollow / like an expanding shockwave shell
    // at v_rim=0 (front face centre), alpha is reduced; at v_rim=1 (edge), full
    float shellAlpha = mix(0.15, 1.0, rimPow);

    // Hot-core flare: very young plasma (age < 0.2) has a bright interior
    float coreFlare = max(0.0, (0.2 - u_age) / 0.2) * (1.0 - v_rim) * 2.5;

    vec3 finalColor = baseColor * (1.0 + rimPow * 0.6 + coreFlare);

    // Total alpha: shell shape * global age fade * per-vertex alpha from CPU
    float finalAlpha = shellAlpha * u_alpha * v_color.a;

    // Clamp output — additive blend means we don't need to worry about
    // over-brightening the alpha channel, only the colour
    fragColor = vec4(min(finalColor, vec3(3.0)), finalAlpha);
}
