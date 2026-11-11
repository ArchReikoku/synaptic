package com.synaptic.config;

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
        "Everyone shares one health bar. A hit on one is a hit on all."),
    DEATH(Group.SHARED, "Death",
        "When one player dies, everyone dies."),
    RESPAWN(Group.SHARED, "Respawn",
        "Whoever respawns first brings the whole group back. Stays on while Death is shared."),
    AIR(Group.SHARED, "Air",
        "Everyone sees the bubbles of whoever is under water. Only the diver takes the drowning damage.",
        false),
    HUNGER(Group.SHARED, "Hunger",
        "Everyone shares one hunger bar."),
    INVENTORY(Group.SHARED, "Inventory",
        "Everyone shares one inventory, armour and offhand included."),
    ENDER_CHEST(Group.SHARED, "Ender Chest",
        "Everyone shares one ender chest."),
    EFFECTS(Group.SHARED, "Effects",
        "A potion effect on one player applies to everyone, at the strongest level going."),
    EXPERIENCE(Group.SHARED, "Experience",
        "Everyone shares one XP pool. Orbs picked up by one player level the whole group."),
    ADVANCEMENTS(Group.SHARED, "Advancements",
        "An advancement earned by one player is granted to everyone."),
    PETS(Group.SHARED, "Pets",
        "Tamed animals answer to everyone, and never turn on a player."),
    MOB_ANGER(Group.SHARED, "Mob Anger",
        "Provoke a neutral mob and it turns on the whole group, not just you. Only players it can already see.",
        false),

    HUNGER_SPLIT(Group.EXTRAS, "Split Hunger",
        "Running and jumping drain the shared bar at one player's rate instead of everyone's."),
    DAMAGE_MESSAGES(Group.EXTRAS, "Damage Chat",
        "Announce every hit in chat, with what caused it and the hearts left."),
    DAMAGE_SOUND(Group.EXTRAS, "Damage Sound",
        "Everyone hears it when anyone is hurt, however far apart you are."),
    SOLO_SLEEP(Group.EXTRAS, "Solo Sleep",
        "One player in a bed skips the night."),
    RUN_RESET(Group.EXTRAS, "Next Run",
        "Adds a Next Run button to the death screen and this screen's top right. Starts a fresh world on a "
            + "new seed without anyone disconnecting. Host only."),
    DEATH_REPORT(Group.EXTRAS, "Death Report",
        "Replaces the death screen with the run's report: who died, how, and how everyone did. "
            + "Needs the mod on your client."),
    SESSION_TAB(Group.EXTRAS, "Session Tab",
        "Replaces the player list with everyone's totals for the session. Needs the mod on your client."),
    KEEP_MINING_PROGRESS(Group.EXTRAS, "Tool Swap Mining",
        "Mining carries on when your held item changes. Leave this on — with a shared inventory, another "
            + "player picking something up can otherwise reset your progress. Needs the mod on your client.");

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
    private final boolean enabledByDefault;

    Feature(Group group, String label, String description) {
        this(group, label, description, true);
    }

    Feature(Group group, String label, String description, boolean enabledByDefault) {
        this.group = group;
        this.label = label;
        this.description = description;
        this.enabledByDefault = enabledByDefault;
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

    /**
     * Whether a fresh config switches this on. Everything is on unless it has a
     * reason not to be — an untested feature is the reason here.
     */
    public boolean enabledByDefault() {
        return enabledByDefault;
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
