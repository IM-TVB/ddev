package com.spawnereco;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerEcoPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private Economy vaultEconomy;
    private Plugin econoShopGui;
    private FileConfiguration config;
    private Map<UUID, Long> lastSpawnTime = new ConcurrentHashMap<>();
    private Map<UUID, Integer> spawnCount = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        // Check for Vault economy
        boolean vaultFound = setupVaultEconomy();
        
        // Check for EconoShopGUI
        econoShopGui = Bukkit.getPluginManager().getPlugin("EconoShopGUI");
        
        if (!vaultFound && econoShopGui == null) {
            getLogger().severe("Neither Vault economy nor EconoShopGUI found! Disabling plugin...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (econoShopGui != null) {
            getLogger().info("EconoShopGUI found! Using it for economy transactions.");
        } else if (vaultFound) {
            getLogger().info("Vault economy found! Using it for economy transactions.");
        }

        getCommand("spawn").setExecutor(this);
        getCommand("spawn").setTabCompleter(this);
        getCommand("spawner-eco").setExecutor(this);

        getLogger().info("SpawnerEco has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("SpawnerEco has been disabled!");
    }

    private boolean setupVaultEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        vaultEconomy = rsp.getProvider();
        return vaultEconomy != null;
    }

    /**
     * Check if player has sufficient funds using Vault or EconoShopGUI
     */
    private boolean hasFunds(Player player, double amount) {
        // Try EconoShopGUI first if available
        if (econoShopGui != null && econoShopGui.isEnabled()) {
            try {
                // EconoShopGUI typically uses an Economy provider via Vault as well
                // If it registers its own economy, we can use it
                RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
                if (rsp != null && rsp.getProvider() != null) {
                    return rsp.getProvider().has(player, amount);
                }
            } catch (Exception e) {
                getLogger().warning("Error checking funds with EconoShopGUI: " + e.getMessage());
            }
        }
        
        // Fall back to Vault economy
        if (vaultEconomy != null) {
            return vaultEconomy.has(player, amount);
        }
        
        // If no economy is available, allow spawning (shouldn't happen due to onEnable check)
        return true;
    }

    /**
     * Withdraw money from player using Vault or EconoShopGUI
     */
    private void withdrawFunds(Player player, double amount) {
        // Try EconoShopGUI first if available
        if (econoShopGui != null && econoShopGui.isEnabled()) {
            try {
                RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
                if (rsp != null && rsp.getProvider() != null) {
                    rsp.getProvider().withdrawPlayer(player, amount);
                    return;
                }
            } catch (Exception e) {
                getLogger().warning("Error withdrawing funds with EconoShopGUI: " + e.getMessage());
            }
        }
        
        // Fall back to Vault economy
        if (vaultEconomy != null) {
            vaultEconomy.withdrawPlayer(player, amount);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("spawner-eco")) {
            if (!sender.hasPermission("spawnereco.admin")) {
                sender.sendMessage(colorize(config.getString("messages.no-permission", "&cNo permission!")));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                config = getConfig();
                sender.sendMessage(colorize(config.getString("messages.reload", "&aConfiguration reloaded!")));
                return true;
            }
            sender.sendMessage("&cUsage: /spawner-eco reload");
            return true;
        }

        if (command.getName().equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("&cOnly players can use this command!");
                return true;
            }

            Player player = (Player) sender;
            if (!player.hasPermission("spawnereco.use")) {
                player.sendMessage(colorize(config.getString("messages.no-permission", "&cNo permission!")));
                return true;
            }

            if (args.length < 1) {
                player.sendMessage("&cUsage: /spawn <entity>");
                return true;
            }

            EntityType entityType;
            try {
                entityType = EntityType.valueOf(args[0].toUpperCase());
            } catch (IllegalArgumentException e) {
                player.sendMessage(colorize(config.getString("messages.invalid-entity", "&cInvalid entity type!")));
                return true;
            }

            if (!checkCooldown(player)) {
                return true;
            }

            if (!checkLimit(player)) {
                player.sendMessage(colorize(config.getString("messages.limit-reached", "&cSpawn limit reached!")));
                return true;
            }

            double cost = getEntityCost(entityType);
            if (config.getBoolean("economy.enabled", true) && !hasFunds(player, cost)) {
                player.sendMessage(colorize(config.getString("messages.no-funds", "&cInsufficient funds!")
                        .replace("%cost%", String.format("%.2f", cost))));
                return true;
            }

            if (config.getBoolean("economy.enabled", true)) {
                withdrawFunds(player, cost);
            }

            player.getLocation().spawnEntity(entityType);
            lastSpawnTime.put(player.getUniqueId(), System.currentTimeMillis());
            spawnCount.merge(player.getUniqueId(), 1, Integer::sum);

            player.sendMessage(colorize(config.getString("messages.spawned", "&aSuccessfully spawned %entity%!")
                    .replace("%entity%", entityType.name())));
            return true;
        }

        return false;
    }

    private boolean checkCooldown(Player player) {
        long cooldownSeconds = config.getLong("cooldown", 2);
        if (cooldownSeconds <= 0) return true;

        UUID uuid = player.getUniqueId();
        if (lastSpawnTime.containsKey(uuid)) {
            long elapsed = (System.currentTimeMillis() - lastSpawnTime.get(uuid)) / 1000;
            if (elapsed < cooldownSeconds) {
                player.sendMessage(colorize(config.getString("messages.cooldown", "&cPlease wait!")
                        .replace("%seconds%", String.valueOf(cooldownSeconds - elapsed))));
                return false;
            }
        }
        return true;
    }

    private boolean checkLimit(Player player) {
        int perMinute = config.getInt("limits.per-minute", 10);
        int maxActive = config.getInt("limits.max-active", 50);

        UUID uuid = player.getUniqueId();
        long oneMinuteAgo = System.currentTimeMillis() - 60000;

        if (lastSpawnTime.containsKey(uuid) && lastSpawnTime.get(uuid) > oneMinuteAgo) {
            int count = spawnCount.getOrDefault(uuid, 0);
            if (count >= perMinute) {
                return false;
            }
        } else {
            spawnCount.put(uuid, 0);
        }

        return spawnCount.getOrDefault(uuid, 0) < maxActive;
    }

    private double getEntityCost(EntityType type) {
        String path = "entity-costs." + type.name();
        if (config.contains(path)) {
            return config.getDouble(path);
        }
        return config.getDouble("economy.default-cost", 10.0);
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("spawn") && args.length == 1) {
            List<String> entities = new ArrayList<>();
            for (EntityType type : EntityType.values()) {
                if (type.isSpawnable() && type.isAlive()) {
                    entities.add(type.name().toLowerCase());
                }
            }
            return entities.stream()
                    .filter(e -> e.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (command.getName().equalsIgnoreCase("spawner-eco") && args.length == 1 && sender.hasPermission("spawnereco.admin")) {
            return Arrays.asList("reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
