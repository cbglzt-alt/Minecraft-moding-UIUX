package dev.lyc.frameledger.measure;

/**
 * The named spans we attribute frame time to.
 *
 * <p>Order matters only for display; measurement is driven by the query ring, not by this enum.</p>
 */
public enum Pass {
    /** Sodium/vanilla terrain: solid, cutout, translucent chunk layers. */
    TERRAIN("Terrain"),
    /** Entity rendering, including block entities drawn per frame. */
    ENTITIES("Entities"),
    /** Block entities drawn through the per-frame dispatcher. */
    BLOCK_ENTITIES("Block Entities"),
    /** Particle rendering. */
    PARTICLES("Particles"),
    /** Clouds. */
    CLOUDS("Clouds"),
    /** Sky, sun, moon, stars (excludes clouds). */
    SKY("Sky"),
    /** Everything else inside the world pass we did not claim. */
    OTHER("Other");

    private final String label;

    Pass(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }
}
