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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ArsNouveauIntegration {

    private static final String MOD_ID = "ars_nouveau";
    private static final String MOD_LIST_CLASS = "net.minecraftforge.fml.ModList";
    private static final String TAG_PARSER_CLASS = "net.minecraft.nbt.TagParser";
    private static final String COMPOUND_TAG_CLASS = "net.minecraft.nbt.CompoundTag";
    private static final String SERVER_PLAYER_CLASS = "net.minecraft.server.level.ServerPlayer";
    private static final String PLAYER_CLASS = "net.minecraft.world.entity.player.Player";
    private static final String CAPABILITY_PROVIDER_CLASS = "net.minecraftforge.common.capabilities.CapabilityProvider";
    private static final String CAPABILITY_REGISTRY_CLASS =
            "com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry$EventHandler";
    private static final String MANA_EVENTS_CLASS = "com.hollingsworth.arsnouveau.common.event.ManaCapEvents";
    private static final String NETWORKING_CLASS = "com.hollingsworth.arsnouveau.common.network.Networking";
    private static final String PERSISTENT_PACKET_CLASS =
            "com.hollingsworth.arsnouveau.common.network.PacketGetPersistentData";

    private static final String CAP_PLAYER_DATA = "ars_nouveau:player_data";
    private static final String CAP_MANA = "ars_nouveau:mana";
    private static final String PERSISTED_FALLBACK_KEY = "PlayerPersisted";
    private static final String PERSISTENT_SCRYER = "an_scryer";
    private static final String PERSISTENT_BOOK = "an_book_";
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
    @Nullable
    private Method syncPlayerCap;
    @Nullable
    private Method syncMana;
    @Nullable
    private Method sendToPlayerClient;
    @Nullable
    private Constructor<?> persistentPacketConstructor;
    @NotNull
    private String persistedNbtKey = PERSISTED_FALLBACK_KEY;

    public ArsNouveauIntegration(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        this.reflection = new ReflectiveModSupport(plugin, "Ars Nouveau");
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

    public void cachePlayerData(@NotNull Player player) {
        if (!isAvailable()) {
            return;
        }
        captureCapsFromHandle(player, "pre-cache").ifPresent(snbt -> cache(capsCache, player.getUniqueId(), snbt));
        capturePersistentFromHandle(player, "pre-cache")
                .ifPresent(snbt -> cache(persistentCache, player.getUniqueId(), snbt));
    }

    @NotNull
    public Optional<String> captureCaps(@NotNull Player player) {
        if (!isAvailable() || serializeCaps == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture caps skipped")
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

        reflection.invoke(deserializeCaps, nmsPlayer, tag);
        plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "apply caps ok")
                + " for " + color(ANSI_BRIGHT_CYAN, player.getName())));
        syncClient(nmsPlayer);
        plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply caps pending confirmation")
                + " for " + player.getName()));
        return ApplyResult.pending("awaiting capture confirmation");
    }

    @NotNull
    public ApplyResult applyPersistent(@NotNull Player player, @Nullable String snbt) {
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
        final Object persistentData = reflection.invoke(getPersistentData, nmsPlayer);
        if (persistentData == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent failed")
                    + " (persistent data null) for " + player.getName()));
            return ApplyResult.pending("persistent data null");
        }

        try {
            final ReadWriteNBT root = NBT.wrapNMSTag(persistentData);
            final ReadWriteNBT incoming = NBT.parseNBT(snbt);
            final ReadWriteNBT targetPersisted = root.getOrCreateCompound(persistedNbtKey);
            targetPersisted.removeKey(PERSISTENT_SCRYER);
            targetPersisted.removeKey(PERSISTENT_BOOK);

            final ReadWriteNBT incomingPersisted = incoming.getCompound(persistedNbtKey);
            if (incomingPersisted != null) {
                copyManagedPersistentKey(incomingPersisted, targetPersisted, PERSISTENT_SCRYER);
                copyManagedPersistentKey(incomingPersisted, targetPersisted, PERSISTENT_BOOK);
            }
            plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "apply persistent ok")
                    + " for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", keys=" + color(ANSI_BRIGHT_CYAN, formatKeys(new HashSet<>(targetPersisted.getKeys())))));
            syncPersistentClient(nmsPlayer, targetPersisted);
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
        if (serializeCaps == null) {
            return Optional.empty();
        }
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " caps failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }

        final Object tagObj = reflection.invoke(serializeCaps, nmsPlayer);
        if (tagObj == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " caps failed")
                    + " (serializeCaps returned null) for " + player.getName()));
            return Optional.empty();
        }

        try {
            final ReadWriteNBT caps = NBT.wrapNMSTag(tagObj);
            filterKeys(caps, Set.of(CAP_PLAYER_DATA, CAP_MANA));
            final Set<String> kept = new HashSet<>(caps.getKeys());
            if (kept.isEmpty()) {
                return Optional.empty();
            }
            final String snbt = caps.toString();
            plugin.debug(formatDebug(phase + " caps ok for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))
                    + ", keys=" + color(ANSI_BRIGHT_CYAN, formatKeys(kept))));
            return Optional.of(snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " caps failed")
                    + " (wrap/filter failed) for " + player.getName()), e);
            return Optional.empty();
        }
    }

    @NotNull
    private Optional<String> capturePersistentFromHandle(@NotNull Player player, @NotNull String phase) {
        if (getPersistentData == null) {
            return Optional.empty();
        }
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " persistent failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }

        final Object tagObj = reflection.invoke(getPersistentData, nmsPlayer);
        if (tagObj == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " persistent failed")
                    + " (persistent data null) for " + player.getName()));
            return Optional.empty();
        }

        try {
            final ReadWriteNBT full = NBT.parseNBT(tagObj.toString());
            final ReadWriteNBT sourcePersisted = full.getCompound(persistedNbtKey);

            final ReadWriteNBT filtered = NBT.createNBTObject();
            final ReadWriteNBT filteredPersisted = filtered.getOrCreateCompound(persistedNbtKey);
            if (sourcePersisted != null) {
                copyManagedPersistentKey(sourcePersisted, filteredPersisted, PERSISTENT_SCRYER);
                copyManagedPersistentKey(sourcePersisted, filteredPersisted, PERSISTENT_BOOK);
            }

            final String snbt = filtered.toString();
            plugin.debug(formatDebug(phase + " persistent ok for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))
                    + ", keys=" + color(ANSI_BRIGHT_CYAN, formatKeys(new HashSet<>(filteredPersisted.getKeys())))));
            return Optional.of(snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " persistent failed")
                    + " (parse/filter failed) for " + player.getName()), e);
            return Optional.empty();
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
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "mod not loaded") + " (" + MOD_ID + ")"));
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

            resolvePersistedKey();
            resolveSyncMethods(serverPlayerClass, compoundTagClass);

            available = parseTag != null && serializeCaps != null && deserializeCaps != null
                    && getPersistentData != null;
            plugin.debug(formatDebug("resolved parseTag=" + color(ANSI_BRIGHT_CYAN, String.valueOf(parseTag != null))
                    + ", serializeCaps=" + color(ANSI_BRIGHT_CYAN, String.valueOf(serializeCaps != null))
                    + ", deserializeCaps=" + color(ANSI_BRIGHT_CYAN, String.valueOf(deserializeCaps != null))
                    + ", getPersistentData=" + color(ANSI_BRIGHT_CYAN, String.valueOf(getPersistentData != null))
                    + ", persistedKey=" + color(ANSI_BRIGHT_CYAN, persistedNbtKey)
                    + ", available=" + color(ANSI_BRIGHT_CYAN, String.valueOf(available))));
        } catch (Throwable e) {
            available = false;
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "integration not available")), e);
        }
    }

    private void resolvePersistedKey() {
        try {
            final Class<?> playerClass = Class.forName(PLAYER_CLASS);
            final Object value = playerClass.getField("PERSISTED_NBT_TAG").get(null);
            if (value instanceof String key && !key.isBlank()) {
                persistedNbtKey = key;
            }
        } catch (Throwable ignored) {
            persistedNbtKey = PERSISTED_FALLBACK_KEY;
        }
    }

    private void resolveSyncMethods(@NotNull Class<?> serverPlayerClass, @NotNull Class<?> compoundTagClass) {
        try {
            final Class<?> capabilityRegistry = Class.forName(CAPABILITY_REGISTRY_CLASS);
            syncPlayerCap = reflection.findMethodPreferStatic(capabilityRegistry, "syncPlayerCap", 1);
            final Class<?> manaEvents = Class.forName(MANA_EVENTS_CLASS);
            syncMana = reflection.findMethodPreferStatic(manaEvents, "syncPlayerEvent", 1);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capability sync methods unavailable")), e);
        }

        try {
            final Class<?> networking = Class.forName(NETWORKING_CLASS);
            sendToPlayerClient = reflection.findMethodPreferStatic(networking, "sendToPlayerClient", 2);
            final Class<?> packetClass = Class.forName(PERSISTENT_PACKET_CLASS);
            persistentPacketConstructor = packetClass.getConstructor(compoundTagClass);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "persistent client sync methods unavailable")), e);
        }
    }

    @Nullable
    private Method findTagParser(@NotNull Class<?> parserClass, @NotNull Class<?> compoundTagClass) {
        Method method = reflection.findMethod(parserClass, "parseTag", 1);
        if (method != null && compoundTagClass.isAssignableFrom(method.getReturnType())) {
            return method;
        }
        method = reflection.findMethod(parserClass, "parse", 1);
        if (method != null && compoundTagClass.isAssignableFrom(method.getReturnType())) {
            return method;
        }
        for (Method candidate : parserClass.getMethods()) {
            if (candidate.getParameterCount() == 1
                    && candidate.getParameterTypes()[0] == String.class
                    && compoundTagClass.isAssignableFrom(candidate.getReturnType())) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
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
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))), e);
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
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capability serializeCaps signature lookup failed")),
                    e);
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
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capability deserializeCaps signature lookup failed")),
                    e);
        }
        return null;
    }

    private void syncClient(@NotNull Object nmsPlayer) {
        reflection.invokeStatic(syncPlayerCap, nmsPlayer);
        reflection.invokeStatic(syncMana, nmsPlayer);
    }

    private void syncPersistentClient(@NotNull Object nmsPlayer, @NotNull ReadWriteNBT persistedTag) {
        if (sendToPlayerClient == null || persistentPacketConstructor == null || parseTag == null) {
            return;
        }
        try {
            final Object tag = parseCompoundTag(persistedTag.toString());
            if (tag == null) {
                return;
            }
            final Object packet = persistentPacketConstructor.newInstance(tag);
            reflection.invokeStatic(sendToPlayerClient, packet, nmsPlayer);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "persistent client sync failed")), e);
        }
    }

    private void copyManagedPersistentKey(@NotNull ReadWriteNBT source, @NotNull ReadWriteNBT target,
                                          @NotNull String key) {
        if (!source.hasTag(key)) {
            return;
        }
        final ReadWriteNBT oneKey = NBT.createNBTObject();
        oneKey.mergeCompound(source);
        for (String existing : new HashSet<>(oneKey.getKeys())) {
            if (!existing.equals(key)) {
                oneKey.removeKey(existing);
            }
        }
        target.mergeCompound(oneKey);
    }

    private void filterKeys(@NotNull ReadWriteNBT tag, @NotNull Set<String> keysToKeep) {
        for (String key : new HashSet<>(tag.getKeys())) {
            if (!keysToKeep.contains(key)) {
                tag.removeKey(key);
            }
        }
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

    private void cache(@NotNull ModSyncCache<String> cache, @NotNull UUID uuid, @NotNull String snbt) {
        if (snbt.isBlank()) {
            return;
        }
        cache.storeTrusted(uuid, snbt);
    }

    @NotNull
    private String formatDebug(@NotNull String message) {
        return ANSI_BRIGHT_MAGENTA + "[Ars Nouveau]" + ANSI_RESET + " " + message;
    }

    @NotNull
    private String color(@NotNull String color, @NotNull String message) {
        return color + message + ANSI_RESET;
    }

    @NotNull
    private String formatKeys(@NotNull Set<String> keys) {
        return keys.stream().sorted().toList().toString();
    }

}
