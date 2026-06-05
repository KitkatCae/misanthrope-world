package exp.CCnewmods.misanthrope_world.physics.collapse;

import exp.CCnewmods.misanthrope_world.physics.collapse.network.CollapseNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * Block entity for a block undergoing lattice collapse.
 *
 * <p>When {@link exp.CCnewmods.misanthrope_world.physics.structural.FailureDispatcher}
 * triggers {@code LATTICE_COLLAPSE}, the original block is replaced with a
 * {@link LatticeCollapseBlock} and this BE is initialized with:
 * <ul>
 *   <li>The original block's {@link ResourceLocation} (for texture sampling)</li>
 *   <li>A stable random seed from the block position</li>
 *   <li>The failure direction (which way the structural load came from)</li>
 *   <li>The total animation duration in ticks</li>
 *   <li>The result block to place when the animation completes</li>
 * </ul>
 *
 * <p>Each server tick, {@link CollapseSimulator#step} advances the density field.
 * The field is sent to clients via {@link CollapseNetwork} for rendering.
 * Delta compression: only cells that changed by more than 0.02 are transmitted.
 *
 * <p>When {@link #collapseTicksRemaining} reaches 0, the BE replaces itself with
 * {@link #resultBlock} (or air if null) and spawns debris particles.
 */
public class LatticeCollapseBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/LatticeCollapse");

    // ── Configuration (set at spawn) ──────────────────────────────────────────

    /**
     * ResourceLocation of the original block (for texture lookup on client).
     */
    @Nullable
    private ResourceLocation originalBlock;

    /**
     * Stable seed for the collapse simulation (from BlockPos.asLong()).
     */
    private long seed;

    /**
     * Direction the structural load came from — drives fracture plane orientation.
     */
    private Direction failureDir = Direction.DOWN;

    /**
     * Total ticks for the collapse animation.
     */
    private int implodeDurationTicks = 40;

    /**
     * Block to place when animation completes. Null = air.
     */
    @Nullable
    private ResourceLocation resultBlock;

    /**
     * Fracture toughness of the original material [0,1].
     */
    private double fractureToughness = 0.5;

    // ── Simulation state ──────────────────────────────────────────────────────

    /**
     * Current density field — 5×5×5, [0,1] per cell.
     */
    private float[][][] field = CollapseSimulator.initialField();

    /**
     * Ticks elapsed since collapse began.
     */
    private int collapseTickElapsed = 0;

    /**
     * Server-side tick counter.
     */
    private int collapseTicksRemaining = -1; // -1 = not yet initialized

    // ── Client-side interpolation ─────────────────────────────────────────────

    /**
     * Client copy of the field (updated by network packets).
     */
    private float[][][] clientField = CollapseSimulator.initialField();

    /**
     * Previous field for lerp (smooth interpolation between server updates).
     */
    private float[][][] clientFieldPrev = CollapseSimulator.initialField();

    /**
     * Timestamp (System.nanoTime()) of last client field update.
     */
    private long clientLastUpdate = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public LatticeCollapseBlockEntity(BlockPos pos, BlockState state) {
        super(exp.CCnewmods.misanthrope_world.objects.MisWorldBlockEntityRegistry
                .LATTICE_COLLAPSE_BE.get(), pos, state);
    }

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Called by {@link exp.CCnewmods.misanthrope_world.physics.structural.FailureDispatcher}
     * immediately after the BE is placed.
     */
    public void initialize(ResourceLocation originalBlock,
                           long seed,
                           Direction failureDir,
                           int implodeDurationTicks,
                           @Nullable ResourceLocation resultBlock,
                           double fractureToughness) {
        this.originalBlock = originalBlock;
        this.seed = seed;
        this.failureDir = failureDir;
        this.implodeDurationTicks = implodeDurationTicks;
        this.resultBlock = resultBlock;
        this.fractureToughness = fractureToughness;
        this.collapseTicksRemaining = implodeDurationTicks;
        this.field = CollapseSimulator.initialField();
        this.collapseTickElapsed = 0;
    }

    // ── Server ticker ─────────────────────────────────────────────────────────

    public static <T extends BlockEntity> BlockEntityTicker<T> serverTicker() {
        return (level, pos, state, be) -> {
            if (be instanceof LatticeCollapseBlockEntity lbe) {
                lbe.serverTick((ServerLevel) level);
            }
        };
    }

    private void serverTick(ServerLevel level) {
        if (collapseTicksRemaining < 0) return; // not initialized

        // Advance simulation
        float[][][] prev = field;
        field = CollapseSimulator.step(
                field, collapseTickElapsed, implodeDurationTicks,
                seed, failureDir, fractureToughness);
        collapseTickElapsed++;
        collapseTicksRemaining--;

        // Send delta to clients every 4 ticks (cheap: ~3–8 cells typically changed)
        if (collapseTickElapsed % 4 == 0 || collapseTicksRemaining == 0) {
            sendFieldDelta(level, prev, field);
        }

        // Emit debris particles at cells that just crossed the 0.2 threshold
        emitDebrisParticles(level, prev, field);

        // Completion
        if (collapseTicksRemaining <= 0) {
            finalizeCollapse(level);
        }

        setChanged();
    }

    private void sendFieldDelta(ServerLevel level, float[][][] prev, float[][][] next) {
        // Collect changed cells
        byte[] indices = new byte[125];
        float[] values = new float[125];
        int count = 0;
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    float diff = Math.abs(next[x][y][z] - prev[x][y][z]);
                    if (diff > 0.02f || collapseTicksRemaining == 0) {
                        indices[count] = (byte) (x * 25 + y * 5 + z);
                        values[count] = next[x][y][z];
                        count++;
                    }
                }
            }
        }
        if (count == 0) return;
        CollapseNetwork.sendDelta(level, worldPosition, indices, values, count);
    }

    private void emitDebrisParticles(ServerLevel level, float[][][] prev, float[][][] next) {
        float blockX = worldPosition.getX() + 0.5f;
        float blockY = worldPosition.getY() + 0.5f;
        float blockZ = worldPosition.getZ() + 0.5f;
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    if (prev[x][y][z] > 0.25f && next[x][y][z] < 0.1f) {
                        float px = blockX + (x - 2) * 0.2f;
                        float py = blockY + (y - 2) * 0.2f;
                        float pz = blockZ + (z - 2) * 0.2f;
                        level.sendParticles(
                                net.minecraft.core.particles.ParticleTypes.SMOKE,
                                px, py, pz, 1,
                                0.05, 0.1, 0.05, 0.02);
                    }
                }
            }
        }
    }

    private void finalizeCollapse(ServerLevel level) {
        BlockState replacement;
        if (resultBlock != null) {
            Block block = BuiltInRegistries.BLOCK.get(resultBlock);
            replacement = block.defaultBlockState();
        } else {
            replacement = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        level.setBlock(worldPosition, replacement, 3);
        // Notify structural stress system that this block changed
        exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField
                .markDirty(level, worldPosition);
    }

    // ── Client field update (called by network handler) ───────────────────────

    /**
     * Updates the client-side density field from a delta packet.
     * Only called on the logical client.
     */
    public void applyFieldDelta(byte[] indices, float[] values, int count) {
        // Preserve previous field for interpolation
        copyField(clientField, clientFieldPrev);
        for (int i = 0; i < count; i++) {
            int idx = indices[i] & 0xFF;
            int x = idx / 25, y = (idx / 5) % 5, z = idx % 5;
            clientField[x][y][z] = values[i];
        }
        clientLastUpdate = System.nanoTime();
    }

    /**
     * Returns a field interpolated between the previous and current client fields.
     * {@code t} is the lerp factor [0,1] based on time since last packet.
     */
    public float[][][] getInterpolatedField(float t) {
        float[][][] result = new float[5][5][5];
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                for (int z = 0; z < 5; z++)
                    result[x][y][z] = clientFieldPrev[x][y][z]
                            + t * (clientField[x][y][z] - clientFieldPrev[x][y][z]);
        return result;
    }

    private static void copyField(float[][][] src, float[][][] dst) {
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                System.arraycopy(src[x][y], 0, dst[x][y], 0, 5);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Nullable
    public ResourceLocation getOriginalBlock() {
        return originalBlock;
    }

    public long getClientLastUpdate() {
        return clientLastUpdate;
    }

    public float[][][] getClientField() {
        return clientField;
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (originalBlock != null) tag.putString("OriginalBlock", originalBlock.toString());
        if (resultBlock != null) tag.putString("ResultBlock", resultBlock.toString());
        tag.putLong("Seed", seed);
        tag.putInt("FailureDir", failureDir.ordinal());
        tag.putInt("ImplodeTicks", implodeDurationTicks);
        tag.putDouble("FractureToughness", fractureToughness);
        tag.putInt("TickElapsed", collapseTickElapsed);
        tag.putInt("TicksRemaining", collapseTicksRemaining);
        // Save field as flat float array
        float[] flat = new float[125];
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                for (int z = 0; z < 5; z++)
                    flat[x * 25 + y * 5 + z] = field[x][y][z];
        ListTag list = new ListTag();
        for (float v : flat) {
            CompoundTag ft = new CompoundTag();
            ft.putFloat("v", v);
            list.add(ft);
        }
        tag.put("Field", list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("OriginalBlock")) originalBlock = new ResourceLocation(tag.getString("OriginalBlock"));
        if (tag.contains("ResultBlock")) resultBlock = new ResourceLocation(tag.getString("ResultBlock"));
        seed = tag.getLong("Seed");
        failureDir = Direction.values()[tag.getInt("FailureDir")];
        implodeDurationTicks = tag.getInt("ImplodeTicks");
        fractureToughness = tag.getDouble("FractureToughness");
        collapseTickElapsed = tag.getInt("TickElapsed");
        collapseTicksRemaining = tag.getInt("TicksRemaining");
        if (tag.contains("Field", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Field", Tag.TAG_COMPOUND);
            field = new float[5][5][5];
            for (int i = 0; i < Math.min(125, list.size()); i++) {
                int x = i / 25, y = (i / 5) % 5, z = i % 5;
                field[x][y][z] = list.getCompound(i).getFloat("v");
            }
        }
    }
}
