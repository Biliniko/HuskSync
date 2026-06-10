# HuskSync Changelog

## 2026-02-04
- Added mod data synchronization framework with Curios integration (Bukkit/Arclight).
- Added CosmeticArmorReworked integration and Curios cosmetic slot support.
- Added `/modinv <type> <player> [version]` to view and edit mod container data.
- Added `mod_data` sync feature flag and mod integration disable list.
- Added development workflow documentation.
- Added Ultimine Addition player ability (can_ultimine) sync.
- Added Mana and Artifice (MNA) player data sync (capabilities and optional Forge persistent NBT) with post-apply dirty/sync dispatch to avoid client desync.
- Standardized built-in mod sync lifecycle handling with pending, cached, and trusted data fallback to reduce empty local capability overwrites.
- Updated command, config, and data snapshot docs for mod data support.
