package exp.CCnewmods.misanthrope_world.temperature.storage;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

// ═══════════════════════════════════════════════════════════════════════════════
// IDynamicTemperatureProvider
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Implemented by block entities that track their own internal temperature.
 * Register instances via ThermalStorageData.registerDynamicProvider().
 * <p>
 * Examples:
 * - Crucible block entity: returns its current melt temperature
 * - Realistic Furnace block entity: returns its combustion chamber temperature
 * - Bloomery block entity: returns its operating temperature
 * - Icebox block entity: returns its cooled internal temperature
 */

public interface IDynamicTemperatureProvider {
    /**
     * Get the current internal temperature of this block entity in Celsius.
     * Return Double.NaN if the block entity is not currently active
     * (e.g. furnace is unlit) — NaN causes fallback to static internal_temperature
     * or ambient if no static value is defined.
     */
    double getInternalCelsius(BlockEntity be);
}
