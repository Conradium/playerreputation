package com.lei.reputation; // Your package

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class ReputationCommands implements CommandExecutor {

    private final PlayerReputation plugin;

    public ReputationCommands(PlayerReputation plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rep")) {
            return false;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                if (!sender.hasPermission("playerreputation.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission.");
                    return true;
                }
                plugin.loadPluginConfig();
                plugin.loadPlayerData();
                // Re-apply to all online players
                sender.sendMessage(ChatColor.YELLOW + "Reloading reputation for online players...");
                int updatedCount = 0;
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    plugin.signalLuckPermsUpdate(onlinePlayer); // Update LP Prefix
                    plugin.assignToHideTeam(onlinePlayer); // Ensure they are in hide team
                    updatedCount++;
                }
                sender.sendMessage(ChatColor.GREEN + "PlayerReputation config and data reloaded. Updated " + updatedCount + " online players.");
                return true;

            case "check":
                UUID targetUUID = null;
                String targetName = "your";

                if (args.length >= 2) {
                    targetName = args[1];
                    // Try online player first
                    Player onlineTarget = Bukkit.getPlayerExact(targetName);
                    if (onlineTarget != null) {
                        targetUUID = onlineTarget.getUniqueId();
                    } else {
                        // Try offline player (less reliable for name->UUID, but works)
                        OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
                        if (offlineTarget.hasPlayedBefore() || offlineTarget.isOnline()) {
                            targetUUID = offlineTarget.getUniqueId();
                            targetName = offlineTarget.getName(); // Get potentially correct capitalization
                        } else {
                            sender.sendMessage(ChatColor.RED + "Player '" + targetName + "' not found.");
                            return true;
                        }
                    }
                } else if (sender instanceof Player) {
                    targetUUID = ((Player) sender).getUniqueId();
                    targetName = sender.getName();
                } else {
                    sender.sendMessage(ChatColor.RED + "Please specify a player from console: /rep check <player>");
                    return true;
                }

                if(targetUUID == null) {
                    sender.sendMessage(ChatColor.RED + "Could not determine player UUID for " + targetName);
                    return true;
                }

                int kills = plugin.getPlayerKills(targetUUID);
                sender.sendMessage(ChatColor.YELLOW + targetName + "'s Reputation Kills: " + ChatColor.WHITE + kills);
                return true;

            case "setkills":
                if (!sender.hasPermission("playerreputation.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission.");
                    return true;
                }
                if (args.length != 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /rep setkills <player> <amount>");
                    return true;
                }

                String setTargetName = args[1];
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                    if (amount < 0) {
                        sender.sendMessage(ChatColor.RED + "Kill count cannot be negative.");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount: " + args[2]);
                    return true;
                }

                // Find player UUID (online or offline)
                Player onlineSetTarget = Bukkit.getPlayerExact(setTargetName);
                UUID setTargetUUID;
                String finalTargetName;

                if (onlineSetTarget != null) {
                    setTargetUUID = onlineSetTarget.getUniqueId();
                    finalTargetName = onlineSetTarget.getName();
                } else {
                    OfflinePlayer offlineSetTarget = Bukkit.getOfflinePlayer(setTargetName);
                    if (offlineSetTarget.hasPlayedBefore() || offlineSetTarget.isOnline()) {
                        setTargetUUID = offlineSetTarget.getUniqueId();
                        finalTargetName = offlineSetTarget.getName() != null ? offlineSetTarget.getName() : setTargetName;
                    } else {
                        sender.sendMessage(ChatColor.RED + "Player '" + setTargetName + "' not found.");
                        return true;
                    }
                }

                if(setTargetUUID == null) {
                    sender.sendMessage(ChatColor.RED + "Could not determine player UUID for " + setTargetName);
                    return true;
                }

                plugin.setPlayerKills(setTargetUUID, amount); // This now calls updatePlayerReputationDisplay internally
                sender.sendMessage(ChatColor.GREEN + "Set " + finalTargetName + "'s kills to " + amount + ".");
                // The prefix update happens inside setPlayerKills if the player is online.
                return true;

            default:
                sendUsage(sender);
                return true;
        }
    }

    private void sendUsage(CommandSender sender){
        sender.sendMessage(ChatColor.GOLD + "--- PlayerReputation Commands ---");
        sender.sendMessage(ChatColor.YELLOW + "/rep check [player]" + ChatColor.GRAY + " - Check reputation kills.");
        if(sender.hasPermission("playerreputation.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/rep reload" + ChatColor.GRAY + " - Reload config and data.");
            sender.sendMessage(ChatColor.YELLOW + "/rep setkills <player> <amount>" + ChatColor.GRAY + " - Set a player's kills.");
        }
    }
}