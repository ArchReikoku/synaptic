package com.sharedlife.config;

/**
 * Everything the mod does that can be switched off individually. The ordinal is
 * the bit position used on the wire and in the config file, so ADD new features
 * at the end only — inserting one in the middle would silently reinterpret every
 * saved setting.
 */
public enum Feature {
    HEALTH("Shared Health"),
    ABSORPTION("Shared Golden Hearts"),
    HUNGER("Shared Hunger"),
    HUNGER_SPLIT("Split Hunger Between Players"),
    EFFECTS("Shared Status Effects"),
    EXPERIENCE("Shared Experience"),
    INVENTORY("Shared Inventory"),
    DAMAGE_MESSAGES("Damage Messages In Chat"),
    DAMAGE_SOUND("Everyone Hears Damage"),
    SOLO_SLEEP("One Player Skips The Night"),
    KEEP_MINING_PROGRESS("Keep Mining Progress On Tool Swap");

    private final String label;

    Feature(String label) {
        this.label = label;
    }

    /** Human-readable name shown on the settings screen. */
    public String label() {
        return label;
    }

    public int bit() {
        return 1 << ordinal();
    }
}
