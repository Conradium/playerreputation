package com.lei.reputation; // Your package

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot; // Import EquipmentSlot
import org.bukkit.scheduler.BukkitRunnable;

public class ReputationListener implements Listener {

    private final PlayerReputation plugin;

    public ReputationListener(PlayerReputation plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) // Monitor runs after others, ignore cancelled kills
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller(); // Can be null

        if (killer != null && killer != victim) { // PvP kill
            // Incrementing kills now also calls updatePlayerReputationDisplay internally
            plugin.incrementPlayerKills(killer);
            // Reset victim's kills to 0
            plugin.setPlayerKills(victim.getUniqueId(), 0);
            // Update victim's display to reflect the change
            plugin.signalLuckPermsUpdate(victim);
        }
        // If killed by environment/mob/etc., do nothing - maintains existing behavior
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay slightly to ensure LP data is loaded and scoreboard is ready
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.loadPlayerReputation(player); // Load kill data
                plugin.signalLuckPermsUpdate(player); // Apply LP prefix
                plugin.assignToHideTeam(player); // Add to scoreboard hide team
            }
        }.runTaskLater(plugin, 5L); // Delay 5 ticks (1/4 second) - adjust if needed
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Remove player from scoreboard team (good practice)
        plugin.removeFromHideTeam(player);

        // Optional: Clear the temporary prefix on quit?
        // If you want the prefix gone immediately when they leave, uncomment the next line.
        // If you want it to persist until they rejoin (or are modified), leave it commented.
        // plugin.clearReputationPrefix(player);
    }

    // --- Right-Click Name Reveal Logic --- (Keep as before)
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Check hand to avoid double calls from off-hand interaction
        if (event.getHand() == EquipmentSlot.HAND && event.getRightClicked() instanceof Player) {
            Player clickedPlayer = (Player) event.getRightClicked();
            Player clickingPlayer = event.getPlayer();
            showPlayerNameInActionbar(clickingPlayer, clickedPlayer);
        }
    }

    private void showPlayerNameInActionbar(Player clickingPlayer, Player clickedPlayer) {
        String formattedName = plugin.getNameFormat().replace("{PLAYER_NAME}", clickedPlayer.getName());
        clickingPlayer.sendActionBar(formattedName);

        new BukkitRunnable() {
            @Override
            public void run() {
                // Simple clear - assumes no other action bar messages interfere immediately
                clickingPlayer.sendActionBar("");
            }
        }.runTaskLater(plugin, plugin.getNameDisplayTime() * 20L);
    }
}