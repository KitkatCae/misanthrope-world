package exp.CCnewmods.misanthrope_world.physics.structural;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import exp.CCnewmods.misanthrope_world.physics.structural.vs2.TemporaryShipLifecycle;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared "turn this block group into a VS2 ship and give it one push" utility.
 * <p>
 * Extracted from {@code FailureDispatcher.spawnFragmentShip} once
 * {@code ImpactHandler}'s crater rework needed the exact same
 * assemble-then-impulse sequence for a second, unrelated reason (fragments
 * flying outward from an impact vs. fragments falling/shearing off under
 * structural failure). Same VS2 calls either way — only the impulse
 * direction/magnitude differs, so that's the one thing callers supply.
 * <p>
 * Both callers already had this exact sequence bytecode-verified against
 * this project's VS2 jar (see {@code FailureDispatcher}'s original comments
 * and {@code ImpactHandler.applyImpactImpulse}'s notes on
 * {@code PhysShip.applyInvariantForce} being the only force-application
 * method that actually exists on this API) — nothing new is being assumed
 * here, just consolidated.
 */
public final class ShipFragmentLauncher {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/ShipFragmentLauncher");

    private ShipFragmentLauncher() {
    }

    /**
     * Assembles {@code component} into a VS2 ship and applies {@code impulse}
     * to it on the first physics tick after assembly. Registers the result
     * with {@link TemporaryShipLifecycle} so it eventually settles back into
     * placed blocks like every other fragment ship in this project.
     *
     * @return true if assembly succeeded and the ship was launched; false if
     *         VS2 assembly failed for any reason (caller should crumble the
     *         component instead — matches the fallback both original callers
     *         already had)
     */
    public static boolean assembleAndLaunch(ServerLevel level, Set<BlockPos> component, Vector3d impulse) {
        if (component.isEmpty()) return false;

        try {
            var positions = component.stream()
                    .map(p -> new BlockPos(p.getX(), p.getY(), p.getZ()))
                    .collect(Collectors.toSet());

            var ship = org.valkyrienskies.mod.common.assembly.ShipAssembler.INSTANCE
                    .assembleToShipFull(level, positions, 1.0);
            if (ship == null) return false;

            String dimId = level.dimension().location().toString();
            BlockPos anchor = component.iterator().next().immutable();

            var loadedShip = VSGameUtilsKt.getLoadedShipManagingPos(level, anchor);
            if (!(loadedShip instanceof LoadedServerShip lss)) return false;

            var listener = new OneTickImpulseListener(new Vector3d(impulse), dimId, anchor);
            ValkyrienSkiesMod.INSTANCE.addBlockEntityPhysTicker(dimId, anchor, listener);

            TemporaryShipLifecycle.register(level, lss.getId());
            return true;
        } catch (Exception e) {
            LOGGER.error("[ShipFragmentLauncher] VS2 fragment assembly failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * One-shot physics-tick listener that applies a single force impulse
     * then removes itself. Moved here from {@code FailureDispatcher} (was
     * {@code FailureDispatcher.OneTickImpulseListener}) and from
     * {@code ImpactHandler} (was {@code ImpactImpulseListener}, though that
     * one also handled bounce/absorption math for ship-vs-terrain impulse —
     * this simpler version is specifically for "one push on a freshly
     * assembled fragment," not general impulse absorption).
     */
    public static final class OneTickImpulseListener
            implements org.valkyrienskies.mod.api.BlockEntityPhysicsListener {

        private final Vector3d impulse;
        private final String dimId;
        private final BlockPos anchor;
        private boolean fired = false;
        private String dimension;

        public OneTickImpulseListener(Vector3d impulse, String dimId, BlockPos anchor) {
            this.impulse = impulse;
            this.dimId = dimId;
            this.anchor = anchor;
            this.dimension = dimId;
        }

        @Override
        public String getDimension() {
            return dimension;
        }

        @Override
        public void setDimension(String value) {
            this.dimension = value;
        }

        @Override
        public void physTick(PhysShip physShip, PhysLevel physLevel) {
            if (fired) return;
            fired = true;
            physShip.applyInvariantForce(impulse);
            try {
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                        .execute(() -> ValkyrienSkiesMod.INSTANCE
                                .removeBlockEntityPhysTicker(anchor, dimId));
            } catch (Exception ignored) {
                // Server unavailable in edge cases — listener fires once then no-ops
            }
        }
    }
}
