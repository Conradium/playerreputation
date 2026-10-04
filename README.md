# PlayerReputation

A Paper plugin that gives every player a PvP **reputation** based on their kill count and exposes it to LuckPerms, so you can show it as a prefix. It also hides everyone's nametag, so players have to right-click someone to find out who they are.

Good for survival, anarchy and roleplay servers where you want killers to stand out without giving away who everyone is.

## How it works

1. **Kills build reputation.** Killing another player adds one kill to your count, and dying to a player resets your count to 0. Deaths to mobs or the environment don't change anything.
2. **Reputation becomes a LuckPerms context.** The plugin registers a context called `reputation-level` with one of three values:

   | Level | Default rule |
   |-------|--------------|
   | `green` | 0 kills |
   | `orange` | 1 to 4 kills |
   | `red` | 5 or more kills |

3. **You choose the prefix in LuckPerms.** Give each level its own prefix (see [LuckPerms setup](#luckperms-setup)). The prefix shows wherever your server displays LuckPerms prefixes (chat, tab list, and so on).
4. **Names are hidden.** All players join a scoreboard team (`rep_hidden_name`) that never shows nametags.
5. **Right-click to reveal.** Right-clicking a player shows their name in your action bar for a few seconds.

Kill counts are saved to `plugins/PlayerReputation/playerdata.yml`, so they survive restarts.

## Requirements

- Paper 1.21.x
- Java 21
- [LuckPerms](https://luckperms.net/) (**required**: the plugin disables itself without it)

## Installation

1. Install LuckPerms.
2. Drop the PlayerReputation jar into `plugins/`.
3. Restart the server, then set up your prefixes in LuckPerms.

## LuckPerms setup

Add a prefix for each reputation level, scoped to the context:

```
/lp group default meta addprefix 100 "&a» " reputation-level=green
/lp group default meta addprefix 100 "&6» " reputation-level=orange
/lp group default meta addprefix 100 "&c» " reputation-level=red
```

Prefixes update right away when a player's kill count changes.

## Commands

All `/rep` commands require `playerreputation.admin` (op by default).

| Command | Description |
|---------|-------------|
| `/rep check [player]` | Show a player's reputation kills (yourself if no name is given) |
| `/rep setkills <player> <amount>` | Set a player's kill count (works for offline players) |
| `/rep reload` | Reload the config and player data, then refresh online players |

## Configuration

```yaml
reputation:
  thresholds:
    orange: 1   # minimum kills for orange
    red: 5      # minimum kills for red

reveal-name:
  display-time: 3              # seconds the revealed name stays in the action bar
  format: "&6{PLAYER_NAME}"    # how the revealed name is shown
```

The `reputation.prefixes` section in the default config is only a reference. The actual prefixes come from LuckPerms.

## Building

```bash
./gradlew build
```

The shaded jar (with bStats relocated) is written to `build/libs/`.
