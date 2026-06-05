package exp.CCnewmods.misanthrope_world.altitude.player;

import exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeBand;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Mutable per-player state stored in {@link exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeTemperatureManager}.
 * Not thread-safe — only mutated from the server tick thread.
 */
public final class PlayerAltitudeState {

    @Nullable private String activeBandId;
    private long ticksInBand = 0;
    private long lastMessageTick = Long.MIN_VALUE;
    private boolean bandChanged = false;

    // Computed each tick
    private double protectionMultiplier  = 0.0;
    private double shelterEnclosure      = 0.0;  // raw flood-fill fraction
    private double windSpeedMps          = 0.0;  // from MGE/PA
    private double shelterMultiplier     = 0.0;  // after wind reduction
    private double atmosphereThinFactor  = 1.0;  // from MGE total pressure vs. baseline
    private double oxygenPressureMbar    = 209.0; // local O2 mbar (PA/MGE)
    private double finalModifier         = 0.0;

    public void refresh(@Nullable AltitudeBand band,
                        int elapsedTicks,
                        double protectionMultiplier,
                        double shelterEnclosure,
                        double windSpeedMps,
                        double shelterMultiplier,
                        double atmosphereThinFactor,
                        double oxygenPressureMbar) {

        String newId = (band == null) ? null : band.id();
        this.bandChanged = !Objects.equals(this.activeBandId, newId);
        this.activeBandId = newId;
        this.ticksInBand = bandChanged ? elapsedTicks : this.ticksInBand + elapsedTicks;

        this.protectionMultiplier = protectionMultiplier;
        this.shelterEnclosure     = shelterEnclosure;
        this.windSpeedMps         = windSpeedMps;
        this.shelterMultiplier    = shelterMultiplier;
        this.atmosphereThinFactor = atmosphereThinFactor;
        this.oxygenPressureMbar   = oxygenPressureMbar;

        this.finalModifier = (band != null)
                ? band.effectiveModifier(protectionMultiplier, shelterMultiplier, atmosphereThinFactor)
                : 0.0;
    }

    @Nullable public String activeBandId()       { return activeBandId; }
    public boolean bandChanged()                 { return bandChanged; }
    public long ticksInBand()                    { return ticksInBand; }
    public long lastMessageTick()                { return lastMessageTick; }
    public void setLastMessageTick(long t)       { this.lastMessageTick = t; }
    public double protectionMultiplier()         { return protectionMultiplier; }
    public double shelterEnclosure()             { return shelterEnclosure; }
    public double windSpeedMps()                 { return windSpeedMps; }
    public double shelterMultiplier()            { return shelterMultiplier; }
    public double atmosphereThinFactor()         { return atmosphereThinFactor; }
    public double oxygenPressureMbar()           { return oxygenPressureMbar; }
    public double finalModifier()                { return finalModifier; }
}
