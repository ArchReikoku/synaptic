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
        "Health, golden hearts, and damage are pooled. Everyone's hearts move together, and a hit on one is a hit on all."),
    DEATH(Group.SHARED, "Death",
        "One death is everyone's death. When any player dies the rest are killed with them — the hardcore rule this mod was built for."),
    RESPAWN(Group.SHARED, "Respawn",
        "One respawn is everyone's respawn. Whoever clicks Respawn first brings the whole group back at the same moment, so nobody is left sitting on the death screen waiting."),
    AIR(Group.SHARED, "Air",
        "Shows everyone the bubbles of whoever is under water, so the whole group can see the diver running out "
            + "of breath. Nobody else drowns for it — the submerged player takes the damage — but hearts are shared, "
            + "so the group pays for it either way, once per breath rather than once per swimmer.",
        false),
    HUNGER(Group.SHARED, "Hunger",
        "Food and saturation are pooled, so the group eats and starves as one."),
    INVENTORY(Group.SHARED, "Inventory",
        "One inventory for everyone, armour and offhand included. Each player still holds their own slot and can use it freely."),
    ENDER_CHEST(Group.SHARED, "Ender Chest",
        "One ender chest for everyone. A second shared pool of 27 slots, separate from the main inventory."),
    EFFECTS(Group.SHARED, "Effects",
        "A potion effect on anyone applies to everyone, at the strongest level and longest duration going."),
    EXPERIENCE(Group.SHARED, "Experience",
        "Levels and XP are pooled. Orbs picked up by one player level the whole group."),
    ADVANCEMENTS(Group.SHARED, "Advancements",
        "An advancement earned by one player is granted to everyone, including recipe unlocks."),
    PETS(Group.SHARED, "Pets",
        "Every tamed animal answers to everyone: sit it down, dye its collar, feed it or armour it as if it were yours. Shared pets never turn on a player, and one that follows will follow whoever handled it last."),

    KEEP_INVENTORY(Group.EXTRAS, "Keep Inventory",
        "Forces the keepInventory gamerule on while inventories are shared. Without it every player drops a copy of the shared inventory on death, duplicating all of it on the ground."),
    HUNGER_SPLIT(Group.EXTRAS, "Split Hunger",
        "Each player's exertion costs only 1/N exhaustion, so four people sprinting drain the shared bar at one player's rate. Off means every player drains it at full speed."),
    DAMAGE_MESSAGES(Group.EXTRAS, "Damage Chat",
        "Announce every hit in chat with the damage, what caused it, and the hearts left."),
    DAMAGE_SOUND(Group.EXTRAS, "Damage Sound",
        "Everyone hears a hurt sound when anyone is hit, however far apart you are."),
    SOLO_SLEEP(Group.EXTRAS, "Solo Sleep",
        "One player in a bed skips the night. Switching this off puts playersSleepingPercentage back to 100."),
    RUN_RESET(Group.EXTRAS, "Next Run",
        "Adds a Next Run button to the death screen. Starting a run builds a fresh world with a new seed, drops "
            + "everyone on the same block facing the same way, wipes inventories and deletes the run you came from — "
            + "all without anyone disconnecting. Host only."),
    DEATH_REPORT(Group.EXTRAS, "Death Report",
        "Replaces the death screen with the run's obituary: whose death ended it and how, and what the session has "
            + "come to for everyone. Vanilla's buttons move to a row in the corner. Needs the mod on your client."),
    SESSION_TAB(Group.EXTRAS, "Session Tab",
        "Replaces the player list with session totals — damage dealt and taken, meals, hunger spent, XP earned, "
            + "advancements unlocked and deaths caused — for everyone who has joined, kept across resets. Offline "
            + "players stay listed, dimmed. Needs the mod on your client."),
    KEEP_MINING_PROGRESS(Group.EXTRAS, "Tool Swap Mining",
        "Mining progress survives a change of held item. Vanilla restarts the break whenever the held stack changes, "
            + "which with a shared inventory means another player picking up items can reset your progress — or stop "
            + "you breaking a block at all. Leave this on. Needs the mod on your client.");

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
    private final boolean defaultOn;

    Feature(Group group, String label, String description) {
        this(group, label, description, true);
    }

    Feature(Group group, String label, String description, boolean defaultOn) {
        this.group = group;
        this.label = label;
        this.description = description;
        this.defaultOn = defaultOn;
    }

    /**
     * Whether a world that has never been told otherwise runs with this on.
     * <p>
     * Almost everything is on: the mod is the sharing, and a fresh install
     * should be the whole thing rather than a menu to go and switch on. The
     * exceptions are the ones that surprise rather than share.
     */
    public boolean defaultOn() {
        return defaultOn;
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
