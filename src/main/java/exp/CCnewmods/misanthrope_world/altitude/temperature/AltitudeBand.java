package exp.CCnewmods.misanthrope_world.altitude.temperature;

import exp.CCnewmods.misanthrope_world.config.AltitudeBandConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A resolved altitude band ready for runtime use.
 */
public final class AltitudeBand {

    private final String id;
    private final int priority;
    private final int minY;
    @Nullable private final Integer maxY;
    private final Set<ResourceLocation> dimensionIds;
    private final AltitudeBandConfig.DimensionMode dimensionMode;
    private final double temperatureModifier;
    private final AltitudeBandConfig.ModifierMode modifierMode;
    private final String onEnterMessage;
    private final String actionbarMessage;
    private final int messageCooldownTicks;
    @Nullable private final TagKey<Item> protectionTag;
    private final int requiredPieces;
    private final double protectionReductionPerPiece;
    private final int fullProtectionPieces;
    private final boolean enableShelterCheck;
    private final int shelterCheckRadius;
    private final double shelterReduction;

    private AltitudeBand(String id, int priority, int minY, @Nullable Integer maxY,
                         Set<ResourceLocation> dimensionIds, AltitudeBandConfig.DimensionMode dimensionMode,
                         double temperatureModifier, AltitudeBandConfig.ModifierMode modifierMode,
                         String onEnterMessage, String actionbarMessage, int messageCooldownTicks,
                         @Nullable TagKey<Item> protectionTag, int requiredPieces,
                         double protectionReductionPerPiece, int fullProtectionPieces,
                         boolean enableShelterCheck, int shelterCheckRadius, double shelterReduction) {
        this.id = id; this.priority = priority; this.minY = minY; this.maxY = maxY;
        this.dimensionIds = dimensionIds; this.dimensionMode = dimensionMode;
        this.temperatureModifier = temperatureModifier; this.modifierMode = modifierMode;
        this.onEnterMessage = onEnterMessage; this.actionbarMessage = actionbarMessage;
        this.messageCooldownTicks = messageCooldownTicks;
        this.protectionTag = protectionTag; this.requiredPieces = requiredPieces;
        this.protectionReductionPerPiece = protectionReductionPerPiece;
        this.fullProtectionPieces = fullProtectionPieces;
        this.enableShelterCheck = enableShelterCheck;
        this.shelterCheckRadius = shelterCheckRadius; this.shelterReduction = shelterReduction;
    }

    public static AltitudeBand fromConfig(AltitudeBandConfig cfg, Consumer<String> warningSink) {
        Set<ResourceLocation> dims = new HashSet<>();
        for (String s : cfg.dimensions()) {
            ResourceLocation rl = ResourceLocation.tryParse(s);
            if (rl == null) warningSink.accept("Skipping invalid dimension id '" + s + "' in band '" + cfg.id() + "'.");
            else dims.add(rl);
        }

        TagKey<Item> protTag = null;
        if (!cfg.protectionTag().isBlank()) {
            ResourceLocation tagRl = ResourceLocation.tryParse(cfg.protectionTag());
            if (tagRl == null) warningSink.accept("Invalid protection tag '" + cfg.protectionTag() + "' in band '" + cfg.id() + "'. Protection disabled.");
            else protTag = TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagRl);
        }

        return new AltitudeBand(cfg.id(), cfg.priority(), cfg.minY(), cfg.maxY(),
                Set.copyOf(dims), cfg.dimensionMode(),
                cfg.temperatureModifier(), cfg.modifierMode(),
                cfg.onEnterMessage(), cfg.actionbarMessage(), cfg.messageCooldownTicks(),
                protTag, cfg.requiredPieces(), cfg.protectionReductionPerPiece(), cfg.fullProtectionPieces(),
                cfg.enableShelterCheck(), cfg.shelterCheckRadius(), cfg.shelterReduction());
    }

    /** Hot-path: does this band apply to the given dimension and Y? */
    public boolean matches(ResourceLocation dimensionId, int y) {
        if (y < minY) return false;
        if (maxY != null && y > maxY) return false;
        boolean listed = dimensionIds.contains(dimensionId);
        return switch (dimensionMode) {
            case WHITELIST -> listed;
            case BLACKLIST -> !listed;
        };
    }

    /**
     * Computes the final temperature modifier after protection, shelter, and atmosphere thinning.
     *
     * @param protectionMultiplier  0–1: fraction of modifier cancelled by armor protection tag
     * @param shelterMultiplier     0–1: fraction of modifier cancelled by enclosure (wind-adjusted)
     * @param atmosphereThinFactor  ≥1.0: amplifier from thin MGE atmosphere (1.0 = sea-level normal)
     */
    public double effectiveModifier(double protectionMultiplier, double shelterMultiplier,
                                    double atmosphereThinFactor) {
        double exposure = Math.max(0.0, 1.0 - protectionMultiplier - shelterMultiplier);
        return temperatureModifier * exposure * atmosphereThinFactor;
    }

    public String id()                                         { return id; }
    public int priority()                                      { return priority; }
    public int minY()                                          { return minY; }
    @Nullable public Integer maxY()                            { return maxY; }
    public double temperatureModifier()                        { return temperatureModifier; }
    public AltitudeBandConfig.ModifierMode modifierMode()      { return modifierMode; }
    public String onEnterMessage()                             { return onEnterMessage; }
    public String actionbarMessage()                           { return actionbarMessage; }
    public int messageCooldownTicks()                          { return messageCooldownTicks; }
    @Nullable public TagKey<Item> protectionTag()              { return protectionTag; }
    public int requiredPieces()                                { return requiredPieces; }
    public double protectionReductionPerPiece()                { return protectionReductionPerPiece; }
    public int fullProtectionPieces()                          { return fullProtectionPieces; }
    public boolean enableShelterCheck()                        { return enableShelterCheck; }
    public int shelterCheckRadius()                            { return shelterCheckRadius; }
    public double shelterReduction()                           { return shelterReduction; }
}
