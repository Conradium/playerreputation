package com.lei.reputation; // Your package

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
// Note: We no longer need Node, NodeType, MetaNode, PrefixNode, DataMutateResult for prefix management here
//       They would be used *within LuckPerms* configuration based on the context.

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bstats.bukkit.Metrics;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
// Note: Predicate is no longer needed for filtering nodes
import java.util.logging.Level;

public class PlayerReputation extends JavaPlugin {

    private ScoreboardManager manager;
    private Scoreboard board;
    private Map<UUID, Integer> playerKills;
    private Map<UUID, Long> lastKillTimestamps = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    // --- LuckPerms ---
    private LuckPerms luckPerms;
    // The context key we will provide dynamically
    public static final String REPUTATION_CONTEXT_KEY = "reputation-level"; // Changed key name for clarity

    // --- Configurable values --- (Keep as before)
    private int orangeThreshold;
    private int redThreshold;
    // Prefixes are no longer directly applied by the plugin via nodes.
    // They should be configured in LuckPerms based on the context.
    // Keep them here for potential reference or other uses, but they aren't used for LP prefixes anymore.
    // private String greenPrefix;
    // private String orangePrefix;
    // private String redPrefix;
    private int nameDisplayTime;
    private String nameFormat;

    // --- Team for HIDING names ---
    private static final String HIDE_TEAM_NAME = "rep_hidden_name";
    private Team hideTeam;


    @Override
    public void onEnable() {
        // --- LuckPerms Setup ---
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            this.luckPerms = provider.getProvider();
        }

        if (this.luckPerms == null) {
            getLogger().severe("LuckPerms API not found! This plugin requires LuckPerms for reputation prefixes.");
            getLogger().severe("Disabling PlayerReputation.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // --- Register Context Calculator ---
        getLogger().info("Registering LuckPerms Context Calculator...");
        luckPerms.getContextManager().registerCalculator(new ReputationContextCalculator(this));
        getLogger().info("Successfully hooked into LuckPerms API and registered Context Calculator.");
        // --- End LuckPerms Setup ---


        saveDefaultConfig();
        loadPluginConfig(); // Load thresholds, display time, etc.

        playerKills = new HashMap<>();
        setupDataFile();
        loadPlayerData();
        loadLastKillTimestamps(); // Load timestamps on enable

        manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            getLogger().severe("Could not get Scoreboard Manager! Name hiding disabled.");
        } else {
            board = manager.getMainScoreboard();
            setupNameHidingTeam(); // Setup the single team for hiding names
        }


        // Register events and commands
        getServer().getPluginManager().registerEvents(new ReputationListener(this), this);
        this.getCommand("rep").setExecutor(new ReputationCommands(this));

        // Apply name hiding to already online players and trigger initial context calculation
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerReputation(player); // Ensure data is loaded
            assignToHideTeam(player); // Assign to scoreboard team
            signalLuckPermsUpdate(player); // Ask LuckPerms to recalculate contexts for the player
        }

        // bStats Metrics
        try {
            new Metrics(this, 22889); // Your bStats ID
        } catch (Exception e) {
            getLogger().warning("Failed to initialize bStats metrics: " + e.getMessage());
        }

        getLogger().info("PlayerReputation enabled!");

        // Removed the hourly rank downgrade task - this logic is now implicit in the context calculator.
        // If a player hasn't killed anyone recently, their kill count won't increase,
        // and their context will reflect their current kill count.
        // If you need explicit decay, that would require a different mechanism (e.g., reducing kills over time).
    }

    @Override
    public void onDisable() {
        savePlayerData();
        saveLastKillTimestamps(); // Save timestamps on disable

        // No need to clear prefixes explicitly, as they are context-based.
        // Unregistering the context calculator happens automatically when LP unloads/reloads.

        getLogger().info("PlayerReputation disabled!");
    }

    private void loadLastKillTimestamps() {
        lastKillTimestamps.clear();
        if (!dataFile.exists() || !dataConfig.isConfigurationSection("lastKillTimestamps")) {
            return;
        }
        for (String uuidString : dataConfig.getConfigurationSection("lastKillTimestamps").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                long timestamp = dataConfig.getLong("lastKillTimestamps." + uuidString);
                lastKillTimestamps.put(uuid, timestamp);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Skipping invalid UUID in lastKillTimestamps: " + uuidString);
            }
        }
        getLogger().info("Loaded last kill timestamps for " + lastKillTimestamps.size() + " players.");
    }

    private void saveLastKillTimestamps() {
        // Clear existing timestamp data first
        dataConfig.set("lastKillTimestamps", null);
        // Save current data
        for (Map.Entry<UUID, Long> entry : lastKillTimestamps.entrySet()) {
            dataConfig.set("lastKillTimestamps." + entry.getKey().toString(), entry.getValue());
        }
        // Save the entire config file (which now includes kills and timestamps)
        saveDataConfig(); // Use a unified save method
        getLogger().info("Saved last kill timestamps for " + lastKillTimestamps.size() + " players.");
    }


    // --- Configuration Loading ---
    public void loadPluginConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        orangeThreshold = config.getInt("reputation.thresholds.orange", 1);
        redThreshold = config.getInt("reputation.thresholds.red", 5);
        nameDisplayTime = config.getInt("reveal-name.display-time", 3);
        nameFormat = ChatColor.translateAlternateColorCodes('&', config.getString("reveal-name.format", "&6{PLAYER_NAME}"));

        // Prefixes are now configured in LuckPerms, remove loading them here
        // greenPrefix = ChatColor.translateAlternateColorCodes('&', config.getString("reputation.prefixes.green", "&a» "));
        // orangePrefix = ChatColor.translateAlternateColorCodes('&', config.getString("reputation.prefixes.orange", "&6» "));
        // redPrefix = ChatColor.translateAlternateColorCodes('&', config.getString("reputation.prefixes.red", "&c» "));

        // Threshold validation...
        if (orangeThreshold <= 0) {
            getLogger().warning("Orange threshold must be positive. Setting to 1.");
            orangeThreshold = 1;
        }
        if (redThreshold <= orangeThreshold) {
            getLogger().warning("Red threshold must be greater than Orange threshold. Adjusting red threshold.");
            redThreshold = orangeThreshold + 1;
        }
    }

    // --- Player Data Handling ---
    private void setupDataFile() {
        dataFile = new File(getDataFolder(), "playerdata.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not create playerdata.yml", e);
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void loadPlayerData() {
        playerKills.clear();
        if (!dataFile.exists()) return;
        dataConfig = YamlConfiguration.loadConfiguration(dataFile); // Ensure it's fresh

        if (dataConfig.isConfigurationSection("kills")) {
            for (String uuidString : dataConfig.getConfigurationSection("kills").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    int kills = dataConfig.getInt("kills." + uuidString);
                    playerKills.put(uuid, kills);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Skipping invalid UUID in playerdata.yml (kills): " + uuidString);
                }
            }
        } else {
            // Compatibility check for old format (root keys)
            for (String uuidString : dataConfig.getKeys(false)) {
                if (!uuidString.equals("lastKillTimestamps")) { // Skip the timestamp section
                    try {
                        UUID uuid = UUID.fromString(uuidString);
                        int kills = dataConfig.getInt(uuidString);
                        playerKills.put(uuid, kills);
                        // Migrate to new structure (optional but good practice)
                        dataConfig.set("kills." + uuidString, kills);
                        dataConfig.set(uuidString, null); // Remove old entry
                        getLogger().info("Migrated kill data for " + uuidString + " to new format.");
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Skipping potentially invalid legacy UUID in playerdata.yml: " + uuidString);
                    }
                }
            }
            if (!playerKills.isEmpty()) {
                saveDataConfig(); // Save migrated data
            }
        }


        getLogger().info("Loaded kill data for " + playerKills.size() + " players.");
    }

    public void savePlayerData() {
        // Save current kill data under the "kills" section
        dataConfig.set("kills", null); // Clear old kill data
        for (Map.Entry<UUID, Integer> entry : playerKills.entrySet()) {
            dataConfig.set("kills." + entry.getKey().toString(), entry.getValue());
        }
        // Timestamps are saved separately by saveLastKillTimestamps, which calls saveDataConfig
        // saveDataConfig(); // Save the config file - handled by onDisable calling saveLastKillTimestamps
        getLogger().info("Prepared kill data for " + playerKills.size() + " players to be saved.");
    }

    // Unified save method
    private void saveDataConfig() {
        if (dataFile == null || dataConfig == null) {
            getLogger().severe("Attempted to save data config, but it wasn't initialized!");
            return;
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save playerdata.yml", e);
        }
    }


    // --- Kill Management ---
    public int getPlayerKills(UUID uuid) {
        return playerKills.getOrDefault(uuid, 0);
    }

    public int getPlayerKills(Player player) {
        return getPlayerKills(player.getUniqueId());
    }

    public void incrementPlayerKills(Player player) {
        UUID uuid = player.getUniqueId();
        int newKills = playerKills.getOrDefault(uuid, 0) + 1;
        playerKills.put(uuid, newKills);
        lastKillTimestamps.put(uuid, System.currentTimeMillis()); // Update last kill time
        savePlayerData(); // Prepare data for saving
        saveLastKillTimestamps(); // Save timestamps (which also saves the rest of the data)
        signalLuckPermsUpdate(player); // Tell LuckPerms the context might have changed
    }

    public void setPlayerKills(UUID uuid, int kills) {
        if (kills < 0) kills = 0;
        playerKills.put(uuid, kills);

        // Update context immediately if player is online
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            signalLuckPermsUpdate(player);
        }
        savePlayerData(); // Prepare data for saving
        saveDataConfig(); // Save immediately after manual command change
    }

    public void loadPlayerReputation(Player player) {
        // This mainly ensures the player is in the map if they weren't loaded initially
        // The actual data loading happens in loadPlayerData()
        if (!playerKills.containsKey(player.getUniqueId())) {
            // If dataFile exists, try loading their specific data again just in case.
            // Otherwise default to 0. Should ideally be loaded already by loadPlayerData.
            if (dataFile.exists()) {
                dataConfig = YamlConfiguration.loadConfiguration(dataFile); // Reload fresh
                playerKills.put(player.getUniqueId(), dataConfig.getInt("kills." + player.getUniqueId().toString(), 0));
            } else {
                playerKills.put(player.getUniqueId(), 0);
            }
        }
    }

    // --- Scoreboard Team for HIDING NAMES --- (Keep as before)
    private void setupNameHidingTeam() {
        if (board == null) {
            getLogger().warning("Scoreboard not available, cannot set up name hiding team.");
            return;
        }
        hideTeam = board.getTeam(HIDE_TEAM_NAME);
        if (hideTeam == null) {
            hideTeam = board.registerNewTeam(HIDE_TEAM_NAME);
            getLogger().info("Registered name hiding team: " + HIDE_TEAM_NAME);
        } else {
            getLogger().info("Found existing name hiding team: " + HIDE_TEAM_NAME);
            // Ensure options are correct even if team existed
        }
        hideTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        hideTeam.setAllowFriendlyFire(true); // Or false based on your needs
        hideTeam.setCanSeeFriendlyInvisibles(false);
        // No color or prefix needed here
    }

    public void assignToHideTeam(Player player) {
        if (hideTeam == null) return; // Scoreboard might not be available

        // Ensure player isn't in another team that might interfere (optional, but safer)
        // Be careful this doesn't override other team plugins if they MANAGE teams
        // Team currentTeam = board.getEntryTeam(player.getName());
        // if (currentTeam != null && !currentTeam.getName().equals(HIDE_TEAM_NAME)) {
        //    getLogger().fine("Removing " + player.getName() + " from team " + currentTeam.getName() + " to add to hide team.");
        //    currentTeam.removeEntry(player.getName());
        //}

        // Add to hide team if not already there
        if (!hideTeam.hasEntry(player.getName())) {
            try {
                hideTeam.addEntry(player.getName());
                getLogger().fine("Added " + player.getName() + " to hide team.");
            } catch (IllegalStateException | IllegalArgumentException e) { // Catch more specific exceptions
                getLogger().log(Level.SEVERE, "Failed to add " + player.getName() + " to hide team.", e);
            }
        }
    }

    public void removeFromHideTeam(Player player) {
        if (hideTeam != null && hideTeam.hasEntry(player.getName())) {
            try {
                hideTeam.removeEntry(player.getName());
                getLogger().fine("Removed " + player.getName() + " from hide team.");
            } catch (IllegalStateException | IllegalArgumentException e) {
                getLogger().log(Level.SEVERE, "Failed to remove " + player.getName() + " from hide team.", e);
            }
        }
    }

    // --- LuckPerms Context Update ---
    /**
     * Signals LuckPerms to re-evaluate the contexts for a specific player.
     * This should be called whenever a factor affecting the context (like kills) changes.
     * @param player The player whose contexts need updating.
     */
    public void signalLuckPermsUpdate(Player player) {
        if (luckPerms != null && player != null && player.isOnline()) {
            // Load the user to ensure they are loaded, then invalidate their contexts.
            // This prompts LuckPerms to recalculate using the registered calculators.
            ContextManager contextManager = luckPerms.getContextManager();
            contextManager.signalContextUpdate(player);
            getLogger().fine("Signaled LuckPerms context update for " + player.getName());

            // Optional: Force refresh permission data (usually not needed just for context change)
            // luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAccept(user -> {
            //    if (user != null) {
            //        luckPerms.getUserManager().signalPermissionDataUpdate(user);
            //    }
            // });
        }
    }

    // --- REMOVED ---
    // updatePlayerReputationDisplay(Player) - Replaced by context calculator + signalLuckPermsUpdate
    // applyRank(Player, String) - No longer needed
    // clearReputationPrefix(Player) - No longer needed
    // downgradePlayerRank(Player, int) - Logic moved to context calculator

    // --- Getters ---
    public int getNameDisplayTime() { return nameDisplayTime; }
    public String getNameFormat() { return nameFormat; }
    public LuckPerms getLuckPerms() { return luckPerms; }
    public int getOrangeThreshold() { return orangeThreshold; } // Expose thresholds for calculator
    public int getRedThreshold() { return redThreshold; }     // Expose thresholds for calculator


    // --- LuckPerms Context Calculator Implementation ---
    public static class ReputationContextCalculator implements ContextCalculator<Player> {
        private final PlayerReputation plugin;

        // Keep context key consistent
        private static final String CTX_KEY = PlayerReputation.REPUTATION_CONTEXT_KEY;

        public ReputationContextCalculator(PlayerReputation plugin) {
            this.plugin = plugin;
        }

        @Override
        public void calculate(Player player, ContextConsumer contextConsumer) {
            // Get the player's kill count from the plugin's data store
            int kills = plugin.getPlayerKills(player.getUniqueId());

            // Determine the reputation level based on kills and thresholds
            String reputationLevel;
            if (kills >= plugin.getRedThreshold()) {
                reputationLevel = "red";
            } else if (kills >= plugin.getOrangeThreshold()) {
                reputationLevel = "orange";
            } else {
                reputationLevel = "green";
            }

            // Add the calculated context key-value pair
            contextConsumer.accept(CTX_KEY, reputationLevel);
        }

        // OPTIONAL: Estimate potential values (helps LuckPerms optimize)
        @Override
        public ContextSet estimatePotentialContexts() {
            // We know the possible values for our context key
            return ImmutableContextSet.builder()
                    .add(CTX_KEY, "green")
                    .add(CTX_KEY, "orange")
                    .add(CTX_KEY, "red")
                    .build();
        }
    }
}