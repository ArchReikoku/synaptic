package com.sharedlife.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the mod does that can be switched off individually.
 * <p>
 * The ordinal is the bit position used on the wire, so client and server must
 * always run the same build. The config file is keyed by name instead, which is
 * why reordering or dropping a constant does not corrupt saved settings.
 */
public enum Feature {
    HEALTH(Group.SHARED, "Life",
        "Health, golden hearts, and damage are pooled. Everyone's hearts move together, and a hit on one is a hit on all."),
    HUNGER(Group.SHARED, "Hunger",
        "Food and saturation are pooled, so the group eats and starves as one."),
    INVENTORY(Group.SHARED, "Inventory",
        "One inventory for everyone, armour and offhand included. Each player still holds their own slot and can use it freely."),
    EFFECTS(Group.SHARED, "Effects",
        "A potion effect on anyone applies to everyone, at the strongest level and longest duration going."),
    EXPERIENCE(Group.SHARED, "Experience",
        "Levels and XP are pooled. Orbs picked up by one player level the whole group."),

    HUNGER_SPLIT(Group.EXTRAS, "Split Hunger",
        "Each player's exertion costs only 1/N exhaustion, so four people sprinting drain the shared bar at one player's rate. Off means every player drains it at full speed."),
    DAMAGE_MESSAGES(Group.EXTRAS, "Damage Chat",
        "Announce every hit in chat with the damage, what caused it, and the hearts left."),
    DAMAGE_SOUND(Group.EXTRAS, "Damage Sound",
        "Everyone hears a hurt sound when anyone is hit, however far apart you are."),
    SOLO_SLEEP(Group.EXTRAS, "Solo Sleep",
        "One player in a bed skips the night. Switching this off puts playersSleepingPercentage back to 100."),
    KEEP_MINING_PROGRESS(Group.EXTRAS, "Tool Swap Mining",
        "Swapping tools mid-break keeps your mining progress instead of restarting it. Needs the mod on your client.");

    /** How the settings screen files these. */
    public enum Group {
        SHARED("Shared"),
        EXTRAS("Extras");

        private final String title;

        Group(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    private final Group group;
    private final String label;
    private final String description;

    Feature(Group group, String label, String description) {
        this.group = group;
        this.label = label;
        this.description = description;
    }

    public Group group() {
        return group;
    }

    /** Short name on the button. */
    public String label() {
        return label;
    }

    /** The longer explanation shown on hover. */
    public String description() {
        return description;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public static List<Feature> of(Group group) {
        List<Feature> features = new ArrayList<>();
        for (Feature feature : values()) {
            if (feature.group == group) features.add(feature);
        }
        return features;
    }
}
