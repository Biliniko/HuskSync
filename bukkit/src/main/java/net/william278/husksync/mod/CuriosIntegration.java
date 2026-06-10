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

import net.william278.husksync.BukkitHuskSync;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class CuriosIntegration implements ModIntegration {

    private static final String MOD_ID = "curios";
    private static final String DISPLAY_NAME = "Curios";
    private static final String COSMETIC_SUFFIX = ":cosmetic";
    private static final String NATIVE_PAYLOAD_SLOT_KEY = "__curios_native__";
    private static final String TAG_PARSER_CLASS = "net.minecraft.nbt.TagParser";
    private static final String COMPOUND_TAG_CLASS = "net.minecraft.nbt.CompoundTag";
    private static final String PACKET_DISTRIBUTOR_CLASS = "net.minecraftforge.network.PacketDistributor";
    private static final String NETWORK_HANDLER_CLASS = "top.theillusivec4.curios.common.network.NetworkHandler";
    private static final String SYNC_CURIOS_PACKET_CLASS =
            "top.theillusivec4.curios.common.network.server.sync.SPacketSyncCurios";
    private static final String CURIOS_MENU_CLASS = "top.theillusivec4.curios.api.type.ICuriosMenu";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BRIGHT_CYAN = "\u001B[96m";
    private static final String ANSI_BRIGHT_YELLOW = "\u001B[93m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    private static final int APPLY_RETRY_ATTEMPTS = 8;
    private static final long APPLY_RETRY_DELAY_TICKS = 5L;

    private final BukkitHuskSync plugin;
    private final ModDataManager manager;
    private final ReflectiveModSupport reflection;

    private boolean resolved = false;
    private boolean available = false;
    private Method getCuriosInventory;
    private Method parseTag;
    private Method packetDistributorWith;
    private Constructor<?> syncCuriosPacketConstructor;
    private Object packetDistributorPlayer;
    private Class<?> networkHandlerClass;
    private Class<?> curiosMenuClass;
    private Object curiosInventoryCapability;

    private enum NativeApplyResult {
        APPLIED,
        FALLBACK
    }

    public CuriosIntegration(@NotNull BukkitHuskSync plugin, @NotNull ModDataManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.reflection = new ReflectiveModSupport(plugin, "Curios");
    }

    @NotNull
    @Override
    public String id() {
        return MOD_ID;
    }

    @NotNull
    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean isAvailable() {
        resolve();
        return available;
    }

    @NotNull
    @Override
    public List<ModSlotData> capture(@NotNull Player player) {
        final Object curiosHandler = getCuriosHandler(player);
        if (curiosHandler == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "handler missing")
                    + " (capture skipped) for " + player.getName()));
            return List.of();
        }

        final Map<?, ?> curios = castMap(reflection.invoke(curiosHandler, "getCurios"));
        if (curios == null || curios.isEmpty()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "no curios handlers")
                    + " (capture skipped) for " + player.getName()));
            return List.of();
        }

        final String captureKeys = curios.keySet().stream()
                .filter(String.class::isInstance)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));
        plugin.debug(formatDebug("capturing " + color(ANSI_BRIGHT_YELLOW, String.valueOf(curios.size()))
                + " slot types for " + player.getName() + ": "
                + color(ANSI_BRIGHT_CYAN, captureKeys.isBlank() ? "<none>" : captureKeys)));

        final List<ModSlotData> data = new ArrayList<>();
        captureNativePayload(curiosHandler, player).ifPresent(nativeNbt ->
                data.add(new ModSlotData(NATIVE_PAYLOAD_SLOT_KEY, -1, null, null, null, nativeNbt))
        );
        final int slotTypes = curios.size();
        final int[] slotEntries = {0};
        final int[] cosmeticEntries = {0};
        final int[] skippedNoStacks = {0};
        final int[] skippedNoSlots = {0};
        curios.forEach((key, value) -> {
            if (!(key instanceof String slotKey)) {
                return;
            }
            final Object stacksHandler = value;
            final Object stacks = reflection.invoke(stacksHandler, "getStacks");
            if (stacks == null) {
                skippedNoStacks[0]++;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing stacks")
                        + " for " + slotKey + " (capture)"));
                return;
            }
            final Integer slots = (Integer) reflection.invoke(stacks, "getSlots");
            if (slots == null || slots <= 0) {
                skippedNoSlots[0]++;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "no slots")
                        + " for " + slotKey + " (capture)"));
                return;
            }
            for (int i = 0; i < slots; i++) {
                final Object nmsItem = reflection.invoke(stacks, "getStackInSlot", i);
                final ItemStack bukkitItem = manager.toBukkitItem(nmsItem);
                data.add(new ModSlotData(slotKey, i, manager.serializeItem(bukkitItem), null, null));
                slotEntries[0]++;
            }

            final Boolean hasCosmetic = (Boolean) reflection.invoke(stacksHandler, "hasCosmetic");
            if (Boolean.TRUE.equals(hasCosmetic)) {
                final Object cosmeticStacks = reflection.invoke(stacksHandler, "getCosmeticStacks");
                if (cosmeticStacks == null) {
                    skippedNoStacks[0]++;
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing cosmetic stacks")
                            + " for " + slotKey + " (capture)"));
                    return;
                }
                final Integer cosmeticSlots = (Integer) reflection.invoke(cosmeticStacks, "getSlots");
                if (cosmeticSlots == null || cosmeticSlots <= 0) {
                    skippedNoSlots[0]++;
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "no cosmetic slots")
                            + " for " + slotKey + " (capture)"));
                    return;
                }
                for (int i = 0; i < cosmeticSlots; i++) {
                    final Object nmsItem = reflection.invoke(cosmeticStacks, "getStackInSlot", i);
                    final ItemStack bukkitItem = manager.toBukkitItem(nmsItem);
                    data.add(new ModSlotData(slotKey + COSMETIC_SUFFIX, i,
                            manager.serializeItem(bukkitItem), null, null));
                    slotEntries[0]++;
                    cosmeticEntries[0]++;
                }
            }
        });

        plugin.debug(formatDebug("capture summary for " + player.getName()
                + ": slotTypes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(slotTypes))
                + ", slotEntries=" + color(ANSI_BRIGHT_CYAN, String.valueOf(slotEntries[0]))
                + ", cosmeticEntries=" + color(ANSI_BRIGHT_CYAN, String.valueOf(cosmeticEntries[0]))
                + ", missingStacks=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedNoStacks[0]))
                + ", noSlots=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedNoSlots[0]))));
        if (slotEntries[0] == 0) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture produced empty slot data"))
                    + " for " + player.getName());
        }

        return data;
    }

    @Override
    public boolean apply(@NotNull Player player, @NotNull List<ModSlotData> data) {
        return apply(player, List.copyOf(data), 0);
    }

    private boolean apply(@NotNull Player player, @NotNull List<ModSlotData> data, int attempt) {
        if (data.isEmpty() || !player.isOnline() || plugin.isDisabling()) {
            return false;
        }
        if (attempt > 0 && !plugin.isLocked(player.getUniqueId())) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply retry skipped")
                    + " for " + player.getName() + " because the player is no longer locked"));
            return false;
        }

        final Object curiosHandler = getCuriosHandler(player);
        if (curiosHandler == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "handler missing")
                    + " (apply skipped) for " + player.getName()));
            scheduleApplyRetry(player, data, attempt, "handler missing");
            return false;
        }

        final Map<?, ?> curios = castMap(reflection.invoke(curiosHandler, "getCurios"));
        if (curios == null || curios.isEmpty()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "no curios handlers")
                    + " (apply skipped) for " + player.getName()));
            scheduleApplyRetry(player, data, attempt, "no curios handlers");
            return false;
        }

        final Optional<String> nativePayload = extractNativePayload(data);
        if (nativePayload.isPresent()) {
            final NativeApplyResult nativeResult = applyNativePayload(player, curiosHandler, nativePayload.get());
            if (nativeResult == NativeApplyResult.APPLIED) {
                return true;
            }
            if (itemSlots(data).isEmpty()) {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "native apply failed")
                        + " and no legacy slot data is available for " + player.getName()));
                return false;
            }
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "falling back to legacy slot apply")
                    + " for " + player.getName()));
        }

        final String availableKeys = curios.keySet().stream()
                .filter(String.class::isInstance)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));

        final List<ModSlotData> itemData = itemSlots(data);
        if (itemData.isEmpty()) {
            return false;
        }
        final Map<String, List<ModSlotData>> bySlot = itemData.stream()
                .collect(Collectors.groupingBy(ModSlotData::slotKey));
        final String incomingKeys = bySlot.keySet().stream()
                .sorted()
                .collect(Collectors.joining(", "));
        plugin.debug(formatDebug("applying " + color(ANSI_BRIGHT_YELLOW, String.valueOf(bySlot.size()))
                + " slot types for " + player.getName() + ": "
                + color(ANSI_BRIGHT_CYAN, incomingKeys.isBlank() ? "<none>" : incomingKeys)
                + " (available: " + color(ANSI_BRIGHT_CYAN,
                availableKeys.isBlank() ? "<none>" : availableKeys) + ")"));
        int applied = 0;
        int skippedMissingKey = 0;
        int skippedNoStacks = 0;
        int skippedOutOfRange = 0;
        boolean retryableFailure = false;
        for (Map.Entry<String, List<ModSlotData>> entry : bySlot.entrySet()) {
            final boolean cosmetic = entry.getKey().endsWith(COSMETIC_SUFFIX);
            final String slotKey = cosmetic
                    ? entry.getKey().substring(0, entry.getKey().length() - COSMETIC_SUFFIX.length())
                    : entry.getKey();
            final Object stacksHandler = curios.get(slotKey);
            if (stacksHandler == null) {
                skippedMissingKey++;
                retryableFailure = true;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing slot key")
                        + " " + slotKey + " for " + player.getName()));
                continue;
            }
            final Object stacks = reflection.invoke(stacksHandler, cosmetic ? "getCosmeticStacks" : "getStacks");
            if (stacks == null) {
                skippedNoStacks++;
                retryableFailure = true;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing stacks")
                        + " for " + slotKey + " (cosmetic=" + cosmetic + ")"));
                continue;
            }
            final Integer slots = (Integer) reflection.invoke(stacks, "getSlots");
            if (slots == null || slots <= 0) {
                skippedNoStacks++;
                retryableFailure = true;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "no slots")
                        + " for " + slotKey + " (cosmetic=" + cosmetic + ")"));
                continue;
            }
            for (ModSlotData slot : entry.getValue()) {
                if (slot.slotIndex() < 0 || slot.slotIndex() >= slots) {
                    skippedOutOfRange++;
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "slot out of range")
                            + " " + slotKey + "#" + slot.slotIndex() + " (max=" + slots + ")"));
                    continue;
                }
                final ItemStack bukkitItem = manager.deserializeItem(slot.itemNbt());
                final Object nmsItem = manager.toNmsItem(bukkitItem);
                reflection.invoke(stacks, "setStackInSlot", slot.slotIndex(),
                        nmsItem == null ? manager.getEmptyNmsItem() : nmsItem);
                applied++;
            }
        }
        plugin.debug(formatDebug("apply summary for " + player.getName()
                + ": applied=" + color(ANSI_BRIGHT_CYAN, String.valueOf(applied))
                + ", missingKeys=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedMissingKey))
                + ", noStacks=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedNoStacks))
                + ", outOfRange=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedOutOfRange))));
        if (retryableFailure) {
            scheduleApplyRetry(player, data, attempt, "slot data unavailable");
        }
        final boolean success = !retryableFailure && skippedOutOfRange == 0;
        if (success) {
            syncClient(player, curiosHandler);
        }
        return success;
    }

    @NotNull
    static Optional<String> extractNativePayload(@NotNull List<ModSlotData> data) {
        return data.stream()
                .map(ModSlotData::nativeNbt)
                .filter(Objects::nonNull)
                .filter(payload -> !payload.isBlank())
                .findFirst();
    }

    @NotNull
    static List<ModSlotData> itemSlots(@NotNull List<ModSlotData> data) {
        return data.stream()
                .filter(slot -> !slot.isMetadata())
                .toList();
    }

    @NotNull
    private Optional<String> captureNativePayload(@NotNull Object curiosHandler, @NotNull Player player) {
        final Optional<Object> tag = writeNativeTag(curiosHandler, player, "capture native");
        if (tag.isEmpty()) {
            return Optional.empty();
        }
        final String snbt = tag.get().toString();
        if (snbt.isBlank() || snbt.equals("{}")) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "native payload empty")
                    + " for " + player.getName()));
            return Optional.empty();
        }
        plugin.debug(formatDebug("captured native payload for " + player.getName()
                + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))));
        return Optional.of(snbt);
    }

    @NotNull
    private NativeApplyResult applyNativePayload(@NotNull Player player, @NotNull Object curiosHandler,
                                                @NotNull String snbt) {
        final Object tag = parseCompoundTag(snbt);
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "native apply failed")
                    + " (parse failed) for " + player.getName()));
            return NativeApplyResult.FALLBACK;
        }

        final Optional<Object> backup = writeNativeTag(curiosHandler, player, "apply backup");
        if (backup.isEmpty()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "native apply failed")
                    + " (backup failed) for " + player.getName()));
            return NativeApplyResult.FALLBACK;
        }

        if (!readNativeTag(curiosHandler, tag, player, "apply native")) {
            restoreNativeTag(curiosHandler, backup.get(), player);
            return NativeApplyResult.FALLBACK;
        }
        syncClient(player, curiosHandler);
        plugin.debug(formatDebug(color(ANSI_BRIGHT_CYAN, "native apply ok")
                + " for " + player.getName()
                + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))));
        return NativeApplyResult.APPLIED;
    }

    @NotNull
    private Optional<Object> writeNativeTag(@NotNull Object curiosHandler, @NotNull Player player,
                                           @NotNull String phase) {
        final Method method = reflection.findMethod(curiosHandler.getClass(), "writeTag", 0);
        if (method == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (writeTag missing) for " + player.getName()));
            return Optional.empty();
        }
        try {
            if (!method.canAccess(curiosHandler)) {
                method.setAccessible(true);
            }
            return Optional.ofNullable(method.invoke(curiosHandler));
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (writeTag failed) for " + player.getName()), e);
            return Optional.empty();
        }
    }

    private boolean readNativeTag(@NotNull Object curiosHandler, @NotNull Object tag, @NotNull Player player,
                                  @NotNull String phase) {
        final Method method = reflection.findMethod(curiosHandler.getClass(), "readTag", 1);
        if (method == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (readTag missing) for " + player.getName()));
            return false;
        }
        try {
            if (!method.canAccess(curiosHandler)) {
                method.setAccessible(true);
            }
            method.invoke(curiosHandler, tag);
            return true;
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (readTag failed) for " + player.getName()), e);
            return false;
        }
    }

    private void restoreNativeTag(@NotNull Object curiosHandler, @NotNull Object backupTag, @NotNull Player player) {
        if (readNativeTag(curiosHandler, backupTag, player, "restore native")) {
            syncClient(player, curiosHandler);
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "native restore ok")
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
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "parse native NBT failed")
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))), e);
            return null;
        }
    }

    private void syncClient(@NotNull Player player, @NotNull Object curiosHandler) {
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            return;
        }
        resetOpenMenu(nmsPlayer, player);

        if (networkHandlerClass == null || syncCuriosPacketConstructor == null
                || packetDistributorPlayer == null || packetDistributorWith == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "client sync unavailable")
                    + " for " + player.getName()));
            return;
        }

        final Map<?, ?> curios = castMap(reflection.invoke(curiosHandler, "getCurios"));
        if (curios == null || curios.isEmpty()) {
            return;
        }

        try {
            final Field instance = networkHandlerClass.getField("INSTANCE");
            final Object network = instance.get(null);
            if (network == null) {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "client sync unavailable")
                        + " (network null) for " + player.getName()));
                return;
            }
            final Object packet = syncCuriosPacketConstructor.newInstance(player.getEntityId(), curios);
            if (!packetDistributorWith.canAccess(packetDistributorPlayer)) {
                packetDistributorWith.setAccessible(true);
            }
            final Supplier<Object> playerSupplier = () -> nmsPlayer;
            final Object target = packetDistributorWith.invoke(packetDistributorPlayer, playerSupplier);
            final Method send = reflection.findMethod(network.getClass(), "send", 2);
            if (send == null) {
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "client sync unavailable")
                        + " (send missing) for " + player.getName()));
                return;
            }
            if (!send.canAccess(network)) {
                send.setAccessible(true);
            }
            send.invoke(network, target, packet);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "client sync failed")
                    + " for " + player.getName()), e);
        }
    }

    private void resetOpenMenu(@NotNull Object nmsPlayer, @NotNull Player player) {
        if (curiosMenuClass == null) {
            return;
        }
        final Object menu = readField(nmsPlayer, "containerMenu");
        if (menu == null || !curiosMenuClass.isInstance(menu)) {
            return;
        }
        final Method resetSlots = reflection.findMethod(menu.getClass(), "resetSlots", 0);
        if (resetSlots == null) {
            return;
        }
        try {
            if (!resetSlots.canAccess(menu)) {
                resetSlots.setAccessible(true);
            }
            resetSlots.invoke(menu);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "menu reset failed")
                    + " for " + player.getName()), e);
        }
    }

    private void scheduleApplyRetry(@NotNull Player player, @NotNull List<ModSlotData> data, int attempt,
                                    @NotNull String reason) {
        if (attempt >= APPLY_RETRY_ATTEMPTS || !player.isOnline() || plugin.isDisabling()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply retry exhausted")
                    + " for " + player.getName() + " reason=" + reason + " attempts=" + attempt));
            return;
        }
        if (!plugin.isLocked(player.getUniqueId())) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "apply retry not scheduled")
                    + " for " + player.getName() + " reason=" + reason
                    + " because the player is no longer locked"));
            return;
        }

        final int nextAttempt = attempt + 1;
        plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "scheduling apply retry")
                + " for " + player.getName() + " reason=" + reason + " attempt=" + nextAttempt));
        plugin.runSyncDelayed(() -> {
            if (apply(player, data, nextAttempt)) {
                manager.confirmApplied(player.getUniqueId(), id(), data);
            }
        }, null, APPLY_RETRY_DELAY_TICKS);
    }

    @NotNull
    private String formatDebug(@NotNull String message) {
        return ANSI_BRIGHT_MAGENTA + "[Curios]" + ANSI_RESET + " " + message;
    }

    @NotNull
    private String color(@NotNull String color, @NotNull String message) {
        return color + message + ANSI_RESET;
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            final Class<?> modList = Class.forName("net.minecraftforge.fml.ModList");
            final Object modListInstance = modList.getMethod("get").invoke(null);
            final boolean loaded = (boolean) modList.getMethod("isLoaded", String.class)
                    .invoke(modListInstance, MOD_ID);
            if (!loaded) {
                available = false;
                return;
            }

            final Class<?> curiosApi = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            getCuriosInventory = reflection.findMethod(curiosApi, "getCuriosInventory", 1);
            try {
                final Class<?> curiosCapability = Class.forName("top.theillusivec4.curios.api.CuriosCapability");
                curiosInventoryCapability = curiosCapability.getField("INVENTORY").get(null);
            } catch (Throwable e) {
                plugin.debug("Curios capability access not available", e);
            }
            resolveNativeNbt();
            resolveClientSync();
            available = getCuriosInventory != null || curiosInventoryCapability != null;
        } catch (Throwable e) {
            available = false;
            plugin.debug("Curios integration not available", e);
        }
    }

    private void resolveNativeNbt() {
        try {
            final Class<?> parserClass = Class.forName(TAG_PARSER_CLASS);
            final Class<?> compoundTagClass = Class.forName(COMPOUND_TAG_CLASS);
            parseTag = findTagParser(parserClass, compoundTagClass);
            plugin.debug(formatDebug("native NBT parseTag="
                    + color(ANSI_BRIGHT_CYAN, String.valueOf(parseTag != null))));
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "native NBT unavailable")), e);
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

    private void resolveClientSync() {
        try {
            networkHandlerClass = Class.forName(NETWORK_HANDLER_CLASS);
            final Class<?> syncCuriosPacket = Class.forName(SYNC_CURIOS_PACKET_CLASS);
            syncCuriosPacketConstructor = syncCuriosPacket.getConstructor(int.class, Map.class);
            syncCuriosPacketConstructor.setAccessible(true);
            final Class<?> packetDistributor = Class.forName(PACKET_DISTRIBUTOR_CLASS);
            packetDistributorPlayer = packetDistributor.getField("PLAYER").get(null);
            if (packetDistributorPlayer != null) {
                packetDistributorWith = reflection.findMethod(packetDistributorPlayer.getClass(), "with", 1);
            }
            curiosMenuClass = Class.forName(CURIOS_MENU_CLASS);
            plugin.debug(formatDebug("client sync packet="
                    + color(ANSI_BRIGHT_CYAN, String.valueOf(syncCuriosPacketConstructor != null))
                    + ", distributor=" + color(ANSI_BRIGHT_CYAN, String.valueOf(packetDistributorWith != null))
                    + ", menu=" + color(ANSI_BRIGHT_CYAN, String.valueOf(curiosMenuClass != null))));
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "client sync methods unavailable")), e);
        }
    }

    @Nullable
    private Object getCuriosHandler(@NotNull Player player) {
        if (!isAvailable()) {
            return null;
        }
        try {
            final Object handle = reflection.getHandle(player);
            Object handler = null;
            if (getCuriosInventory != null) {
                final Object optional = reflection.invokeStatic(getCuriosInventory, handle);
                handler = reflection.resolveOptional(optional);
            }
            if (handler == null && curiosInventoryCapability != null) {
                final Object capability = reflection.invoke(handle, "getCapability", curiosInventoryCapability);
                handler = reflection.resolveOptional(capability);
                if (handler == null) {
                    final Object sidedCapability = reflection.invoke(handle, "getCapability",
                            curiosInventoryCapability, null);
                    handler = reflection.resolveOptional(sidedCapability);
                }
            }
            return handler;
        } catch (Throwable e) {
            plugin.debug("Failed to access Curios handler", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private Map<?, ?> castMap(@Nullable Object map) {
        if (map instanceof Map<?, ?> m) {
            return m;
        }
        return null;
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

}
