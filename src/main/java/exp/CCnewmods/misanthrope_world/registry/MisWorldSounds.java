package exp.CCnewmods.misanthrope_world.registry;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers all custom {@link SoundEvent}s for Misanthrope World.
 *
 * <p>The actual audio files are referenced by {@code sounds.json}; this class
 * only creates the Forge registry entries that give each sound an ID.
 *
 * <h3>Pressure sounds</h3>
 * <ul>
 *   <li>{@link #HURT_CRUSH}   — played when an entity takes crush damage</li>
 *   <li>{@link #HURT_VACUUM}  — played when an entity takes vacuum/decompression damage</li>
 *   <li>{@link #DEATH_CRUSH}  — played on death by crushing pressure</li>
 *   <li>{@link #DEATH_VACUUM} — played on death by vacuum/decompression</li>
 * </ul>
 *
 * <h3>Sonic boom (ported from MVSE's MVSSounds)</h3>
 * <ul>
 *   <li>{@link #SONIC_BOOM_CLOSE}/{@link #SONIC_BOOM_MEDIUM}/{@link #SONIC_BOOM_FAR}
 *       — distance-scaled boom variants, played by SoundBarrierClientHandler</li>
 *   <li>{@link #SUPERSONIC_RUMBLE} — registered but never actually played anywhere
 *       in MVSE either; ported as-is, flagging in case it's meant to be wired up</li>
 * </ul>
 *
 * <h3>Reentry (ported from MVSE's MVSSounds, retroactively — missed in the
 * reentry-system port since MVSSounds wasn't looked at that phase)</h3>
 * <ul>
 *   <li>{@link #REENTRY_ROAR} — also registered but never played anywhere in
 *       MVSE; ported as-is, same flag as SUPERSONIC_RUMBLE above</li>
 * </ul>
 *
 * <p>Call {@link #register(IEventBus)} from the mod constructor.
 */
public final class MisWorldSounds {

    private MisWorldSounds() {}

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Misanthrope_world.MODID);

    // ── Pressure ──────────────────────────────────────────────────────────────

    /** Wet creak / structural groan — played while being crushed by pressure. */
    public static final RegistryObject<SoundEvent> HURT_CRUSH =
            register("entity.generic.hurt_pressure_crush");

    /** Airy hiss / pop — played while suffering decompression / vacuum damage. */
    public static final RegistryObject<SoundEvent> HURT_VACUUM =
            register("entity.generic.hurt_pressure_vacuum");

    /** Death sting for crush — heavier, final crunch. */
    public static final RegistryObject<SoundEvent> DEATH_CRUSH =
            register("entity.generic.death_pressure_crush");

    /** Death sting for vacuum — silent rush of escaping air. */
    public static final RegistryObject<SoundEvent> DEATH_VACUUM =
            register("entity.generic.death_pressure_vacuum");

    // ── Sonic boom ────────────────────────────────────────────────────────────

    /** Sharp crack / boom — player within 32 blocks of the crossing. */
    public static final RegistryObject<SoundEvent> SONIC_BOOM_CLOSE =
            register("sonic_boom_close");

    /** Mid-distance boom — 32-128 blocks. */
    public static final RegistryObject<SoundEvent> SONIC_BOOM_MEDIUM =
            register("sonic_boom_medium");

    /** Distant rumble — over 128 blocks. */
    public static final RegistryObject<SoundEvent> SONIC_BOOM_FAR =
            register("sonic_boom_far");

    /** Continuous low-frequency rumble while a ship is supersonic. Unused currently. */
    public static final RegistryObject<SoundEvent> SUPERSONIC_RUMBLE =
            register("supersonic_rumble");

    // ── Reentry ───────────────────────────────────────────────────────────────

    /** Atmospheric plasma roar while a ship is hypersonic. Unused currently. */
    public static final RegistryObject<SoundEvent> REENTRY_ROAR =
            register("reentry_roar");

    // ── Wiring ────────────────────────────────────────────────────────────────

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static RegistryObject<SoundEvent> register(String path) {
        ResourceLocation id = new ResourceLocation(Misanthrope_world.MODID, path);
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
