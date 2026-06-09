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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final long CAPTURE_CACHE_TTL_MS = 5000L;

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    private static final String ANSI_BRIGHT_CYAN = "\u001B[96m";
    private static final String ANSI_BRIGHT_YELLOW = "\u001B[93m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_BRIGHT_GREEN = "\u001B[92m";

    private final BukkitHuskSync plugin;
    private final Map<String, Method> methodCache = new HashMap<>();
    private final Map<UUID, CachedNbt> cachedCaps = new ConcurrentHashMap<>();
    private final Map<UUID, CachedNbt> cachedPersistent = new ConcurrentHashMap<>();

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

    private record CachedNbt(@NotNull String snbt, long timestamp) {
    }

    public ArsNouveauIntegration(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

    public void cachePlayerData(@NotNull Player player) {
        if (!isAvailable()) {
            return;
        }
        captureCapsFromHandle(player, "pre-cache").ifPresent(snbt -> cache(cachedCaps, player.getUniqueId(), snbt));
        capturePersistentFromHandle(player, "pre-cache")
                .ifPresent(snbt -> cache(cachedPersistent, player.getUniqueId(), snbt));
    }

    @NotNull
    public Optional<String> captureCaps(@NotNull Player player) {
        if (!isAvailable() || serializeCaps == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture caps skipped")
                    + " (integration unavailable) for " + player.getName()));
            return Optional.empty();
        }
        final Optional<CachedNbt> cached = getCached(cachedCaps, player.getUniqueId());
        if (!player.isOnline()) {
            return cached.map(CachedNbt::snbt);
        }

        final Optional<String> direct = captureCapsFromHandle(player, "capture");
        if (direct.isPresent()) {
            cache(cachedCaps, player.getUniqueId(), direct.get());
            return direct;
        }
        return cached.map(CachedNbt::snbt);
    }

    @NotNull
    public Optional<String> capturePersistent(@NotNull Player player) {
        if (!isAvailable() || getPersistentData == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture persistent skipped")
                    + " (integration unavailable) for " + player.getName()));
            return Optional.empty();
        }
        final Optional<CachedNbt> cached = getCached(cachedPersistent, player.getUniqueId());
        if (!player.isOnline()) {
            return cached.map(CachedNbt::snbt);
        }

        final Optional<String> direct = capturePersistentFromHandle(player, "capture");
        if (direct.isPresent()) {
            cache(cachedPersistent, player.getUniqueId(), direct.get());
            return direct;
        }
        return cached.map(CachedNbt::snbt);
    }

    public void applyCaps(@NotNull Player player, @Nullable String snbt) {
        if (!isAvailable() || deserializeCaps == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps skipped")
                    + " (integration unavailable) for " + player.getName()));
            return;
        }
        if (snbt == null || snbt.isBlank()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps skipped")
                    + " (empty NBT) for " + player.getName()));
            return;
        }

        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps failed")
                    + " (nms player null) for " + player.getName()));
            return;
        }

        final Object tag = parseCompoundTag(snbt);
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply caps failed")
                    + " (parseTag null) for " + player.getName()));
            return;
        }

        invoke(deserializeCaps, nmsPlayer, tag);
        plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "apply caps ok")
                + " for " + color(ANSI_BRIGHT_CYAN, player.getName())));
        syncClient(nmsPlayer);
    }

    public void applyPersistent(@NotNull Player player, @Nullable String snbt) {
        if (!isAvailable() || getPersistentData == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent skipped")
                    + " (integration unavailable) for " + player.getName()));
            return;
        }
        if (snbt == null || snbt.isBlank()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent skipped")
                    + " (empty NBT) for " + player.getName()));
            return;
        }

        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent failed")
                    + " (nms player null) for " + player.getName()));
            return;
        }
        final Object persistentData = invoke(getPersistentData, nmsPlayer);
        if (persistentData == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent failed")
                    + " (persistent data null) for " + player.getName()));
            return;
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
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply persistent failed")
                    + " for " + player.getName()), e);
        }
    }

    @NotNull
    private Optional<String> captureCapsFromHandle(@NotNull Player player, @NotNull String phase) {
        if (serializeCaps == null) {
            return Optional.empty();
        }
        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " caps failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }

        final Object tagObj = invoke(serializeCaps, nmsPlayer);
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
        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " persistent failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }

        final Object tagObj = invoke(getPersistentData, nmsPlayer);
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
            serializeCaps = findMethod(serverPlayerClass, "serializeCaps", 0);
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
            deserializeCaps = findMethod(serverPlayerClass, "deserializeCaps", 1);
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
            getPersistentData = findMethod(serverPlayerClass, "getPersistentData", 0);
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
            syncPlayerCap = findMethod(capabilityRegistry, "syncPlayerCap", 1);
            final Class<?> manaEvents = Class.forName(MANA_EVENTS_CLASS);
            syncMana = findMethod(manaEvents, "syncPlayerEvent", 1);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capability sync methods unavailable")), e);
        }

        try {
            final Class<?> networking = Class.forName(NETWORKING_CLASS);
            sendToPlayerClient = findMethod(networking, "sendToPlayerClient", 2);
            final Class<?> packetClass = Class.forName(PERSISTENT_PACKET_CLASS);
            persistentPacketConstructor = packetClass.getConstructor(compoundTagClass);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "persistent client sync methods unavailable")), e);
        }
    }

    @Nullable
    private Method findTagParser(@NotNull Class<?> parserClass, @NotNull Class<?> compoundTagClass) {
        Method method = findMethod(parserClass, "parseTag", 1);
        if (method != null && compoundTagClass.isAssignableFrom(method.getReturnType())) {
            return method;
        }
        method = findMethod(parserClass, "parse", 1);
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
            return parseTag.invoke(null, snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "parse NBT failed")
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))), e);
            return null;
        }
    }

    @Nullable
    private Object getHandle(@NotNull Player player) {
        try {
            final Method method = findMethod(player.getClass(), "getHandle", 0);
            if (method == null) {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "getHandle missing")
                        + " for " + player.getName()));
                return null;
            }
            return method.invoke(player);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "getHandle failed")
                    + " for " + player.getName()), e);
            return null;
        }
    }

    @Nullable
    private Object invoke(@Nullable Method method, @Nullable Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            if (!method.canAccess(target)) {
                method.setAccessible(true);
            }
            return method.invoke(target, args);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "method invoke failed")
                    + " " + method.getDeclaringClass().getName() + "#" + method.getName()), e);
            return null;
        }
    }

    @Nullable
    private Object invokeStatic(@Nullable Method method, Object... args) {
        return invoke(method, null, args);
    }

    @Nullable
    private Method findMethod(@NotNull Class<?> type, @NotNull String name, int params) {
        final String key = type.getName() + "#" + name + "#" + params;
        if (methodCache.containsKey(key)) {
            return methodCache.get(key);
        }
        Method fallback = null;
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == params) {
                method.setAccessible(true);
                if (fallback == null) {
                    fallback = method;
                }
                if (method.getDeclaringClass() == type) {
                    methodCache.put(key, method);
                    return method;
                }
            }
        }
        methodCache.put(key, fallback);
        return fallback;
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
        invokeStatic(syncPlayerCap, nmsPlayer);
        invokeStatic(syncMana, nmsPlayer);
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
            invokeStatic(sendToPlayerClient, packet, nmsPlayer);
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

    private void cache(@NotNull Map<UUID, CachedNbt> cache, @NotNull UUID uuid, @NotNull String snbt) {
        cache.put(uuid, new CachedNbt(snbt, System.currentTimeMillis()));
    }

    @NotNull
    private Optional<CachedNbt> getCached(@NotNull Map<UUID, CachedNbt> cache, @NotNull UUID uuid) {
        final CachedNbt cached = cache.get(uuid);
        if (cached == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - cached.timestamp() > CAPTURE_CACHE_TTL_MS) {
            cache.remove(uuid);
            return Optional.empty();
        }
        return Optional.of(cached);
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
