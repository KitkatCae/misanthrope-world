package exp.CCnewmods.misanthrope_world.registry;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Ported from MVSE's MVSParticles. Currently just the reentry plasma trail
 * particle; more will land here as vaporize/sonic-boom move over too.
 */
public final class MisWorldParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, Misanthrope_world.MODID);

    /**
     * Plasma trail particle — long directional streak, white-to-orange-to-red
     * fade. Rendered by {@code physics.reentry.client.PlasmaParticle}.
     */
    public static final RegistryObject<SimpleParticleType> PLASMA_TRAIL =
            PARTICLES.register("plasma_trail", () -> new SimpleParticleType(false));

    private MisWorldParticles() {
    }
}
