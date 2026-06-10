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
import net.william278.husksync.data.BukkitData;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.mod.ModDataView;
import net.william278.husksync.mod.ModDataProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ModDataManager implements ModDataProvider {

    private static final Comparator<ModSlotData> SLOT_ORDER = Comparator.comparing(ModSlotData::slotKey)
            .thenComparingInt(ModSlotData::slotIndex);

    private final BukkitHuskSync plugin;
    private final List<ModIntegration> integrations;
    private final Map<String, ModSyncCache<List<ModSlotData>>> integrationCaches = new ConcurrentHashMap<>();

    @Nullable
    private final Method asBukkitCopy;
    @Nullable
    private final Method asNmsCopy;
    @Nullable
    private final Object emptyNmsItem;

    public ModDataManager(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        this.integrations = List.of(
                new CuriosIntegration(plugin, this),
                new CosmeticArmorIntegration(plugin, this)
        );
        Method bukkitCopy = null;
        Method nmsCopy = null;
        Object empty = null;
        try {
            final String packageName = Bukkit.getServer().getClass().getPackage().getName();
            final String version = packageName.substring(packageName.lastIndexOf('.') + 1);
            final Class<?> craftItemStack = Class.forName(
                    "org.bukkit.craftbukkit.%s.inventory.CraftItemStack".formatted(version)
            );
            final Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            bukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
            nmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            empty = nmsCopy.invoke(null, new ItemStack(Material.AIR));
        } catch (Throwable e) {
            plugin.log(Level.WARNING, "Failed to initialize CraftItemStack reflection for mod data", e);
        }
        this.asBukkitCopy = bukkitCopy;
        this.asNmsCopy = nmsCopy;
        this.emptyNmsItem = empty;
    }

    @NotNull
    private List<ModIntegration> getAvailableIntegrations() {
        return integrations.stream()
                .filter(ModIntegration::isAvailable)
                .filter(integration -> !plugin.getSettings().getSynchronization()
                        .isModIntegrationDisabled(integration.id()))
                .toList();
    }

    @Override
    public boolean hasAvailableIntegrations() {
        return !getAvailableIntegrations().isEmpty();
    }

    @NotNull
    @Override
    public Set<String> getAvailableIntegrationIds() {
        return getAvailableIntegrations().stream().map(ModIntegration::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    @NotNull
    private Optional<ModIntegration> getIntegration(@NotNull String id) {
        return getAvailableIntegrations().stream()
                .filter(integration -> integration.id().equalsIgnoreCase(id))
                .findFirst();
    }

    @NotNull
    private ModSyncCache<List<ModSlotData>> getCache(@NotNull String id) {
        return integrationCaches.computeIfAbsent(id.toLowerCase(Locale.ENGLISH), ignored -> new ModSyncCache<>());
    }

    public void cachePlayerModData(@NotNull Player player) {
        final UUID uuid = player.getUniqueId();
        for (ModIntegration integration : getAvailableIntegrations()) {
            final String id = integration.id();
            try {
                final CaptureResult<List<ModSlotData>> result = getCache(id).capture(
                        uuid, normalizeSlots(integration.capture(player)), "pre-cache empty"
                );
                logCaptureResult(id, result);
            } catch (Throwable e) {
                plugin.debug("Failed to pre-cache mod data for integration: " + id, e);
            }
        }
    }

    private void markApplyPending(@NotNull UUID uuid, @NotNull String id, @NotNull List<ModSlotData> slots) {
        normalizeSlots(slots).ifPresent(normalized -> getCache(id).storePending(uuid, normalized));
    }

    void confirmApplied(@NotNull UUID uuid, @NotNull String id, @NotNull List<ModSlotData> slots) {
        normalizeSlots(slots).ifPresent(normalized -> getCache(id).confirmPending(uuid, normalized));
    }

    private boolean putFallbackData(@NotNull Map<String, List<ModSlotData>> data, @NotNull UUID uuid,
                                    @NotNull String id, @NotNull String reason) {
        final CaptureResult<List<ModSlotData>> result = getCache(id).fallback(uuid, reason);
        logCaptureResult(id, result);
        result.dataOptional().ifPresent(slots -> data.put(id, slots));
        return result.hasData();
    }

    @NotNull
    public BukkitData.ModData capture(@NotNull Player player) {
        final Map<String, List<ModSlotData>> data = new HashMap<>();
        final UUID uuid = player.getUniqueId();
        for (ModIntegration integration : getAvailableIntegrations()) {
            final String id = integration.id();
            try {
                final CaptureResult<List<ModSlotData>> result = getCache(id).capture(
                        uuid, normalizeSlots(integration.capture(player)), "capture empty"
                );
                logCaptureResult(id, result);
                result.dataOptional().ifPresent(slots -> data.put(id, slots));
            } catch (Throwable e) {
                plugin.debug("Failed to capture mod data for integration: " + id, e);
                putFallbackData(data, uuid, id, "capture failed");
            }
        }
        return BukkitData.ModData.from(data);
    }

    public void apply(@NotNull Player player, @NotNull BukkitData.ModData data) {
        if (data.getIntegrations() == null || data.getIntegrations().isEmpty()) {
            return;
        }
        data.getIntegrations().forEach((id, slots) -> getIntegration(id).ifPresent(integration -> {
            if (slots == null || slots.isEmpty()) {
                return;
            }
            try {
                final UUID uuid = player.getUniqueId();
                final Optional<List<ModSlotData>> normalized = normalizeSlots(slots);
                if (normalized.isEmpty()) {
                    return;
                }
                markApplyPending(uuid, id, normalized.get());
                if (integration.apply(player, normalized.get())) {
                    confirmApplied(uuid, id, normalized.get());
                } else {
                    plugin.debug("Mod data apply pending for integration: " + id);
                }
            } catch (Throwable e) {
                plugin.debug("Failed to apply mod data for integration: " + id, e);
            }
        }));
    }

    @NotNull
    @Override
    public Optional<ModDataView> getModDataView(@NotNull String type, @NotNull DataSnapshot.Unpacked snapshot) {
        final ModIntegration integration = getIntegration(type).orElse(null);
        if (integration == null) {
            return Optional.empty();
        }

        final Data modData = snapshot.getData().get(Identifier.MOD_DATA);
        if (!(modData instanceof BukkitData.ModData bukkitModData)) {
            return Optional.empty();
        }

        if (bukkitModData.getIntegrations() == null) {
            return Optional.empty();
        }
        final List<ModSlotData> slots = bukkitModData.getIntegrations().get(integration.id());
        if (slots == null || slots.isEmpty()) {
            return Optional.empty();
        }

        final List<ModSlotData> ordered = orderedSlots(slots);
        final ItemStack[] contents = new ItemStack[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            contents[i] = deserializeItem(ordered.get(i).itemNbt());
        }

        return Optional.of(new ModDataView(
                integration.displayName(),
                BukkitData.Items.ItemArray.adapt(contents),
                contents.length
        ));
    }

    @Override
    public void updateModData(@NotNull DataSnapshot.Unpacked snapshot, @NotNull String type,
                              @NotNull Data.Items.Items items) {
        final ModIntegration integration = getIntegration(type).orElse(null);
        if (integration == null) {
            return;
        }
        final Map<Identifier, Data> dataMap = snapshot.getData();
        final BukkitData.ModData modData = dataMap.get(Identifier.MOD_DATA) instanceof BukkitData.ModData existing
                ? existing : BukkitData.ModData.from(new HashMap<>());
        if (modData.getIntegrations() == null) {
            modData.setIntegrations(new HashMap<>());
        }
        final String integrationId = integration.id();
        final List<ModSlotData> slots = modData.getIntegrations().get(integrationId);
        if (slots == null || slots.isEmpty()) {
            return;
        }

        final ItemStack[] contents = ((BukkitData.Items) items).getContents();
        final List<ModSlotData> ordered = orderedSlots(slots);
        for (int i = 0; i < ordered.size() && i < contents.length; i++) {
            final ModSlotData slot = ordered.get(i);
            ordered.set(i, new ModSlotData(slot.slotKey(), slot.slotIndex(), serializeItem(contents[i]),
                    slot.skinArmor(), slot.hiddenFlags()));
        }

        modData.getIntegrations().put(integrationId, ordered);
        snapshot.setData(Identifier.MOD_DATA, modData);
    }

    @NotNull
    private List<ModSlotData> orderedSlots(@NotNull List<ModSlotData> slots) {
        return slots.stream().sorted(SLOT_ORDER).collect(Collectors.toCollection(ArrayList::new));
    }

    @NotNull
    private Optional<List<ModSlotData>> normalizeSlots(@Nullable List<ModSlotData> slots) {
        if (slots == null || slots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(orderedSlots(slots)));
    }

    private void logCaptureResult(@NotNull String id, @NotNull CaptureResult<List<ModSlotData>> result) {
        switch (result.status()) {
            case CAPTURED -> plugin.debug("Captured mod data for integration: " + id
                    + " slots=" + result.data().size() + " (" + result.reason() + ")");
            case FALLBACK_PENDING -> plugin.debug("Using pending applied mod data for integration: " + id
                    + " slots=" + result.data().size() + " (" + result.reason() + ")");
            case FALLBACK_CACHED -> plugin.debug("Using cached mod data for integration: " + id
                    + " slots=" + result.data().size() + " (" + result.reason() + ")");
            case FALLBACK_TRUSTED -> plugin.debug("Using last trusted mod data for integration: " + id
                    + " slots=" + result.data().size() + " (" + result.reason() + ")");
            case UNAVAILABLE -> plugin.debug("Mod data capture skipped for integration: " + id
                    + " (" + result.reason() + ")");
        }
    }

    @Nullable
    public ItemStack deserializeItem(@Nullable String itemNbt) {
        if (itemNbt == null || itemNbt.isBlank()) {
            return null;
        }
        try {
            final ReadWriteNBT nbt = NBT.parseNBT(itemNbt);
            return NBT.itemStackFromNBT(nbt);
        } catch (Throwable e) {
            plugin.debug("Failed to deserialize mod item NBT", e);
            return null;
        }
    }

    @Nullable
    public String serializeItem(@Nullable ItemStack item) {
        if (item == null) {
            return null;
        }
        try {
            final ReadWriteNBT nbt = NBT.itemStackToNBT(item);
            return nbt.toString();
        } catch (Throwable e) {
            plugin.debug("Failed to serialize mod item NBT", e);
            return null;
        }
    }

    @Nullable
    public ItemStack toBukkitItem(@Nullable Object nmsItem) {
        if (nmsItem == null || asBukkitCopy == null) {
            return null;
        }
        try {
            return (ItemStack) asBukkitCopy.invoke(null, nmsItem);
        } catch (Throwable e) {
            plugin.debug("Failed to convert NMS item to Bukkit item", e);
            return null;
        }
    }

    @Nullable
    public Object toNmsItem(@Nullable ItemStack item) {
        if (asNmsCopy == null) {
            return null;
        }
        try {
            final ItemStack stack = item == null ? new ItemStack(Material.AIR) : item;
            return asNmsCopy.invoke(null, stack);
        } catch (Throwable e) {
            plugin.debug("Failed to convert Bukkit item to NMS item", e);
            return emptyNmsItem;
        }
    }

    @Nullable
    public Object getEmptyNmsItem() {
        return emptyNmsItem;
    }

}
