/*
 * This file is part of HuskSync, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.husksync.mod;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.config.Settings;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Built-in Mana and Artifice (mna) data integration.
 */
public class MnaIntegration {

    private static final String MOD_ID = "mna";
    private static final String MOD_LIST_CLASS = "net.minecraftforge.fml.ModList";
    private static final String TAG_PARSER_CLASS = "net.minecraft.nbt.TagParser";
    private static final String COMPOUND_TAG_CLASS = "net.minecraft.nbt.CompoundTag";
    private static final String SERVER_PLAYER_CLASS = "net.minecraft.server.level.ServerPlayer";
    private static final String LIVING_ENTITY_CLASS = "net.minecraft.world.entity.LivingEntity";
    private static final String DISPATCHER_CLASS = "com.mna.network.ServerMessageDispatcher";
    private static final String CAPABILITY_PROVIDER_CLASS = "net.minecraftforge.common.capabilities.CapabilityProvider";

    private static final String CAP_MAGIC = "mna:magic";
    private static final String CAP_PROGRESSION = "mna:progression";
    private static final String CAP_ROTE = "mna:rote_spells";
    private static final String CAP_AURAS = "mna:auras";
    private static final String CAP_MAPFX = "mna:pfx_capability";

    private static final String PROGRESSION_PROVIDER_CLASS =
            "com.mna.capabilities.playerdata.progression.PlayerProgressionProvider";
    private static final String ROTE_PROVIDER_CLASS =
            "com.mna.capabilities.playerdata.rote.PlayerRoteSpellsProvider";
    private static final String AURA_PROVIDER_CLASS =
            "com.mna.capabilities.particles.ParticleAuraProvider";
    private static final String MAPFX_PROVIDER_CLASS =
            "com.mna.capabilities.entity.MAPFXProvider";

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    private static final String ANSI_BRIGHT_CYAN = "\u001B[96m";
    private static final String ANSI_BRIGHT_YELLOW = "\u001B[93m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_BRIGHT_GREEN = "\u001B[92m";

    private final BukkitHuskSync plugin;
    private final ReflectiveModSupport reflection;
    private final ModSyncCache<String> capsCache = new ModSyncCache<>();
    private final ModSyncCache<String> persistentCache = new ModSyncCache<>();

    private boolean resolved;
    private boolean available;

    @Nullable
    private Method parseTag;
    @Nullable
    private Method serializeCaps;
    @Nullable
    private Method deserializeCaps;
    @Nullable
    private Method getPersistentData;

    // Optional dispatcher methods (version-dependent)
    @Nullable
    private Method sendMagicSyncMessage;
    @Nullable
    private Method sendProgressionSyncMessage;
    @Nullable
    private Method sendRoteSyncMessage;
    @Nullable
    private Method sendAuraSyncMessageTracking;
    @Nullable
    private Method sendAuraSyncMessageTo;
    @Nullable
    private Method sendMAPFXMessage;

    public MnaIntegration(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        this.reflection = new ReflectiveModSupport(plugin, "MNA");
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

    public void cachePlayerData(@NotNull Player player) {
        if (!isAvailable()) {
            return;
        }

        // Capability sync
        if (serializeCaps != null) {
            captureCapsFromHandle(player, "pre-cache")
                    .ifPresent(snbt -> cache(capsCache, player.getUniqueId(), snbt));
        }

        // Persistent NBT sync
        if (getPersistentData != null && getMnaSettings().getPersistentMode() != Settings.SynchronizationSettings
                .MnaSettings.PersistentMode.OFF) {
            capturePersistentFromHandle(player, "pre-cache")
                    .ifPresent(snbt -> cache(persistentCache, player.getUniqueId(), snbt));
        }
    }

    @NotNull
    public Optional<String> captureCaps(@NotNull Player player) {
        if (!isAvailable() || serializeCaps == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture skipped")
                    + " (integration unavailable) for " + player.getName()));
            return captureFallback(capsCache, player, "caps", "integration unavailable");
        }

        if (!player.isOnline()) {
            return captureFallback(capsCache, player, "caps", "player offline");
        }

        final Optional<String> direct = captureCapsFromHandle(player, "capture");
        return capture(capsCache, player, "caps", direct, "direct capture failed");
    }

    @NotNull
    public Optional<String> capturePersistent(@NotNull Player player) {
        final Settings.SynchronizationSettings.MnaSettings settings = getMnaSettings();
        if (settings.getPersistentMode() == Settings.SynchronizationSettings.MnaSettings.PersistentMode.OFF) {
            return Optional.empty();
        }
        if (!isAvailable() || getPersistentData == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture persistent skipped")
                    + " (integration unavailable) for " + player.getName()));
            return captureFallback(persistentCache, player, "persistent", "integration unavailable");
        }

        if (!player.isOnline()) {
            return captureFallback(persistentCache, player, "persistent", "player offline");
        }

        final Optional<String> direct = capturePersistentFromHandle(player, "capture");
        return capture(persistentCache, player, "persistent", direct, "direct capture failed");
    }

    @NotNull
    public ApplyResult applyCaps(@NotNull Player player, @Nullable String snbt) {
        if (snbt == null || snbt.isBlank()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps skipped")
                    + " (empty NBT) for " + player.getName()));
            return ApplyResult.skipped("empty nbt");
        }
        capsCache.storePending(player.getUniqueId(), snbt);
        if (!isAvailable() || deserializeCaps == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps skipped")
                    + " (integration unavailable) for " + player.getName()));
            return ApplyResult.pending("integration unavailable");
        }

        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps failed")
                    + " (nms player null) for " + player.getName()));
            return ApplyResult.pending("nms player null");
        }

        final Object tag = parseCompoundTag(snbt);
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps failed")
                    + " (parseTag null) for " + player.getName()));
            return ApplyResult.failed("parseTag null");
        }

        try {
            final AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            plugin.debug(formatDebug("apply caps pre-bukkit health="
                    + color(ANSI_BRIGHT_CYAN, String.valueOf(player.getHealth()))
                    + ", maxHealth=" + color(ANSI_BRIGHT_CYAN,
                    String.valueOf(maxHealth != null ? maxHealth.getValue() : -1D))));
        } catch (Throwable ignored) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply caps pre-bukkit read failed")
                    + " for " + player.getName()));
        }

        plugin.debug(formatDebug("apply caps start for " + color(ANSI_BRIGHT_CYAN, player.getName())
                + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))));
        try {
            final ReadWriteNBT incoming = NBT.parseNBT(snbt);
            plugin.debug(formatDebug("apply caps incomingKeys="
                    + color(ANSI_BRIGHT_CYAN, formatKeys(new HashSet<>(incoming.getKeys()), 16))));
        } catch (Throwable ignored) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply caps key parse failed")
                    + " for " + player.getName()));
        }
        reflection.invoke(deserializeCaps, nmsPlayer, tag);
        plugin.debug(formatDebug("apply caps ok for " + color(ANSI_BRIGHT_CYAN, player.getName())));

        // Ensure the mod recalculates and notifies clients (capability deserialization alone is often insufficient)
        postApplyFixes(nmsPlayer);

        try {
            final AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            plugin.debug(formatDebug("apply caps post-bukkit health="
                    + color(ANSI_BRIGHT_CYAN, String.valueOf(player.getHealth()))
                    + ", maxHealth=" + color(ANSI_BRIGHT_CYAN,
                    String.valueOf(maxHealth != null ? maxHealth.getValue() : -1D))));
        } catch (Throwable ignored) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply caps post-bukkit read failed")
                    + " for " + player.getName()));
        }
        plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply caps pending confirmation")
                + " for " + player.getName()));
        return ApplyResult.pending("awaiting capture confirmation");
    }

    @NotNull
    public ApplyResult applyPersistent(@NotNull Player player, @Nullable String snbt) {
        final Settings.SynchronizationSettings.MnaSettings settings = getMnaSettings();
        if (settings.getPersistentMode() == Settings.SynchronizationSettings.MnaSettings.PersistentMode.OFF) {
            return ApplyResult.skipped("persistent sync disabled");
        }
        if (snbt == null || snbt.isBlank()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent skipped")
                    + " (empty NBT) for " + player.getName()));
            return ApplyResult.skipped("empty nbt");
        }
        persistentCache.storePending(player.getUniqueId(), snbt);
        if (!isAvailable() || getPersistentData == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent skipped")
                    + " (integration unavailable) for " + player.getName()));
            return ApplyResult.pending("integration unavailable");
        }

        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent failed")
                    + " (nms player null) for " + player.getName()));
            return ApplyResult.pending("nms player null");
        }

        final Object tagObj = reflection.invoke(getPersistentData, nmsPlayer);
        if (tagObj == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent failed")
                    + " (persistent tag null) for " + player.getName()));
            return ApplyResult.pending("persistent tag null");
        }

        try {
            plugin.debug(formatDebug("apply persistent start for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))
                    + ", mode=" + color(ANSI_BRIGHT_CYAN, settings.getPersistentMode().name())));

            final ReadWriteNBT target = NBT.wrapNMSTag(tagObj);
            final Set<String> existing = new HashSet<>(target.getKeys());
            final int targetBefore = existing.size();
            int targetRemoved = 0;
            for (String key : existing) {
                if (isManagedPersistentKey(key, settings)) {
                    target.removeKey(key);
                    targetRemoved++;
                }
            }
            final Set<String> targetAfter = new HashSet<>(target.getKeys());

            final ReadWriteNBT incoming = NBT.parseNBT(snbt);
            final Set<String> incomingKeys = new HashSet<>(incoming.getKeys());
            final int incomingBefore = incomingKeys.size();
            int incomingRemoved = 0;
            for (String key : incomingKeys) {
                if (!isManagedPersistentKey(key, settings)) {
                    incoming.removeKey(key);
                    incomingRemoved++;
                }
            }
            final Set<String> incomingKept = new HashSet<>(incoming.getKeys());

            target.mergeCompound(incoming);
            final Set<String> mergedKeys = new HashSet<>(target.getKeys());

            plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "apply persistent ok")
                    + " for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", targetKeys=" + color(ANSI_BRIGHT_CYAN, targetBefore + "->" + targetAfter.size())
                    + " (cleared=" + color(ANSI_BRIGHT_CYAN, String.valueOf(targetRemoved)) + ")"
                    + ", incomingKeys=" + color(ANSI_BRIGHT_CYAN, incomingBefore + "->" + incomingKept.size())
                    + " (removed=" + color(ANSI_BRIGHT_CYAN, String.valueOf(incomingRemoved)) + ")"
                    + ", mergedKeys=" + color(ANSI_BRIGHT_CYAN, String.valueOf(mergedKeys.size()))
                    + ", keptKeys=" + color(ANSI_BRIGHT_CYAN, formatKeys(incomingKept, 25))
                    + ", mode=" + color(ANSI_BRIGHT_CYAN, settings.getPersistentMode().name())));
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply persistent pending confirmation")
                    + " for " + player.getName()));
            return ApplyResult.pending("awaiting capture confirmation");
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent failed")
                    + " for " + player.getName()), e);
            return ApplyResult.failed("apply failed");
        }
    }

    @NotNull
    private Optional<String> captureCapsFromHandle(@NotNull Player player, @NotNull String phase) {
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }

        final Object tagObj = reflection.invoke(serializeCaps, nmsPlayer);
        if (tagObj == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (serializeCaps returned null) for " + player.getName()));
            return Optional.empty();
        }

        try {
            final Settings.SynchronizationSettings.MnaSettings settings = getMnaSettings();
            final ReadWriteNBT caps = NBT.wrapNMSTag(tagObj);
            final Set<String> keys = new HashSet<>(caps.getKeys());
            final int before = keys.size();
            int removed = 0;
            for (String key : keys) {
                if (!isManagedCapabilityKey(key, settings)) {
                    caps.removeKey(key);
                    removed++;
                }
            }

            final Set<String> kept = new HashSet<>(caps.getKeys());
            final String snbt = caps.toString();
            plugin.debug(formatDebug(phase + " ok for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))
                    + ", capsKeys=" + color(ANSI_BRIGHT_CYAN, before + "->" + kept.size())
                    + ", removed=" + color(ANSI_BRIGHT_CYAN, String.valueOf(removed))
                    + ", keptKeys=" + color(ANSI_BRIGHT_CYAN, formatKeys(kept, 16))
                    + ", aura=" + coloredBool(settings.isSyncAura())
                    + ", mapfx=" + coloredBool(settings.isSyncMapfx())));
            return Optional.of(snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (wrap caps failed) for " + player.getName()), e);
            return Optional.empty();
        }
    }

    @NotNull
    private Optional<String> capturePersistentFromHandle(@NotNull Player player, @NotNull String phase) {
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " persistent failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }

        final Object tagObj = reflection.invoke(getPersistentData, nmsPlayer);
        if (tagObj == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " persistent failed")
                    + " (persistent tag null) for " + player.getName()));
            return Optional.empty();
        }

        final String fullSnbt = tagObj.toString();
        try {
            final ReadWriteNBT filtered = NBT.parseNBT(fullSnbt);
            final Settings.SynchronizationSettings.MnaSettings settings = getMnaSettings();
            final Set<String> keys = new HashSet<>(filtered.getKeys());
            final int before = keys.size();
            int removed = 0;
            for (String key : keys) {
                if (!isManagedPersistentKey(key, settings)) {
                    filtered.removeKey(key);
                    removed++;
                }
            }

            final Set<String> kept = new HashSet<>(filtered.getKeys());
            final String snbt = filtered.toString();
            plugin.debug(formatDebug(phase + " persistent ok for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", fullBytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(fullSnbt.length()))
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))
                    + ", keys=" + color(ANSI_BRIGHT_CYAN, before + "->" + kept.size())
                    + ", removed=" + color(ANSI_BRIGHT_CYAN, String.valueOf(removed))
                    + ", keptKeys=" + color(ANSI_BRIGHT_CYAN, formatKeys(kept, 25))
                    + ", mode=" + color(ANSI_BRIGHT_CYAN, settings.getPersistentMode().name())));
            return Optional.of(snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " persistent failed")
                    + " (parse/filter failed) for " + player.getName()), e);
            return Optional.empty();
        }
    }

    private void postApplyFixes(@NotNull Object nmsPlayer) {
        final Settings.SynchronizationSettings.MnaSettings mna = getMnaSettings();
        plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " start"
                + " visual=" + coloredBool(mna.isEnableVisualSync())
                + " aura=" + coloredBool(mna.isSyncAura())
                + " mapfx=" + coloredBool(mna.isSyncMapfx())
                + " persistent=" + color(ANSI_BRIGHT_CYAN, mna.getPersistentMode().name())));

        // Progression: reapply tier attribute modifiers after NBT load
        final Object progression = getCapabilityInstance(nmsPlayer, PROGRESSION_PROVIDER_CLASS, "PROGRESSION");
        if (progression != null) {
            final Object tierObj = reflection.invoke(progression, "getTier");
            final int tier = tierObj instanceof Number n ? n.intValue() : 0;
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply")
                    + " progression tier=" + color(ANSI_BRIGHT_CYAN, String.valueOf(tier))
                    + " (invoking setTier(tier, player, false))"));
            reflection.invoke(progression, "setTier", tier, nmsPlayer, false);
            plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "post-apply ok") + " progression setTier invoked"));
        } else {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped")
                    + " progression capability missing (tier attributes may be stale)"));
        }

        // Rote: ensure dirty so client sync/polling doesn't get skipped
        final Object rote = getCapabilityInstance(nmsPlayer, ROTE_PROVIDER_CLASS, "ROTE");
        if (rote != null) {
            reflection.invoke(rote, "setDirty");
            plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "post-apply ok") + " rote setDirty()"));
        } else {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped") + " rote capability missing"));
        }

        // Aura
        if (mna.isSyncAura()) {
            final Object aura = getCapabilityInstance(nmsPlayer, AURA_PROVIDER_CLASS, "AURA");
            if (aura != null) {
                reflection.invoke(aura, "setDirty");
                plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "post-apply ok") + " aura setDirty()"));
            } else {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped") + " aura capability missing"));
            }
        } else {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " aura sync disabled"));
        }

        // MAPFX: don't rely on MAPFX#sync(needsSync); send packet directly
        if (mna.isSyncMapfx()) {
            if (sendMAPFXMessage != null) {
                reflection.invokeStatic(sendMAPFXMessage, nmsPlayer);
                plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "post-apply ok") + " sendMAPFXMessage()"));
            } else {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped")
                        + " sendMAPFXMessage missing (visuals may be stale)"));
            }
        } else {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " mapfx sync disabled"));
        }

        // Force client refresh (optional)
        if (mna.isEnableVisualSync()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " visual dispatch availability:"
                    + " magic=" + coloredBool(sendMagicSyncMessage != null)
                    + " progression=" + coloredBool(sendProgressionSyncMessage != null)
                    + " rote=" + coloredBool(sendRoteSyncMessage != null)
                    + " auraTo=" + coloredBool(sendAuraSyncMessageTo != null)
                    + " auraTracking=" + coloredBool(sendAuraSyncMessageTracking != null)));

            if (sendMagicSyncMessage != null) {
                reflection.invokeStatic(sendMagicSyncMessage, nmsPlayer);
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " sent magic sync"));
            } else {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped")
                        + " magic sync (method missing)"));
            }

            if (sendProgressionSyncMessage != null) {
                reflection.invokeStatic(sendProgressionSyncMessage, nmsPlayer);
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " sent progression sync"));
            } else {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped")
                        + " progression sync (method missing)"));
            }

            if (sendRoteSyncMessage != null) {
                reflection.invokeStatic(sendRoteSyncMessage, nmsPlayer);
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " sent rote sync"));
            } else {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped")
                        + " rote sync (method missing)"));
            }

            if (mna.isSyncAura()) {
                if (sendAuraSyncMessageTo != null) {
                    reflection.invokeStatic(sendAuraSyncMessageTo, nmsPlayer, nmsPlayer);
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " sent aura self sync"));
                } else {
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped")
                            + " aura self sync (method missing)"));
                }

                if (sendAuraSyncMessageTracking != null) {
                    reflection.invokeStatic(sendAuraSyncMessageTracking, nmsPlayer);
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " sent aura tracking sync"));
                } else {
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "post-apply skipped")
                            + " aura tracking sync (method missing)"));
                }
            }

            plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "post-apply ok") + " visual sync complete"));
        } else {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "post-apply") + " visual sync disabled"));
        }
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        plugin.debug(formatDebug("resolve start"));

        try {
            final Class<?> modList = Class.forName(MOD_LIST_CLASS);
            final Object modListInstance = modList.getMethod("get").invoke(null);
            final boolean loaded = (boolean) modList.getMethod("isLoaded", String.class)
                    .invoke(modListInstance, MOD_ID);
            if (!loaded) {
                available = false;
                plugin.debug(formatDebug("resolve: mod not loaded (" + MOD_ID + ")"));
                return;
            }

            final Class<?> parserClass = Class.forName(TAG_PARSER_CLASS);
            final Class<?> compoundTagClass = Class.forName(COMPOUND_TAG_CLASS);
            parseTag = findTagParser(parserClass, compoundTagClass);

            final Class<?> serverPlayerClass = Class.forName(SERVER_PLAYER_CLASS);
            serializeCaps = reflection.findMethodPreferStatic(serverPlayerClass, "serializeCaps", 0);
            if (serializeCaps != null && !compoundTagClass.isAssignableFrom(serializeCaps.getReturnType())) {
                serializeCaps = null;
            }
            if (serializeCaps == null) {
                serializeCaps = findCapabilitySerializeCaps(compoundTagClass);
                if (serializeCaps != null) {
                    plugin.debug(formatDebug("resolved serializeCaps via signature="
                                             + color(ANSI_BRIGHT_CYAN, serializeCaps.getDeclaringClass().getName()
                                                    + "#" + serializeCaps.getName())));
                }
            }

            deserializeCaps = reflection.findMethodPreferStatic(serverPlayerClass, "deserializeCaps", 1);
            if (deserializeCaps != null && (deserializeCaps.getParameterCount() != 1
                    || !compoundTagClass.isAssignableFrom(deserializeCaps.getParameterTypes()[0]))) {
                deserializeCaps = null;
            }
            if (deserializeCaps == null) {
                deserializeCaps = findCapabilityDeserializeCaps(compoundTagClass);
                if (deserializeCaps != null) {
                    plugin.debug(formatDebug("resolved deserializeCaps via signature="
                                             + color(ANSI_BRIGHT_CYAN, deserializeCaps.getDeclaringClass().getName()
                                                    + "#" + deserializeCaps.getName())));
                }
            }

            getPersistentData = reflection.findMethodPreferStatic(serverPlayerClass, "getPersistentData", 0);
            if (getPersistentData != null && !compoundTagClass.isAssignableFrom(getPersistentData.getReturnType())) {
                getPersistentData = null;
            }

            // Optional dispatcher methods (not required for availability)
            resolveDispatcher(serverPlayerClass);

            available = parseTag != null && serializeCaps != null && deserializeCaps != null;
            plugin.debug(formatDebug("resolved parseTag=" + color(ANSI_BRIGHT_CYAN, String.valueOf(parseTag != null))
                    + ", serializeCaps=" + color(ANSI_BRIGHT_CYAN, String.valueOf(serializeCaps != null))
                    + ", deserializeCaps=" + color(ANSI_BRIGHT_CYAN, String.valueOf(deserializeCaps != null))
                    + ", getPersistentData=" + color(ANSI_BRIGHT_CYAN, String.valueOf(getPersistentData != null))
                    + ", available=" + color(ANSI_BRIGHT_CYAN, String.valueOf(available))));
        } catch (Throwable e) {
            available = false;
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "integration not available")), e);
        }
    }

    private void resolveDispatcher(@NotNull Class<?> serverPlayerClass) {
        try {
            final Class<?> dispatcher = Class.forName(DISPATCHER_CLASS);
            final Class<?> livingEntity = Class.forName(LIVING_ENTITY_CLASS);

            sendMagicSyncMessage = findDispatcherMethod(dispatcher, "sendMagicSyncMessage", serverPlayerClass);
            sendProgressionSyncMessage = findDispatcherMethod(dispatcher, "sendProgressionSyncMessage", serverPlayerClass);
            sendRoteSyncMessage = findDispatcherMethod(dispatcher, "sendRoteSyncMessage", serverPlayerClass);
            sendAuraSyncMessageTracking = findDispatcherMethod(dispatcher, "sendAuraSyncMessage", serverPlayerClass);
            sendAuraSyncMessageTo = findDispatcherMethod(dispatcher, "sendAuraSyncMessage", serverPlayerClass, serverPlayerClass);
            sendMAPFXMessage = findDispatcherMethod(dispatcher, "sendMAPFXMessage", livingEntity);

            plugin.debug(formatDebug("resolved dispatcher magic=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sendMagicSyncMessage != null))
                    + ", progression=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sendProgressionSyncMessage != null))
                    + ", rote=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sendRoteSyncMessage != null))
                    + ", auraTracking=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sendAuraSyncMessageTracking != null))
                    + ", auraTo=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sendAuraSyncMessageTo != null))
                    + ", mapfx=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sendMAPFXMessage != null))));
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "dispatcher methods unavailable")), e);
        }
    }

    @Nullable
    private Method findDispatcherMethod(@NotNull Class<?> dispatcher, @NotNull String name, @NotNull Class<?>... params) {
        try {
            final Method method = dispatcher.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private Method findTagParser(@NotNull Class<?> parserClass, @NotNull Class<?> compoundTagClass) {
        Method method = reflection.findMethod(parserClass, "parseTag", 1);
        if (method != null && compoundTagClass.isAssignableFrom(method.getReturnType())) {
            plugin.debug(formatDebug("TagParser method=" + color(ANSI_BRIGHT_CYAN, "parseTag")));
            return method;
        }
        method = reflection.findMethod(parserClass, "parse", 1);
        if (method != null && compoundTagClass.isAssignableFrom(method.getReturnType())) {
            plugin.debug(formatDebug("TagParser method=" + color(ANSI_BRIGHT_CYAN, "parse")));
            return method;
        }
        for (Method candidate : parserClass.getMethods()) {
            if (candidate.getParameterCount() == 1
                    && candidate.getParameterTypes()[0] == String.class
                    && compoundTagClass.isAssignableFrom(candidate.getReturnType())) {
                candidate.setAccessible(true);
                plugin.debug(formatDebug("TagParser method=" + color(ANSI_BRIGHT_CYAN, candidate.getName())));
                return candidate;
            }
        }
        plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "TagParser method not found")));
        return null;
    }

    @Nullable
    private Object parseCompoundTag(@NotNull String snbt) {
        if (parseTag == null) {
            return null;
        }
        try {
            return reflection.invokeStatic(parseTag, snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "parse NBT failed")
                    + " bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))), e);
            return null;
        }
    }

    @Nullable
    private Method findCapabilitySerializeCaps(@NotNull Class<?> compoundTagClass) {
        try {
            final Class<?> provider = Class.forName(CAPABILITY_PROVIDER_CLASS);
            for (Class<?> current = provider; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getParameterCount() == 0
                            && compoundTagClass.isAssignableFrom(method.getReturnType())) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capability serializeCaps signature lookup failed")), e);
        }
        return null;
    }

    @Nullable
    private Method findCapabilityDeserializeCaps(@NotNull Class<?> compoundTagClass) {
        try {
            final Class<?> provider = Class.forName(CAPABILITY_PROVIDER_CLASS);
            for (Class<?> current = provider; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getParameterCount() == 1
                            && compoundTagClass.isAssignableFrom(method.getParameterTypes()[0])
                            && method.getReturnType() == void.class) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capability deserializeCaps signature lookup failed")), e);
        }
        return null;
    }

    @Nullable
    private Object getCapabilityInstance(@NotNull Object nmsPlayer, @NotNull String providerClassName,
                                         @NotNull String capabilityFieldName) {
        try {
            final Class<?> provider = Class.forName(providerClassName);
            final Field field = provider.getField(capabilityFieldName);
            final Object capability = field.get(null);
            if (capability == null) {
                return null;
            }

            Object lazy = null;
            final Method getCapability = reflection.findMethod(nmsPlayer.getClass(), "getCapability", 1);
            if (getCapability != null) {
                lazy = reflection.invoke(getCapability, nmsPlayer, capability);
            } else {
                final Method getCapability2 = reflection.findMethod(nmsPlayer.getClass(), "getCapability", 2);
                if (getCapability2 != null) {
                    lazy = reflection.invoke(getCapability2, nmsPlayer, capability, null);
                }
            }
            if (lazy == null) {
                return null;
            }

            // LazyOptional#orElse(T other)
            return reflection.invoke(lazy, "orElse", (Object) null);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "getCapability failed")
                    + " " + providerClassName + "#" + capabilityFieldName), e);
            return null;
        }
    }

    private boolean isManagedCapabilityKey(@NotNull String key,
                                          @NotNull Settings.SynchronizationSettings.MnaSettings settings) {
        if (CAP_MAGIC.equals(key) || CAP_PROGRESSION.equals(key) || CAP_ROTE.equals(key)) {
            return true;
        }
        if (CAP_AURAS.equals(key)) {
            return settings.isSyncAura();
        }
        if (CAP_MAPFX.equals(key)) {
            return settings.isSyncMapfx();
        }
        return false;
    }

    private boolean isManagedPersistentKey(@NotNull String key,
                                          @NotNull Settings.SynchronizationSettings.MnaSettings settings) {
        if (settings.getExcludeKeys().contains(key)) {
            return false;
        }

        final boolean prefixMatch = settings.getIncludePrefixes().stream().anyMatch(key::startsWith);
        if (prefixMatch) {
            return true;
        }

        return settings.getPersistentMode() == Settings.SynchronizationSettings.MnaSettings.PersistentMode.FULL
                && settings.getIncludeKeys().contains(key);
    }

    @NotNull
    private Settings.SynchronizationSettings.MnaSettings getMnaSettings() {
        return plugin.getSettings().getSynchronization().getMna();
    }

    @NotNull
    private String coloredBool(boolean value) {
        return color(value ? ANSI_BRIGHT_GREEN : ANSI_BRIGHT_RED, String.valueOf(value));
    }

    @NotNull
    private String formatKeys(@NotNull Set<String> keys, int limit) {
        if (keys.isEmpty()) {
            return "[]";
        }
        final List<String> sorted = keys.stream().sorted().toList();
        if (sorted.size() <= limit) {
            return sorted.toString();
        }
        return sorted.subList(0, limit).toString() + " ... (+" + (sorted.size() - limit) + ")";
    }

    @NotNull
    private Optional<String> capture(@NotNull ModSyncCache<String> cache, @NotNull Player player,
                                     @NotNull String type, @NotNull Optional<String> direct,
                                     @NotNull String unavailableReason) {
        final CaptureResult<String> result = cache.capture(player.getUniqueId(), direct, unavailableReason);
        logCaptureResult(type, player, result);
        return result.dataOptional();
    }

    @NotNull
    private Optional<String> captureFallback(@NotNull ModSyncCache<String> cache, @NotNull Player player,
                                             @NotNull String type, @NotNull String reason) {
        final CaptureResult<String> result = cache.fallback(player.getUniqueId(), reason);
        logCaptureResult(type, player, result);
        return result.dataOptional();
    }

    private void logCaptureResult(@NotNull String type, @NotNull Player player, @NotNull CaptureResult<String> result) {
        switch (result.status()) {
            case CAPTURED -> plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "capture ok")
                    + " type=" + color(ANSI_BRIGHT_CYAN, type)
                    + " for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case FALLBACK_PENDING -> plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capture using pending")
                    + " type=" + color(ANSI_BRIGHT_CYAN, type)
                    + " for " + player.getName()
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case FALLBACK_CACHED -> plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capture using cached")
                    + " type=" + color(ANSI_BRIGHT_CYAN, type)
                    + " for " + player.getName()
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case FALLBACK_TRUSTED -> plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capture using trusted")
                    + " type=" + color(ANSI_BRIGHT_CYAN, type)
                    + " for " + player.getName()
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case UNAVAILABLE -> plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture failed")
                    + " type=" + color(ANSI_BRIGHT_CYAN, type)
                    + " (no fallback data) for " + player.getName()
                    + " (" + result.reason() + ")"));
        }
    }

    @NotNull
    private String cacheName(@NotNull ModSyncCache<String> cache) {
        if (cache == capsCache) {
            return "caps";
        }
        if (cache == persistentCache) {
            return "persistent";
        }
        return "unknown";
    }

    private void cache(@NotNull ModSyncCache<String> cache, @NotNull UUID uuid, @NotNull String snbt) {
        if (snbt.isBlank()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "cache skipped")
                    + " (blank SNBT) type=" + color(ANSI_BRIGHT_CYAN, cacheName(cache))
                    + " uuid=" + color(ANSI_BRIGHT_CYAN, uuid.toString())));
            return;
        }
        cache.storeTrusted(uuid, snbt);
        plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "cache stored")
                + " type=" + color(ANSI_BRIGHT_CYAN, cacheName(cache))
                + " uuid=" + color(ANSI_BRIGHT_CYAN, uuid.toString())
                + " bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))));
    }

    @NotNull
    private String formatDebug(@NotNull String message) {
        return ANSI_BRIGHT_MAGENTA + "[MNA]" + ANSI_RESET + " " + message;
    }

    @NotNull
    private String color(@NotNull String color, @NotNull String message) {
        return color + message + ANSI_RESET;
    }

}
