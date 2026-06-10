This page contains the configuration structure for HuskSync.

## Configuration structure
📁 `plugins/HuskSync/`
- 📄 `config.yml`: General plugin configuration
- 📄 `server.yml`: Server ID configuration
- 📄 `messages-xx-xx.yml`: Plugin locales, formatted in MineDown (see [[Translations]])

## Example files
<details>
<summary>config.yml</summary>

```yaml
# ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
# ┃        HuskSync Config       ┃
# ┃    Developed by William278   ┃
# ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
# ┣╸ Information: https://william278.net/project/husksync
# ┣╸ Config Help: https://william278.net/docs/husksync/config-file/
# ┗╸ Documentation: https://william278.net/docs/husksync

# Locale of the default language file to use.
# Docs: https://william278.net/docs/husksync/translations
language: en-gb
# Whether to automatically check for plugin updates on startup
check_for_updates: true
# Specify a common ID for grouping servers running HuskSync. Don't modify this unless you know what you're doing!
cluster_id: ''
# Enable development debug logging
debug_logging: true
# Whether to enable the Player Analytics hook.
# Docs: https://william278.net/docs/husksync/plan-hook
enable_plan_hook: true
# Whether to cancel game event packets directly when handling locked players if ProtocolLib or PacketEvents is installed
cancel_packets: true
# Add HuskSync commands to this list to prevent them from being registered (e.g. ['userdata'])
disabled_commands: []
# Database settings
database:
  # Type of database to use (MYSQL, MARIADB, POSTGRES, MONGO)
  type: MYSQL
  # Specify credentials here for your MYSQL, MARIADB, POSTGRES OR MONGO database
  credentials:
    host: localhost
    port: 3306
    database: minecraft
    username: root
    password: ''
    # Only change this if you're using MARIADB or POSTGRES
    parameters: ?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8
  # MYSQL, MARIADB, POSTGRES database Hikari connection pool properties. Don't modify this unless you know what you're doing!
  connection_pool:
    maximum_pool_size: 10
    minimum_idle: 10
    maximum_lifetime: 1800000
    keepalive_time: 0
    connection_timeout: 5000
  # Advanced MongoDB settings. Don't modify unless you know what you're doing!
  mongo_settings:
    using_atlas: false
    parameters: ?retryWrites=true&w=majority&authSource=HuskSync
  # Names of tables to use on your database. Don't modify this unless you know what you're doing!
  table_names:
    users: husksync_users
    user_data: husksync_user_data
# Redis settings
redis:
  # Specify the credentials of your Redis server here.
  # Set "user" to '' if you don't have one or would like to use the default user.
  # Set "password" to '' if you don't have one.
  credentials:
    host: localhost
    port: 6379
    # Only change the database if you know what you are doing. The default is 0.
    database: 0
    user: ''
    password: ''
    use_ssl: false
  # Options for if you're using Redis sentinel. Don't modify this unless you know what you're doing!
  sentinel:
    # The master set name for the Redis sentinel.
    master: ''
    # List of host:port pairs
    nodes: []
    password: ''
# Data syncing settings
synchronization:
  # The data synchronization mode to use (LOCKSTEP or DELAY). LOCKSTEP is recommended for most networks.
  # Docs: https://william278.net/docs/husksync/sync-modes
  mode: LOCKSTEP
  # The number of data snapshot backups that should be kept at once per user
  max_user_data_snapshots: 16
  # Number of hours between new snapshots being saved as backups (Use "0" to backup all snapshots)
  snapshot_backup_frequency: 4
  # List of save cause IDs for which a snapshot will be automatically pinned (so it won't be rotated).
  # Docs: https://william278.net/docs/husksync/data-rotation#save-causes
  auto_pinned_save_causes:
    - INVENTORY_COMMAND
    - ENDERCHEST_COMMAND
    - MOD_CONTAINER_COMMAND
    - BACKUP_RESTORE
    - LEGACY_MIGRATION
    - MPDB_MIGRATION
  # Whether to create a snapshot for users on a world when the server saves that world
  save_on_world_save: true
  # Configuration for how and when to sync player data when they die
  save_on_death:
    # Whether to create a snapshot for users when they die (containing their death drops)
    enabled: true
    # What items to save in death snapshots? (DROPS or ITEMS_TO_KEEP). Note that ITEMS_TO_KEEP (suggested for keepInventory servers) requires a Paper 1.19.4+ server.
    items_to_save: ITEMS_TO_KEEP
    # Should a death snapshot still be created even if the items to save on the player's death are empty?
    save_empty_items: true
    # Whether dead players who log out and log in to a different server should have their items saved.
    sync_dead_players_changing_server: true
  # Whether to use the snappy data compression algorithm. Keep on unless you know what you're doing
  compress_data: true
  # Where to display sync notifications (ACTION_BAR, CHAT or NONE)
  notification_display_slot: ACTION_BAR
  # Persist maps locked in a Cartography Table to let them be viewed on any server
  persist_locked_maps: true
  # If using the DELAY sync method, how long should this server listen for Redis key data updates before pulling data from the database instead (i.e., if the user did not change servers).
  network_latency_milliseconds: 500
  # Which data types to synchronize.
  # Docs: https://william278.net/docs/husksync/sync-features
  features:
    inventory: true
    ender_chest: true
    mod_data: true
    solcarrot_foodlist: true
    ultimine_ability: true
    mna_playerdata: true
    mna_persistent_data: true
    ars_nouveau_playerdata: true
    ars_nouveau_persistent_data: true
    irons_spellbooks_magic_data: true
    experience: true
    advancements: true
    game_mode: true
    flight_status: true
    potion_effects: true
    statistics: true
    health: true
    hunger: true
    attributes: true
    persistent_data: true
    location: false
  # List of mod data integrations to disable (e.g. ['curios'])
  # Leave empty to enable all available integrations
  mod_data_disabled_integrations: []
  # Settings for syncing Mana and Artifice (mna) player data (Forge/Arclight only)
  mna:
    # Whether to send MNA sync messages after applying data to ensure client-side visuals update
    # Disable if you experience plugin conflicts
    enable_visual_sync: true
    # Whether to sync Aura capability data
    sync_aura: true
    # Whether to sync MAPFX capability data
    sync_mapfx: true
    # Persistent NBT sync mode for MNA
    # OFF disables syncing
    # SAFE syncs only keys matching include_prefixes (minus exclude_keys)
    # FULL syncs keys matching include_prefixes plus include_keys (minus exclude_keys)
    persistent_mode: FULL
    # Top-level persistent NBT key prefixes to include when persistentMode is SAFE/FULL
    include_prefixes:
      - "mna:"
      - "mna_"
      - "ma_"
    # Additional top-level persistent NBT keys to include when persistentMode is FULL
    # Use this to sync keys that don't match include_prefixes
    include_keys:
      - PreventRemoteMovement
      - bind_wounds_last_pos
      - bone_armor_set_bonus
      - boss_arena_notification_cooldown
      - brambles_distance
      - brambles_old_x
      - brambles_old_y
      - brambles_old_z
      - cannon_shot
      - coldDarkPos
      - council_armor_reflect_counters
      - council_armor_set_bonus
      - demon_armor_meteor_jumping
      - demon_armor_set_bonus
      - demon_armor_sprint_time
      - eldrin_flight
      - entangle_apply
      - faction_casting_resource_idx
      - fey_armor_set_bonus
      - ginvis_disabled_until
      - hellfiretrident
      - icarianX
      - icarianY
      - icarianZ
      - last_coffin_boost
      - last_milk_time
      - last_shear_time
      - life_tap_pct
      - lift_speed
      - living_bomb_exploded
      - mana_shield_range
      - mist_form_speed_remove
      - possession_left_click_time
      - return_on_next_arrow_shot
      - return_stack
      - warded_notification_cooldown
      - wingsout
    # Top-level persistent NBT keys to exclude (never synced, regardless of mode)
    exclude_keys:
      - "mna:summon_ids"
      - "mna:summon_tag"
      - eldrin_flight_entity_id
      - mana_shield_source_id
      - shackles_of_pain_target
      - posessed_entity_id
      - mind_controller
  # Commands which should be blocked before a player has finished syncing (Use * to block all commands)
  blacklisted_commands_while_locked:
    - '*'
  # Configuration for how to sync attributes
  attributes:
    # Which attribute types should be saved as part of attribute syncing. Supports wildcard matching.
    # (e.g. ['minecraft:generic.max_health', 'minecraft:generic.*'])
    synced_attributes: 
      - "minecraft:generic.max_health"
      - "minecraft:max_health"
      - "minecraft:generic.max_absorption"
      - "minecraft:max_absorption"
      - "minecraft:generic.luck"
      - "minecraft:luck"
      - "minecraft:generic.scale"
      - "minecraft:scale"
      - "minecraft:generic.step_height"
      - "minecraft:step_height"
      - "minecraft:generic.gravity"
      - "minecraft:gravity"
    # Which attribute modifiers should not be saved when syncing users. Supports wildcard matching.
    # (e.g. ['minecraft:effect.speed', 'minecraft:effect.*'])
    ignored_modifiers: ['minecraft:effect.*', 'minecraft:creative_mode_*']
  # Event priorities for listeners (HIGHEST, NORMAL, LOWEST). Change if you encounter plugin conflicts
  event_priorities:
    quit_listener: LOWEST
    join_listener: LOWEST
    death_listener: NORMAL
```

</details>

<details>
<summary>server.yml</summary>

```yaml
# ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
# ┃     HuskSync - Server ID     ┃
# ┃    Developed by William278   ┃
# ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
# ┣╸ This file should contain the ID of this server as defined in your proxy config.
# ┗╸ If you join it using /server alpha, then set it to 'alpha' (case-sensitive)

name: beta
```

</details>
