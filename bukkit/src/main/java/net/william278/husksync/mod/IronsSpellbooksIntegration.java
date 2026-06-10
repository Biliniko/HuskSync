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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Built-in Iron's Spells 'n Spellbooks player magic data integration.
 */
public class IronsSpellbooksIntegration {

    private static final String MOD_ID = "irons_spellbooks";
    private static final String MOD_LIST_CLASS = "net.minecraftforge.fml.ModList";
    private static final String TAG_PARSER_CLASS = "net.minecraft.nbt.TagParser";
    private static final String COMPOUND_TAG_CLASS = "net.minecraft.nbt.CompoundTag";
    private static final String MAGIC_DATA_CLASS = "io.redspace.ironsspellbooks.api.magic.MagicData";
    private static final String RECAST_RESULT_CLASS = "io.redspace.ironsspellbooks.capabilities.magic.RecastResult";
    private static final String PACKET_DISTRIBUTOR_CLASS = "io.redspace.ironsspellbooks.setup.PacketDistributor";
    private static final String SYNC_MANA_PACKET_CLASS = "io.redspace.ironsspellbooks.network.SyncManaPacket";

    private static final String MANA = "mana";
    private static final String COOLDOWNS = "cooldowns";
    private static final String RECASTS = "recasts";
    private static final String IS_CASTING = "isCasting";
    private static final String CASTING_SPELL_ID = "castingSpellId";
    private static final String CASTING_EQUIPMENT_SLOT = "castingEquipmentSlot";
    private static final String CASTING_SPELL_LEVEL = "castingSpellLevel";
    private static final String HEART_STOP_ACCUMULATED_DAMAGE = "heartStopAccumulatedDamage";
    private static final String EVASION_HITS_REMAINING = "evasionHitsRemaining";
    private static final String LEARNED_SPELLS = "learnedSpells";
    private static final String SPELL_SELECTION = "spellSelection";
    private static final String SLOT = "slot";
    private static final String INDEX = "index";
    private static final String LAST_SLOT = "lastSlot";
    private static final String LAST_INDEX = "lastIndex";

    private static final Set<String> SAFE_KEYS = Set.of(
            MANA,
            COOLDOWNS,
            LEARNED_SPELLS,
            SPELL_SELECTION
    );

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    private static final String ANSI_BRIGHT_CYAN = "\u001B[96m";
    private static final String ANSI_BRIGHT_YELLOW = "\u001B[93m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_BRIGHT_GREEN = "\u001B[92m";

    private final BukkitHuskSync plugin;
    private final ReflectiveModSupport reflection;
    private final ModSyncCache<String> magicDataCache = new ModSyncCache<>();

    private boolean resolved;
    private boolean available;

    @Nullable
    private Method parseTag;
    @Nullable
    private Method getPlayerMagicData;
    @Nullable
    private Method saveNbtData;
    @Nullable
    private Method loadNbtData;
    @Nullable
    private Method sendToPlayer;
    @Nullable
    private Constructor<?> compoundTagConstructor;
    @Nullable
    private Constructor<?> magicDataConstructor;
    @Nullable
    private Constructor<?> syncManaPacketConstructor;
    @Nullable
    private Object recastCommandResult;

    public IronsSpellbooksIntegration(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        this.reflection = new ReflectiveModSupport(plugin, "Iron's Spellbooks");
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

    public void cachePlayerData(@NotNull Player player) {
        if (!isAvailable()) {
            return;
        }
        captureFromHandle(player, "pre-cache").ifPresent(snbt -> cache(player.getUniqueId(), snbt));
    }

    @NotNull
    public Optional<String> captureMagicData(@NotNull Player player) {
        if (!isAvailable()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture skipped")
                    + " (integration unavailable) for " + player.getName()));
            return captureFallback(player, "integration unavailable");
        }
        if (!player.isOnline()) {
            return captureFallback(player, "player offline");
        }

        final Optional<String> direct = captureFromHandle(player, "capture");
        final CaptureResult<String> result = magicDataCache.capture(
                player.getUniqueId(), direct, "direct capture failed"
        );
        logCaptureResult(player, result);
        return result.dataOptional();
    }

    @NotNull
    public ApplyResult applyMagicData(@NotNull Player player, @Nullable String snbt) {
        if (snbt == null || snbt.isBlank()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply skipped")
                    + " (empty NBT) for " + player.getName()));
            return ApplyResult.skipped("empty nbt");
        }

        final String sanitized;
        try {
            sanitized = sanitizeMagicDataSnbt(snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply failed")
                    + " (sanitize failed) for " + player.getName()), e);
            return ApplyResult.failed("sanitize failed");
        }

        magicDataCache.storePending(player.getUniqueId(), sanitized);
        if (!isAvailable()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply skipped")
                    + " (integration unavailable) for " + player.getName()));
            return ApplyResult.pending("integration unavailable");
        }

        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply failed")
                    + " (nms player null) for " + player.getName()));
            return ApplyResult.pending("nms player null");
        }

        final Object magicData = getMagicData(nmsPlayer, player, "apply");
        if (magicData == null) {
            return ApplyResult.pending("magic data unavailable");
        }

        final Object tag = parseCompoundTag(sanitized);
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply failed")
                    + " (parseTag null) for " + player.getName()));
            return ApplyResult.failed("parseTag null");
        }

        final Object registryAccess = getRegistryAccess(nmsPlayer);
        if (!validateMagicDataLoad(tag, registryAccess, player)) {
            return ApplyResult.failed("loadNBTData validation failed");
        }
        final Optional<String> backup = captureRawMagicData(magicData, registryAccess, player);
        if (backup.isEmpty()) {
            return ApplyResult.failed("backup failed");
        }

        clearAuthoritativeState(magicData);
        if (!loadMagicData(magicData, tag, registryAccess, player, "apply")) {
            restoreMagicData(nmsPlayer, magicData, backup.get(), registryAccess, player);
            return ApplyResult.failed("loadNBTData failed");
        }
        syncClient(nmsPlayer, magicData);
        magicDataCache.confirmPending(player.getUniqueId(), sanitized);

        plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "apply ok")
                + " for " + color(ANSI_BRIGHT_CYAN, player.getName())
                + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sanitized.length()))));
        return ApplyResult.success("applied");
    }

    @NotNull
    private Optional<String> captureFromHandle(@NotNull Player player, @NotNull String phase) {
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }

        final Object magicData = getMagicData(nmsPlayer, player, phase);
        if (magicData == null) {
            return Optional.empty();
        }

        final Object tag = createCompoundTag();
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (compound tag null) for " + player.getName()));
            return Optional.empty();
        }

        if (!saveMagicData(magicData, tag, getRegistryAccess(nmsPlayer), player, phase)) {
            return Optional.empty();
        }
        try {
            final ReadWriteNBT wrapped = NBT.wrapNMSTag(tag);
            if (wrapped.getKeys().isEmpty()) {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                        + " (magic data saved empty) for " + player.getName()));
                return Optional.empty();
            }
            final int before = wrapped.getKeys().size();
            sanitizeMagicData(wrapped);
            if (!hasRequiredMagicData(wrapped)) {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                        + " (missing required magic data) for " + player.getName()));
                return Optional.empty();
            }
            final Set<String> kept = new HashSet<>(wrapped.getKeys());
            final String sanitized = wrapped.toString();
            plugin.debug(formatDebug(phase + " ok for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(sanitized.length()))
                    + ", keys=" + color(ANSI_BRIGHT_CYAN, before + "->" + kept.size())
                    + ", keptKeys=" + color(ANSI_BRIGHT_CYAN, formatKeys(kept))));
            return Optional.of(sanitized);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (wrap/filter failed) for " + player.getName()), e);
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
            compoundTagConstructor = compoundTagClass.getDeclaredConstructor();
            compoundTagConstructor.setAccessible(true);

            final Class<?> magicDataClass = Class.forName(MAGIC_DATA_CLASS);
            magicDataConstructor = magicDataClass.getDeclaredConstructor();
            magicDataConstructor.setAccessible(true);
            getPlayerMagicData = reflection.findMethodPreferStatic(magicDataClass, "getPlayerMagicData", 1);
            saveNbtData = reflection.findMethod(magicDataClass, "saveNBTData", 2);
            loadNbtData = reflection.findMethod(magicDataClass, "loadNBTData", 2);

            resolveRecastResult();
            resolveSyncMethods(magicDataClass);

            available = parseTag != null && compoundTagConstructor != null
                    && magicDataConstructor != null && getPlayerMagicData != null
                    && saveNbtData != null && loadNbtData != null;
            plugin.debug(formatDebug("resolved parseTag=" + color(ANSI_BRIGHT_CYAN, String.valueOf(parseTag != null))
                    + ", compoundTag=" + color(ANSI_BRIGHT_CYAN, String.valueOf(compoundTagConstructor != null))
                    + ", magicData=" + color(ANSI_BRIGHT_CYAN, String.valueOf(magicDataConstructor != null))
                    + ", getPlayerMagicData=" + color(ANSI_BRIGHT_CYAN, String.valueOf(getPlayerMagicData != null))
                    + ", saveNBTData=" + color(ANSI_BRIGHT_CYAN, String.valueOf(saveNbtData != null))
                    + ", loadNBTData=" + color(ANSI_BRIGHT_CYAN, String.valueOf(loadNbtData != null))
                    + ", recastCommand=" + color(ANSI_BRIGHT_CYAN, String.valueOf(recastCommandResult != null))
                    + ", manaSync=" + color(ANSI_BRIGHT_CYAN, String.valueOf(syncManaPacketConstructor != null
                    && sendToPlayer != null))
                    + ", available=" + color(ANSI_BRIGHT_CYAN, String.valueOf(available))));
        } catch (Throwable e) {
            available = false;
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "integration not available")), e);
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

    private void resolveRecastResult() {
        try {
            final Class<?> recastResult = Class.forName(RECAST_RESULT_CLASS);
            @SuppressWarnings({"rawtypes", "unchecked"})
            final Object command = Enum.valueOf((Class<Enum>) recastResult.asSubclass(Enum.class), "COMMAND");
            recastCommandResult = command;
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "recast command result unavailable")), e);
        }
    }

    private void resolveSyncMethods(@NotNull Class<?> magicDataClass) {
        try {
            final Class<?> packetDistributor = Class.forName(PACKET_DISTRIBUTOR_CLASS);
            sendToPlayer = reflection.findMethodPreferStatic(packetDistributor, "sendToPlayer", 2);
            final Class<?> syncManaPacket = Class.forName(SYNC_MANA_PACKET_CLASS);
            syncManaPacketConstructor = syncManaPacket.getConstructor(magicDataClass);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "client sync methods unavailable")), e);
        }
    }

    @Nullable
    private Object createMagicData() {
        if (magicDataConstructor == null) {
            return null;
        }
        try {
            return magicDataConstructor.newInstance();
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "magic data create failed")), e);
            return null;
        }
    }

    @Nullable
    private Object createCompoundTag() {
        if (compoundTagConstructor == null) {
            return null;
        }
        try {
            return compoundTagConstructor.newInstance();
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "compound tag create failed")), e);
            return null;
        }
    }

    @NotNull
    private Optional<String> captureRawMagicData(@NotNull Object magicData, @Nullable Object registryAccess,
                                                 @NotNull Player player) {
        final Object tag = createCompoundTag();
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply backup failed")
                    + " (compound tag null) for " + player.getName()));
            return Optional.empty();
        }
        if (!saveMagicData(magicData, tag, registryAccess, player, "apply backup")) {
            return Optional.empty();
        }
        return Optional.of(tag.toString());
    }

    private boolean validateMagicDataLoad(@NotNull Object tag, @Nullable Object registryAccess, @NotNull Player player) {
        final Object probe = createMagicData();
        if (probe == null) {
            return false;
        }
        return loadMagicData(probe, tag, registryAccess, player, "validate");
    }

    private void restoreMagicData(@NotNull Object nmsPlayer, @NotNull Object magicData, @NotNull String backupSnbt,
                                  @Nullable Object registryAccess, @NotNull Player player) {
        final Object backupTag = parseCompoundTag(backupSnbt);
        if (backupTag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "restore backup failed")
                    + " (parseTag null) for " + player.getName()));
            return;
        }
        clearAuthoritativeState(magicData);
        if (loadMagicData(magicData, backupTag, registryAccess, player, "restore")) {
            syncClient(nmsPlayer, magicData);
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "restore backup ok")
                    + " for " + player.getName()));
        }
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

    private boolean saveMagicData(@NotNull Object magicData, @NotNull Object tag, @Nullable Object registryAccess,
                                  @NotNull Player player, @NotNull String phase) {
        if (saveNbtData == null) {
            return false;
        }
        try {
            if (!saveNbtData.canAccess(magicData)) {
                saveNbtData.setAccessible(true);
            }
            saveNbtData.invoke(magicData, tag, registryAccess);
            return true;
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (saveNBTData failed) for " + player.getName()), e);
            return false;
        }
    }

    private boolean loadMagicData(@NotNull Object magicData, @NotNull Object tag, @Nullable Object registryAccess,
                                  @NotNull Player player, @NotNull String phase) {
        if (loadNbtData == null) {
            return false;
        }
        try {
            if (!loadNbtData.canAccess(magicData)) {
                loadNbtData.setAccessible(true);
            }
            loadNbtData.invoke(magicData, tag, registryAccess);
            return true;
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (loadNBTData failed) for " + player.getName()), e);
            return false;
        }
    }

    @Nullable
    private Object getMagicData(@NotNull Object nmsPlayer, @NotNull Player player, @NotNull String phase) {
        Object magicData = reflection.invoke(nmsPlayer, "irons_spellbooks$getMagicData");
        if (magicData == null) {
            magicData = reflection.invokeStaticPreferred(getPlayerMagicData, nmsPlayer);
        }
        if (magicData == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (magic data null) for " + player.getName()));
        }
        return magicData;
    }

    @Nullable
    private Object getRegistryAccess(@NotNull Object nmsPlayer) {
        Object registryAccess = reflection.invoke(nmsPlayer, "registryAccess");
        if (registryAccess != null) {
            return registryAccess;
        }

        Object level = reflection.invoke(nmsPlayer, "level");
        if (level == null) {
            level = readField(nmsPlayer, "level");
        }
        return level == null ? null : reflection.invoke(level, "registryAccess");
    }

    @Nullable
    private Object readField(@NotNull Object target, @NotNull String name) {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                final Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Search superclass
            } catch (Throwable e) {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "field read failed")
                        + " " + current.getName() + "#" + name), e);
                return null;
            }
        }
        return null;
    }

    private void clearAuthoritativeState(@NotNull Object magicData) {
        reflection.invoke(magicData, "resetCastingState");

        final Object syncedData = reflection.invoke(magicData, "getSyncedData");
        reflection.invoke(syncedData, "forgetAllSpells");

        final Object cooldowns = reflection.invoke(magicData, "getPlayerCooldowns");
        reflection.invoke(cooldowns, "clearCooldowns");

        final Object recasts = reflection.invoke(magicData, "getPlayerRecasts");
        if (recastCommandResult != null) {
            reflection.invoke(recasts, "removeAll", recastCommandResult);
        } else {
            reflection.invoke(recasts, "syncAllToPlayer");
        }
    }

    private void syncClient(@NotNull Object nmsPlayer, @NotNull Object magicData) {
        final Object cooldowns = reflection.invoke(magicData, "getPlayerCooldowns");
        reflection.invoke(cooldowns, "syncToPlayer", nmsPlayer);

        final Object recasts = reflection.invoke(magicData, "getPlayerRecasts");
        reflection.invoke(recasts, "syncAllToPlayer");

        final Object syncedData = reflection.invoke(magicData, "getSyncedData");
        reflection.invoke(syncedData, "syncToPlayer", nmsPlayer);

        if (sendToPlayer == null || syncManaPacketConstructor == null) {
            return;
        }
        try {
            final Object packet = syncManaPacketConstructor.newInstance(magicData);
            reflection.invokeStatic(sendToPlayer, nmsPlayer, packet);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "mana client sync failed")), e);
        }
    }

    @NotNull
    static String sanitizeMagicDataSnbt(@NotNull String snbt) {
        final ReadWriteNBT tag = NBT.parseNBT(snbt);
        sanitizeMagicData(tag);
        if (!hasRequiredMagicData(tag)) {
            throw new IllegalArgumentException("missing required Iron's Spellbooks magic data");
        }
        return tag.toString();
    }

    static void sanitizeMagicData(@NotNull ReadWriteNBT tag) {
        for (String key : new HashSet<>(tag.getKeys())) {
            if (!SAFE_KEYS.contains(key)) {
                tag.removeKey(key);
            }
        }

        if (!hasRequiredMagicData(tag)) {
            return;
        }

        tag.removeKey(RECASTS);
        tag.removeKey(HEART_STOP_ACCUMULATED_DAMAGE);
        tag.removeKey(EVASION_HITS_REMAINING);
        tag.setBoolean(IS_CASTING, false);
        tag.setString(CASTING_SPELL_ID, "");
        tag.setString(CASTING_EQUIPMENT_SLOT, "");
        tag.setInteger(CASTING_SPELL_LEVEL, 0);

        final ReadWriteNBT spellSelection = tag.getOrCreateCompound(SPELL_SELECTION);
        if (!spellSelection.hasTag(SLOT)) {
            spellSelection.setString(SLOT, "");
        }
        if (!spellSelection.hasTag(INDEX)) {
            spellSelection.setInteger(INDEX, -1);
        }
        if (!spellSelection.hasTag(LAST_SLOT)) {
            spellSelection.setString(LAST_SLOT, "");
        }
        if (!spellSelection.hasTag(LAST_INDEX)) {
            spellSelection.setInteger(LAST_INDEX, -1);
        }
    }

    static boolean hasRequiredMagicData(@NotNull ReadWriteNBT tag) {
        return tag.hasTag(MANA) && tag.hasTag(SPELL_SELECTION);
    }

    @NotNull
    private Optional<String> captureFallback(@NotNull Player player, @NotNull String reason) {
        final CaptureResult<String> result = magicDataCache.fallback(player.getUniqueId(), reason);
        logCaptureResult(player, result);
        return result.dataOptional();
    }

    private void logCaptureResult(@NotNull Player player, @NotNull CaptureResult<String> result) {
        switch (result.status()) {
            case CAPTURED -> plugin.debug(formatDebug(color(ANSI_BRIGHT_GREEN, "capture ok")
                    + " for " + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case FALLBACK_PENDING -> plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capture using pending")
                    + " for " + player.getName()
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case FALLBACK_CACHED -> plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capture using cached")
                    + " for " + player.getName()
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case FALLBACK_TRUSTED -> plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capture using trusted")
                    + " for " + player.getName()
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(result.data().length()))
                    + " (" + result.reason() + ")"));
            case UNAVAILABLE -> plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture failed")
                    + " (no fallback data) for " + player.getName()
                    + " (" + result.reason() + ")"));
        }
    }

    private void cache(@NotNull UUID uuid, @NotNull String snbt) {
        if (!snbt.isBlank()) {
            magicDataCache.storeTrusted(uuid, snbt);
        }
    }

    @NotNull
    private String formatDebug(@NotNull String message) {
        return ANSI_BRIGHT_MAGENTA + "[Iron's Spellbooks]" + ANSI_RESET + " " + message;
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
