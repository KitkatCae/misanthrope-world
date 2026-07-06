package exp.CCnewmods.misanthrope_world.crackrender.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-side crack state for a single block position.
 * <p>
 * ── Crack levels ──────────────────────────────────────────────────────────────
 * 0  PRISTINE   — no damage (entry never stored)
 * 1  HAIRLINE   — thin visible crack, no gameplay effect
 * 2  CRACKED    — visible crack, reduced structural integrity
 * 3  SEVERE     — wide crack, through-and-through gap at render, debris risk
 * 4  COLLAPSED  — block removed; entry deleted after collapse is processed
 * <p>
 * ── Option B ownership ────────────────────────────────────────────────────────
 * Each block owns its own crack level independently. A vein can be at level 3
 * midspan while its origin block is still at level 1 (stress concentration).
 * The veinSegments list describes which world-space crack paths pass through
 * this block — purely for geometric routing, not ownership of level.
 * <p>
 * ── Healing ───────────────────────────────────────────────────────────────────
 * healTicksAccumulated advances each server tick when no CrackSourceProvider
 * is actively driving this block. When it reaches cause.healRate, level drops
 * by one. Resets to 0 on any new damage event. Blocks with cause.healRate == 0
 * never heal.
 */
public class CrackEntry {

    public static final int LEVEL_PRISTINE = 0;
    public static final int LEVEL_HAIRLINE = 1;
    public static final int LEVEL_CRACKED = 2;
    public static final int LEVEL_SEVERE = 3;
    public static final int LEVEL_COLLAPSED = 4;

    private final BlockPos pos;
    private final CrackCause cause;
    private int level;
    private int healTicksAccumulated;
    private long lastDrivenTick;

    /**
     * All vein segments that pass through this block.
     */
    private final List<VeinSegment> segments = new ArrayList<>();

    public CrackEntry(BlockPos pos, CrackCause cause, int initialLevel) {
        this.pos = pos.immutable();
        this.cause = cause;
        this.level = initialLevel;
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Advance crack level by one step.
     *
     * @return new level after advance
     */
    public int advance(long gameTick) {
        if (level < LEVEL_COLLAPSED) level++;
        healTicksAccumulated = 0;
        lastDrivenTick = gameTick;
        return level;
    }

    /**
     * Drive this block — resets heal accumulation.
     * Called each tick by CrackPropagator when a source is active.
     */
    public void drive(long gameTick) {
        healTicksAccumulated = 0;
        lastDrivenTick = gameTick;
    }

    /**
     * Jump directly to a given crack level, bypassing the normal one-step-
     * per-advance() ramp. For violent, instantaneous causes — a fracture
     * from an impact-driven crater cut shouldn't need to visually "heal up"
     * through hairline/cracked over several propagation cycles before it
     * looks like what it is. Clamped to [{@link #LEVEL_PRISTINE},
     * {@link #LEVEL_COLLAPSED}]. Resets heal accumulation like advance() does.
     *
     * @param level    target level, clamped into range
     * @param gameTick current server tick, recorded as lastDrivenTick
     */
    public void setLevelInstant(int level, long gameTick) {
        this.level = Math.max(LEVEL_PRISTINE, Math.min(LEVEL_COLLAPSED, level));
        this.healTicksAccumulated = 0;
        this.lastDrivenTick = gameTick;
    }

    /**
     * Attempt one heal tick. Returns true if level decreased.
     */
    public boolean tickHeal() {
        if (cause.healRate == 0) return false;
        if (level <= LEVEL_PRISTINE) return false;
        healTicksAccumulated++;
        if (healTicksAccumulated >= cause.healRate) {
            level--;
            healTicksAccumulated = 0;
            return true;
        }
        return false;
    }

    public void addSegment(VeinSegment segment) {
        segments.add(segment);
    }

    public void clearSegments() {
        segments.clear();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public BlockPos pos() {
        return pos;
    }

    public CrackCause cause() {
        return cause;
    }

    public int level() {
        return level;
    }

    public long lastDrivenTick() {
        return lastDrivenTick;
    }

    public List<VeinSegment> segments() {
        return Collections.unmodifiableList(segments);
    }

    public boolean isCollapsed() {
        return level >= LEVEL_COLLAPSED;
    }

    public boolean isSevere() {
        return level >= LEVEL_SEVERE;
    }

    public boolean hasCracks() {
        return level > LEVEL_PRISTINE;
    }

    /**
     * Whether any vein segment in this block crosses the given face.
     * Used by the face suppression mixin.
     */
    public boolean hasCrackOnFace(Direction face) {
        for (VeinSegment seg : segments) {
            if (seg.crossesFace(face)) return true;
        }
        return false;
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Cause", cause.ordinal());
        tag.putInt("Level", level);
        tag.putInt("HealTicks", healTicksAccumulated);
        tag.putLong("LastDriven", lastDrivenTick);
        if (!segments.isEmpty()) {
            tag.put("Segments", VeinSegment.saveList(segments));
        }
        return tag;
    }

    public static CrackEntry load(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));
        CrackCause cause = CrackCause.values()[tag.getInt("Cause")];
        CrackEntry entry = new CrackEntry(pos, cause, tag.getInt("Level"));
        entry.healTicksAccumulated = tag.getInt("HealTicks");
        entry.lastDrivenTick = tag.getLong("LastDriven");
        if (tag.contains("Segments", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Segments", Tag.TAG_COMPOUND);
            for (VeinSegment seg : VeinSegment.loadList(list)) {
                entry.addSegment(seg);
            }
        }
        return entry;
    }
}
