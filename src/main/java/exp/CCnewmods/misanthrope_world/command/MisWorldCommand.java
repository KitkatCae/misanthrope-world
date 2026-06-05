package exp.CCnewmods.misanthrope_world.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.momosoftworks.coldsweat.api.util.Temperature;
import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.altitude.compat.MgeAtmosphereReader;
import exp.CCnewmods.misanthrope_world.altitude.player.PlayerAltitudeState;
import exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeBand;
import exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeTemperatureManager;
import exp.CCnewmods.misanthrope_world.config.AltitudeConfig;
import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackStateMap;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Unified {@code /misworld} command tree.
 *
 * <pre>
 * /misworld
 *   status                    — overall mod status (loaded systems, config state)
 *
 *   altitude
 *     status                  — per-player altitude debug (band, modifier, shelter, MGE)
 *     reload                  — hot-reload altitude band file
 *     list                    — list all loaded bands by priority
 *
 *   structural
 *     check                   — scan nearby blocks for cracks and stress, print summary
 *     reload                  — force re-queue all loaded chunks for structural re-evaluation
 * </pre>
 *
 * All sub-commands require permission level 2 (op).
 */
@Mod.EventBusSubscriber(modid = Misanthrope_world.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MisWorldCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("misworld")
                .requires(src -> src.hasPermission(2))

                // /misworld status
                .executes(ctx -> executeStatus(ctx.getSource()))
                .then(Commands.literal("status")
                        .executes(ctx -> executeStatus(ctx.getSource())))

                // /misworld altitude ...
                .then(Commands.literal("altitude")
                        .then(Commands.literal("status")
                                .executes(ctx -> executeAltitudeStatus(ctx.getSource())))
                        .then(Commands.literal("reload")
                                .executes(ctx -> executeAltitudeReload(ctx.getSource())))
                        .then(Commands.literal("list")
                                .executes(ctx -> executeAltitudeList(ctx.getSource()))))

                // /misworld structural ...
                .then(Commands.literal("structural")
                        .then(Commands.literal("check")
                                .executes(ctx -> executeStructuralCheck(ctx.getSource())))
                        .then(Commands.literal("reload")
                                .executes(ctx -> executeStructuralReload(ctx.getSource()))))
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // /misworld  /misworld status
    // ═════════════════════════════════════════════════════════════════════════

    private static int executeStatus(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder();
        sb.append("§6[Misanthrope World] §rSystem status:\n");

        // Config toggles
        append(sb, "crackSystem",         MisWorldConfig.isCrackSystemEnabled());
        append(sb, "collapseSystem",       MisWorldConfig.isCollapseSystemEnabled());
        append(sb, "wetSand",              MisWorldConfig.isWetSandEnabled());
        append(sb, "altitudeTemperature",  MisWorldConfig.isAltitudeTemperatureEnabled());

        // Soft-dep presence
        sb.append("  §7Integrations:§r\n");
        sb.append("    MGE:              ").append(yesNo(MgeAtmosphereReader.getInstance().isMgeLoaded())).append("\n");
        sb.append("    ProjectAtmo:      ").append(yesNo(ModList.get().isLoaded("projectatmosphere"))).append("\n");
        sb.append("    ColdSweat:        ").append(yesNo(ModList.get().isLoaded("cold_sweat"))).append("\n");
        sb.append("    Thermodynamica:   ").append(yesNo(ModList.get().isLoaded("thermodynamica"))).append("\n");
        sb.append("    Minecollapse:     ").append(yesNo(ModList.get().isLoaded("minecollapse"))).append("\n");

        // Altitude
        if (MisWorldConfig.isAltitudeTemperatureEnabled()) {
            sb.append("  §7Altitude bands:§r ").append(AltitudeConfig.getInstance().getBands().size())
              .append(" loaded from §e").append(AltitudeConfig.getInstance().currentFileName()).append("§r\n");
            sb.append("    windSensitivity: ").append(fmt(MisWorldConfig.altitudeWindSensitivity())).append("\n");
            sb.append("    thinMaxFactor:   ").append(fmt(MisWorldConfig.altitudeAtmosphereThinMaxFactor())).append("\n");
        }

        // Structural
        sb.append("  §7Structural:§r\n");
        sb.append("    bgBlocksPerTick:  ").append(MisWorldConfig.structuralBackgroundBlocksPerTick()).append("\n");
        sb.append("    crackInterval:    ").append(MisWorldConfig.crackPropagationIntervalTicks()).append(" ticks\n");
        sb.append("    materialProps:    loaded (no public count API)\n");

        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static void append(StringBuilder sb, String name, boolean enabled) {
        sb.append("  §7").append(String.format("%-22s", name)).append("§r ")
          .append(enabled ? "§a✔ enabled§r" : "§c✘ disabled§r").append("\n");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // /misworld altitude status
    // ═════════════════════════════════════════════════════════════════════════

    private static int executeAltitudeStatus(CommandSourceStack source) throws CommandSyntaxException {
        if (!MisWorldConfig.isAltitudeTemperatureEnabled()) {
            source.sendFailure(Component.literal("[MisWorld] Altitude temperature system is disabled in config."));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        AltitudeTemperatureManager manager = AltitudeTemperatureManager.getInstance();

        PlayerAltitudeState state = manager.refreshState(player);
        Optional<AltitudeBand> bandOpt = manager.findMatchingBand(player);

        String bandId  = bandOpt.map(AltitudeBand::id).orElse("none");
        String maxYStr = bandOpt.flatMap(b -> Optional.ofNullable(b.maxY()))
                .map(Object::toString).orElse("open");
        String modMode = bandOpt.map(b -> b.modifierMode().name()).orElse("n/a");

        double worldTemp = Double.NaN;
        try { worldTemp = Temperature.get(player, Temperature.Trait.WORLD); } catch (Exception ignored) {}

        MgeAtmosphereReader mge = MgeAtmosphereReader.getInstance();
        BlockPos pos = player.blockPosition();
        float totalMbar    = mge.getTotalPressureMbar(player.level(), pos);
        float baselineMbar = mge.getDimensionBaselinePressureMbar(player.level(), pos);
        float o2Mbar       = mge.getOxygenMbar(player.level(), pos);

        String text = "[MisWorld Altitude] Status for " + player.getName().getString() + ":\n" +
                "  Y=" + player.getBlockY() + "  Dim=" + player.level().dimension().location() + "\n" +
                "  Active band: " + bandId + "  Y-range: "
                        + bandOpt.map(b -> b.minY() + "–" + maxYStr).orElse("n/a") + "\n" +
                "  Raw modifier: " + fmt(bandOpt.map(AltitudeBand::temperatureModifier).orElse(0.0))
                        + " (" + modMode + ")\n" +
                "  Final modifier: " + fmt(state.finalModifier()) + "\n" +
                "  Protection mult: " + fmt(state.protectionMultiplier()) + "\n" +
                "  Shelter enclosure: " + fmt(state.shelterEnclosure())
                        + "  Wind: " + fmt(state.windSpeedMps()) + " m/s\n" +
                "  Shelter mult (wind-adj): " + fmt(state.shelterMultiplier()) + "\n" +
                "  Atmo thin factor: " + fmt(state.atmosphereThinFactor())
                        + "  (max=" + fmt(MisWorldConfig.altitudeAtmosphereThinMaxFactor()) + ")\n" +
                "  MGE pressure: " + fmt(totalMbar) + " mbar  baseline: " + fmt(baselineMbar) + " mbar\n" +
                "  MGE O2: " + fmt(o2Mbar) + " mbar  (std=" + MgeAtmosphereReader.STANDARD_O2_MBAR + ")\n" +
                "  ColdSweat WORLD temp: " + fmt(worldTemp) + "°C\n" +
                "  Ticks in band: " + state.ticksInBand();

        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // /misworld altitude reload
    // ═════════════════════════════════════════════════════════════════════════

    private static int executeAltitudeReload(CommandSourceStack source) {
        if (!MisWorldConfig.isAltitudeTemperatureEnabled()) {
            source.sendFailure(Component.literal("[MisWorld] Altitude temperature system is disabled in config."));
            return 0;
        }
        AltitudeConfig.getInstance().reload();
        int count = AltitudeConfig.getInstance().getBands().size();
        String file = AltitudeConfig.getInstance().currentFileName();
        source.sendSuccess(() -> Component.literal(
                "[MisWorld Altitude] Reloaded " + count + " band(s) from " + file + "."
        ), true);
        return count;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // /misworld altitude list
    // ═════════════════════════════════════════════════════════════════════════

    private static int executeAltitudeList(CommandSourceStack source) {
        if (!MisWorldConfig.isAltitudeTemperatureEnabled()) {
            source.sendFailure(Component.literal("[MisWorld] Altitude temperature system is disabled in config."));
            return 0;
        }
        List<AltitudeBand> bands = AltitudeConfig.getInstance().getBands();
        if (bands.isEmpty()) {
            source.sendFailure(Component.literal("[MisWorld Altitude] No bands loaded."));
            return 0;
        }
        StringBuilder sb = new StringBuilder("[MisWorld Altitude] Bands (priority desc):\n");
        for (AltitudeBand b : bands) {
            String maxY = (b.maxY() == null) ? "open" : String.valueOf(b.maxY());
            sb.append(String.format("  [%3d] %-22s Y:%d–%s  %s (%s)\n",
                    b.priority(), b.id(), b.minY(), maxY,
                    fmt(b.temperatureModifier()), b.modifierMode().name()));
        }
        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return bands.size();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // /misworld structural check
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Scans blocks within 24 blocks of the player for active crack entries.
     * Reports the top 10 most stressed blocks — highest crack level first.
     */
    private static int executeStructuralCheck(CommandSourceStack source) throws CommandSyntaxException {
        if (!MisWorldConfig.isCrackSystemEnabled() && !MisWorldConfig.isCollapseSystemEnabled()) {
            source.sendFailure(Component.literal("[MisWorld] Both crackSystem and collapseSystem are disabled in config."));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int radius = 24;

        // Pull all tracked crack entries for this dimension
        CrackStateMap stateMap = CrackStateMap.get(level);
        Collection<CrackEntry> allEntries = stateMap.allEntries();

        // Filter to within radius, sort by crack level desc
        List<CrackEntry> nearby = allEntries.stream()
                .filter(e -> {
                    BlockPos p = e.pos();
                    return Math.abs(p.getX() - origin.getX()) <= radius
                            && Math.abs(p.getY() - origin.getY()) <= radius
                            && Math.abs(p.getZ() - origin.getZ()) <= radius;
                })
                .sorted(Comparator.comparingInt(CrackEntry::level).reversed())
                .limit(10)
                .toList();

        StringBuilder sb = new StringBuilder(
                "[MisWorld Structural] Nearby crack scan (r=" + radius + ", showing top 10):\n");
        sb.append("  Total tracked in dimension: ").append(allEntries.size()).append("\n");
        sb.append("  Within radius: ").append(
                allEntries.stream().filter(e -> {
                    BlockPos p = e.pos();
                    return Math.abs(p.getX() - origin.getX()) <= radius
                            && Math.abs(p.getY() - origin.getY()) <= radius
                            && Math.abs(p.getZ() - origin.getZ()) <= radius;
                }).count()).append("\n");

        if (nearby.isEmpty()) {
            sb.append("  No cracked blocks nearby.\n");
        } else {
            for (CrackEntry e : nearby) {
                BlockPos p = e.pos();
                int dx = p.getX() - origin.getX();
                int dy = p.getY() - origin.getY();
                int dz = p.getZ() - origin.getZ();
                sb.append(String.format("  Level %d  [%d,%d,%d]  Δ(%+d,%+d,%+d)  cause=%s\n",
                        e.level(), p.getX(), p.getY(), p.getZ(), dx, dy, dz,
                        e.cause().name()));
            }
        }

        // Also note how many providers are registered
        sb.append("  Active crack sources: ").append(CrackPropagator.sourceCount()).append("\n");

        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return nearby.size();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // /misworld structural reload
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Re-queues all blocks in all loaded chunks of the current dimension for
     * structural stress re-evaluation. Useful after config changes to structural
     * parameters or material property reloads.
     */
    private static int executeStructuralReload(CommandSourceStack source) throws CommandSyntaxException {
        if (!MisWorldConfig.isCrackSystemEnabled() && !MisWorldConfig.isCollapseSystemEnabled()) {
            source.sendFailure(Component.literal("[MisWorld] Both structural systems are disabled in config."));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();

        // Mark all loaded chunk positions dirty — StructuralStressField will
        // re-evaluate them over the next several ticks via background scan.
        int chunksMarked = 0;
        for (net.minecraft.world.level.ChunkPos cp : StructuralStressField.getLoadedChunks(level)) {
            // Mark the centre block of each chunk dirty to trigger re-evaluation
            BlockPos centre = new BlockPos(cp.getMiddleBlockX(), level.getSeaLevel(), cp.getMiddleBlockZ());
            StructuralStressField.markDirty(level, centre);
            chunksMarked++;
        }

        int finalChunks = chunksMarked;
        source.sendSuccess(() -> Component.literal(
                "[MisWorld Structural] Queued " + finalChunks + " chunk(s) for re-evaluation."
        ), true);
        return chunksMarked;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private static String fmt(double d) {
        return Double.isNaN(d) ? "NaN" : String.format(Locale.ROOT, "%.4f", d);
    }

    private static String yesNo(boolean b) {
        return b ? "§ayes§r" : "§cno§r";
    }
}
