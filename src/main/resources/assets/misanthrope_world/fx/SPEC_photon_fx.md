# Photon FX Spec — Misanthrope VS Engine Aerodynamics

These are the design targets for the Photon `.fx` files authored in-game with `/photon`.
The custom triangle-mesh shader handles the main plasma wake geometry.
Photon's job is complementary: surface glow burst, ablation sparks, and the
ionisation shimmer at ship edges.

All effects live at: `assets/misanthrope_vs_engine/fx/`

---

## 1. `plasma_ablation.fx` — Ablation Sparks

**What it is:** Small hot fragments ablated from the ship's leading surface. These
are the tiny scattered bright dots visible in the background of the reference GIF.
Plays from each leading-edge sample point when intensity > 0.3.

**Emitter type:** ParticleEmitter

### Main settings
| Setting | Value |
|---|---|
| Duration | Looping |
| Emission rate | `intensity × 40` per second |
| Start lifetime | 0.4–1.2 s (random constant) |
| Start speed | 2–8 blocks/s |
| Start size | 0.05–0.15 (tiny) |
| Start color | Random between white `(1,1,1)` and orange `(1,0.5,0)` |
| Gravity multiplier | 0.3 (slight arc downward) |
| Simulation space | World |
| Renderer | Particle, Billboard, Layer: Transparent |
| Bloom effect | ON, Bloom color: `(1, 0.6, 0.1)` |

### Shape
| Setting | Value |
|---|---|
| Shape | Cone |
| Angle | 70° (wide spray) |
| Radius | 0.5 |
| Emit from | Base |
| Orientation | Point toward world `−velDir` (i.e. backward) |

### Velocity over lifetime
- Linear: starts at full speed, decays to 0 by end of lifetime
- Add a slight turbulence noise: strength 0.5, frequency 1.2

### Color over lifetime
| Time | Color | Alpha |
|---|---|---|
| 0 | White `(1,1,1)` | 1.0 |
| 0.3 | Orange `(1,0.5,0)` | 0.9 |
| 0.7 | Red `(0.7,0.1,0)` | 0.5 |
| 1.0 | Dark red `(0.3,0,0)` | 0.0 |

### Size over lifetime
- Starts at `startSize`, grows to 1.5× at 40% lifetime, shrinks to 0 at end.

---

## 2. `plasma_onset.fx` — Reentry Onset Flash

**What it is:** A single bright flash burst when the ship first crosses the Mach 5
threshold (intensity crosses from 0 to onset). Not looping — one-shot.

**Emitter type:** ParticleEmitter

### Main settings
| Setting | Value |
|---|---|
| Duration | 0.5 s |
| Stop action | Destroy |
| Emission mode | Burst |
| Burst count | 80 particles at t=0 |
| Start lifetime | 0.3–0.8 s |
| Start speed | 4–12 blocks/s (radial from center) |
| Start size | 0.2–0.5 |
| Start color | White `(1,1,1)` with alpha 1.0 |
| Simulation space | World |
| Bloom effect | ON, very high intensity: Bloom color `(1, 0.8, 0.4)` |

### Shape
| Setting | Value |
|---|---|
| Shape | Sphere |
| Radius | 0.5 |

### Color over lifetime
| Time | Color | Alpha |
|---|---|---|
| 0 | White `(1,1,1)` | 1.0 |
| 0.5 | Yellow `(1,0.9,0.3)` | 0.6 |
| 1.0 | Transparent | 0.0 |

---

## 3. `ionisation_shimmer.fx` — Edge Ionisation Shimmer

**What it is:** The faint blue-violet shimmer at the very leading tip of the ship
at high Mach (80+). Suggests ionised plasma corona. Looping, very subtle.

**Emitter type:** ParticleEmitter

### Main settings
| Setting | Value |
|---|---|
| Duration | Looping |
| Emission rate | `max(0, mach - 80) × 0.2` per second (very sparse) |
| Start lifetime | 0.15–0.4 s |
| Start speed | 0.3–1.0 blocks/s |
| Start size | 0.3–0.8 |
| Start color | Blue-violet `(0.5, 0.4, 1.0)` |
| Bloom effect | ON, Bloom color: `(0.4, 0.3, 1.0)` |

### Shape
| Setting | Value |
|---|---|
| Shape | Box |
| Size | Match leading face dimensions at runtime |

### Color over lifetime
| Time | Color | Alpha |
|---|---|---|
| 0 | Blue-violet `(0.5,0.4,1.0)` | 0.7 |
| 0.5 | Lighter violet `(0.7,0.6,1.0)` | 0.4 |
| 1.0 | White `(1,1,1)` | 0.0 |

### Size over lifetime
- Starts at startSize, expands to 2× then fades — creates "pop" appearance.

---

## Placement / invocation from code

All three are played via `MgePhotonEffects.playAt()` pattern (already used in MGE):

```java
// In PlasmaTrailRenderer.clientTick(), when Photon is loaded:
if (MgePhotonEffects.isLoaded()) {
    if (state.intensity() > 0.3f) {
        MgePhotonEffects.playAt(FX_PLASMA_ABLATION, level, shipPos);
    }
    if (justCrossedOnset) {
        MgePhotonEffects.playAt(FX_PLASMA_ONSET, level, shipPos);
    }
    if (state.mach() > 80f) {
        MgePhotonEffects.playAt(FX_IONISATION, level, shipPos);
    }
}
```

The corresponding constants and `tryLoad()` registration go in a new
`MVSPhotonEffects.java` (same pattern as `MgePhotonEffects.java` in MGE):
```java
public static final ResourceLocation FX_PLASMA_ABLATION = rl("plasma_ablation");
public static final ResourceLocation FX_PLASMA_ONSET    = rl("plasma_onset");
public static final ResourceLocation FX_IONISATION      = rl("ionisation_shimmer");
```

---

## Notes on bloom

Photon's bloom compositing (`unreal_composite.fsh`) picks up `fragData[1]` from
all Photon emitters AND from our custom `plasma_edge.fsh` (which also writes to
`fragData[1]`). The result is a unified bloom pass across both systems — the
triangle-mesh glow and the Photon sparks bloom together naturally without any
additional integration work.

If Photon is absent, our shader's `fragData[1]` write is silently discarded by
the GL driver. No crash, no visual artifacts — the plasma still renders, just
without bloom.
