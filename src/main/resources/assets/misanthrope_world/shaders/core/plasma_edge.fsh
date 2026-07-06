#version 150

// Plasma edge fragment shader.
// Renders the leading-edge plasma glow and trailing streaks.
//
// Colour ramp (driven by vColor.a encoding Mach tier + trailUV.y):
//   face  (trailT=0): white core  →  orange
//   trail (trailT=1): red  →  fully transparent
//
// Mach tier (vColor.rgb):
//   Mach 5–12   → orange  (1.0, 0.45, 0.0)
//   Mach 12–25  → yellow  (1.0, 0.85, 0.2)
//   Mach 25–80  → white   (1.0, 1.0,  1.0)
//   Mach 80+    → violet  (0.7, 0.5,  1.0)
//
// The CPU packs the Mach-tier colour into vColor.rgb.
// vColor.a carries turbulence seed (sin-phase offset, [0,1]).

uniform float Intensity;       // [0,1] overall heating intensity
uniform float Time;            // game time in seconds for animation
uniform float BloomStrength;   // how much bleeds into bloom buffer

out vec4 fragData[2];          // [0]=main, [1]=bloom (Photon-compatible)

in vec2  trailUV;
in vec4  vColor;
in float fragDist;

// ── Noise ────────────────────────────────────────────────────────────────────
// Fast hash-based value noise for turbulence along the trail.
float hash(float n) { return fract(sin(n) * 753.5453); }
float noise(float x) {
    float i = floor(x);
    float f = fract(x);
    f = f * f * (3.0 - 2.0 * f);  // smoothstep
    return mix(hash(i), hash(i + 1.0), f);
}

void main() {
    float trailT = trailUV.y;   // 0 = face, 1 = tip
    float faceU  = trailUV.x;   // 0–1 across the face

    // ── Turbulence ───────────────────────────────────────────────────────────
    // Animated noise rippling down the trail
    float seed   = vColor.a;    // per-vertex random offset
    float noiseT = trailT * 6.0 - Time * 2.5 + seed * 3.14;
    float turb   = noise(noiseT) * 0.5 + noise(noiseT * 2.3 + 1.7) * 0.25;
    // Turbulence fades near the face (clean edge) and near the tip (fades out)
    turb *= trailT * (1.0 - trailT * trailT) * 2.0;

    // ── Alpha (opacity) ──────────────────────────────────────────────────────
    // Sharp at face, exponential decay along trail, turbulence-modulated
    float baseAlpha = pow(1.0 - trailT, 1.5 + turb * 0.8);
    // Thin cross-section: slight feathering at edges of the face
    float edgeFade  = 1.0 - pow(abs(faceU * 2.0 - 1.0), 3.0);
    float alpha     = baseAlpha * edgeFade * Intensity;
    if (alpha < 0.005) discard;

    // ── Colour ramp ──────────────────────────────────────────────────────────
    // At the face (trailT=0): pure white core blends toward the Mach colour
    // Along the trail: shifts toward deep orange/red then transparent
    vec3 faceColor  = mix(vec3(1.0, 1.0, 1.0), vColor.rgb, trailT * 0.4);
    vec3 trailColor = mix(vColor.rgb, vec3(0.6, 0.05, 0.0), smoothstep(0.2, 1.0, trailT));
    vec3 color      = mix(faceColor, trailColor, trailT);

    // Turbulence brightens streaks: lighter patches inside the trail
    color = color + vec3(turb * 0.3 * (1.0 - trailT));

    vec4 finalColor = vec4(color, alpha);

    // ── Output ───────────────────────────────────────────────────────────────
    fragData[0] = finalColor;

    // Bloom buffer: face region glows intensely, trail less so
    float bloomA = alpha * BloomStrength * (1.0 - trailT * 0.7);
    fragData[1]  = vec4(color * bloomA, bloomA);
}
