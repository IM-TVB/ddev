# Spawner Eco Plugin

A plugin for spawning entities with economy integration. Supports both **Vault** and **EconoShopGUI** for economy transactions.

## Features

- Spawn entities with configurable costs
- Economy balance checking before spawn
- **Compatible with Vault and EconoShopGUI**
- Permission-based access control
- Configurable spawn limits
- Cooldown between spawns
- Per-entity cost configuration

## Requirements

- **Spigot/Paper 1.20+**
- **Vault** (optional, for economy provider)
- **EconoShopGUI** (optional, alternative economy provider)

*Note: The plugin requires at least one economy provider (Vault or EconoShopGUI) to function.*

## Installation

1. Place the plugin jar in your server's `plugins` folder
2. Ensure you have **Vault** or **EconoShopGUI** installed
3. Restart the server
4. Configure settings in `plugins/spawner-eco/config.yml`

## Commands

- `/spawn <entity>` - Spawn an entity (requires permission and funds)
- `/spawner-eco reload` - Reload configuration (admin only)

## Permissions

- `spawnereco.use` - Allows using the spawn command (default: true)
- `spawnereco.admin` - Allows admin commands (default: op)

## Configuration

Edit `config.yml` to customize:
- Entity spawn costs
- Spawn limits per player
- Cooldowns between spawns
- Economy settings (enable/disable, default cost)
- Custom messages

### Example Configuration

```yaml
economy:
  enabled: true
  default-cost: 10.0

entity-costs:
  ZOMBIE: 5.0
  SKELETON: 5.0
  CREEPER: 8.0
  VILLAGER: 50.0

limits:
  per-minute: 10
  max-active: 50

cooldown: 2
```

## Economy Integration

This plugin automatically detects and uses:
1. **EconoShopGUI** - If present, it will use its economy provider
2. **Vault** - Falls back to Vault economy if EconoShopGUI is not available

The plugin will disable itself if neither economy provider is found.
