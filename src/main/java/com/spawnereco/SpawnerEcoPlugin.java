package com.spawnereco;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerEcoPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private Economy vaultEconomy;
    private Plugin econoShopGui;
    private FileConfiguration config;
    private Map<UUID, Long> lastSpawnTime = new ConcurrentHashMap<>();
    private Map<UUID, Integer> spawnCount = new ConcurrentHashMap<>();
    private static final String GUI_TITLE = "§6§lSpawner Shop";
    private static final int GUI_SIZE = 54;

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

        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("spawn").setExecutor(this);
        getCommand("spawn").setTabCompleter(this);
        getCommand("spawner-eco").setExecutor(this);
        getCommand("spawner-eco").setTabCompleter(this);

        getLogger().info("SpawnerEco has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("SpawnerEco has been disabled!");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        
        event.setCancelled(true);
        
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
        
        if (!clickedItem.hasItemMeta() || !clickedItem.getItemMeta().hasDisplayName()) return;
        
        String displayName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
        
        if (displayName.contains("Spawner")) {
            String entityName = displayName.replace(" Spawner", "").toLowerCase().replace(" ", "_");
            handleSpawnerPurchase(player, entityName);
            player.closeInventory();
        }
    }

    private void handleSpawnerPurchase(Player player, String entityName) {
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(entityName.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(colorize("&cInvalid entity type!"));
            return;
        }

        double cost = getEntityCost(entityType);
        
        if (!hasFunds(player, cost)) {
            player.sendMessage(colorize(config.getString("messages.no-funds", "&cInsufficient funds! Cost: $%cost%")
                    .replace("%cost%", String.format("%.2f", cost))));
            return;
        }

        withdrawFunds(player, cost);
        
        ItemStack spawnerItem = createSpawnerItem(entityType);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(spawnerItem);
        
        if (!leftover.isEmpty()) {
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            player.sendMessage(colorize("&eSpawner dropped on ground - inventory full!"));
        } else {
            player.sendMessage(colorize("&aPurchased " + entityType.name() + " spawner for $" + String.format("%.2f", cost) + "!"));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    private void openSpawnerGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);
        
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(" ");
            glass.setItemMeta(glassMeta);
        }
        for (int i = 0; i < GUI_SIZE; i++) {
            gui.setItem(i, glass);
        }
        
        List<String> allowedEntities = config.getStringList("allowed-entities", 
            Arrays.asList("ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "ENDERMAN"));
        
        int slot = 0;
        for (String entityName : allowedEntities) {
            if (slot >= 45) break;
            
            EntityType entityType;
            try {
                entityType = EntityType.valueOf(entityName.toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            
            ItemStack spawnerItem = createSpawnerItem(entityType);
            double cost = getEntityCost(entityType);
            
            ItemMeta meta = spawnerItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + formatEntityName(entityType) + " Spawner");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.WHITE + "Cost: " + ChatColor.GREEN + "$" + String.format("%.2f", cost));
                lore.add(ChatColor.YELLOW + "Click to purchase!");
                lore.add("");
                lore.add(ChatColor.GRAY + "Limits: " + config.getInt("limits.max-active", 50));
                meta.setLore(lore);
                spawnerItem.setItemMeta(meta);
            }
            
            gui.setItem(slot, spawnerItem);
            slot++;
        }
        
        player.openInventory(gui);
    }

    private ItemStack createSpawnerItem(EntityType entityType) {
        ItemStack spawner = new ItemStack(Material.SPAWNER);
        ItemMeta meta = spawner.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + formatEntityName(entityType) + " Spawner");
            spawner.setItemMeta(meta);
        }
        return spawner;
    }

    private String formatEntityName(EntityType entityType) {
        String name = entityType.name().replace("_", " ");
        StringBuilder result = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1).toLowerCase())
                      .append(" ");
            }
        }
        return result.toString().trim();
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
            if (!(sender instanceof Player)) {
                sender.sendMessage("&cOnly players can use this command!");
                return true;
            }
            
            Player player = (Player) sender;
            
            if (!player.hasPermission("spawnereco.use")) {
                player.sendMessage(colorize(config.getString("messages.no-permission", "&cNo permission!")));
                return true;
            }
            
            // Open GUI when no args provided
            openSpawnerGUI(player);
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

            player.getLocation().getWorld().spawnEntity(player.getLocation(), entityType);
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

    private void reloadConfig(Player player) {
        reloadConfig();
        config = getConfig();
        player.sendMessage(colorize("&aConfiguration reloaded!"));
    }
}
