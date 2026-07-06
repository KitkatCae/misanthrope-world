package exp.CCnewmods.misanthrope_world.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;

/**
 * {@link ResourceKey} constants for MisanthrΩpe World's custom damage types.
 *
 * <p>In 1.20.1 damage types are fully data-driven: the actual {@link DamageType}
 * instances live in {@code data/misanthrope_world/damage_type/*.json} and are
 * loaded by the server's built-in registry system.  These keys are only used to
 * create {@link DamageSource} instances at runtime — no code-side registration
 * is required beyond declaring the keys and providing the JSON files.
 *
 * <h3>Death messages</h3>
 * The {@code message_id} field in each JSON drives the translation key:
 * <pre>
 *   death.attack.&lt;message_id&gt;               — killed by something else
 *   death.attack.&lt;message_id&gt;.player        — killed by a player (if applicable)
 * </pre>
 * Both keys are present in {@code en_us.json}.
 *
 * <h3>Sound subtitles</h3>
 * Sound events {@code misanthrope_world:entity.generic.hurt_pressure_crush} and
 * {@code misanthrope_world:entity.generic.hurt_pressure_vacuum} are declared in
 * {@code sounds.json}.  Subtitle translation keys follow the standard pattern
 * {@code subtitles.misanthrope_world.entity.generic.hurt_pressure_*}.
 */
public final class MisWorldDamageTypes {

    private MisWorldDamageTypes() {}

    /** Creature crushed by excess ambient pressure (deep water, pressurised gas). */
    public static final ResourceKey<DamageType> CRUSH = key("crush");

    /** Creature damaged by decompression / vacuum (high altitude, space). */
    public static final ResourceKey<DamageType> VACUUM = key("vacuum");

    // ── Factory helpers ───────────────────────────────────────────────────────

    /**
     * Creates a {@link DamageSource} for crush damage using the level's live
     * registry.  Call server-side only.
     */
    public static DamageSource crush(Level level) {
        return new DamageSource(
                level.registryAccess()
                        .registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(CRUSH));
    }

    /**
     * Creates a {@link DamageSource} for vacuum/decompression damage.
     * Call server-side only.
     */
    public static DamageSource vacuum(Level level) {
        return new DamageSource(
                level.registryAccess()
                        .registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(VACUUM));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static ResourceKey<DamageType> key(String path) {
        return ResourceKey.create(
                Registries.DAMAGE_TYPE,
                new ResourceLocation("misanthrope_world", path));
    }
}
