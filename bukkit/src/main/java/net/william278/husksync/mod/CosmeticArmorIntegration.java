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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public class CosmeticArmorIntegration implements ModIntegration {

    private static final String MOD_ID = "cosmeticarmorreworked";
    private static final String DISPLAY_NAME = "Cosmetic Armor";
    private static final String SLOT_KEY = "cosmetic_armor";
    private static final int SLOT_COUNT = 4;

    private final BukkitHuskSync plugin;
    private final ModDataManager manager;

    private boolean resolved = false;
    private boolean available = false;
    private Method getCaStacks;
    private Method getSlots;
    private Method getStackInSlot;
    private Method setStackInSlot;
    private Method isSkinArmor;
    private Method setSkinArmor;
    private Method forEachHidden;
    private Method setHidden;

    public CosmeticArmorIntegration(@NotNull BukkitHuskSync plugin, @NotNull ModDataManager manager) {
        this.plugin = plugin;
        this.manager = manager;
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
        final Object stacks = getCosArmorStacks(player);
        if (stacks == null) {
            return List.of();
        }
        final Integer slots = (Integer) invoke(stacks, getSlots);
        final int limit = Math.min(SLOT_COUNT, slots == null || slots <= 0 ? SLOT_COUNT : slots);
        final @Nullable List<String> hiddenFlags = captureHiddenFlags(stacks);
        final List<ModSlotData> data = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            final Object nmsItem = invoke(stacks, getStackInSlot, i);
            final ItemStack bukkitItem = manager.toBukkitItem(nmsItem);
            final Boolean skinArmor = (Boolean) invoke(stacks, isSkinArmor, i);
            final List<String> slotHidden = i == 0 ? hiddenFlags : null;
            data.add(new ModSlotData(SLOT_KEY, i, manager.serializeItem(bukkitItem), skinArmor, slotHidden));
        }
        return data;
    }

    @Override
    public void apply(@NotNull Player player, @NotNull List<ModSlotData> data) {
        final Object stacks = getCosArmorStacks(player);
        if (stacks == null) {
            return;
        }
        final Integer slots = (Integer) invoke(stacks, getSlots);
        final int limit = Math.min(SLOT_COUNT, slots == null || slots <= 0 ? SLOT_COUNT : slots);
        final Set<String> hiddenFlags = extractHiddenFlags(data);
        for (ModSlotData slot : data) {
            if (slot.slotIndex() < 0 || slot.slotIndex() >= limit) {
                continue;
            }
            final ItemStack bukkitItem = manager.deserializeItem(slot.itemNbt());
            final Object nmsItem = manager.toNmsItem(bukkitItem);
            invoke(stacks, setStackInSlot, slot.slotIndex(),
                    nmsItem == null ? manager.getEmptyNmsItem() : nmsItem);
            if (slot.skinArmor() != null) {
                invoke(stacks, setSkinArmor, slot.slotIndex(), slot.skinArmor());
            }
        }
        if (hiddenFlags != null) {
            applyHiddenFlags(stacks, hiddenFlags);
        }
    }

    @Nullable
    private List<String> captureHiddenFlags(@NotNull Object stacks) {
        if (forEachHidden == null) {
            return null;
        }
        final Set<String> hidden = new HashSet<>();
        final BiConsumer<String, String> consumer = (modid, identifier) -> {
            if (modid == null || identifier == null || modid.isBlank() || identifier.isBlank()) {
                return;
            }
            hidden.add(modid + ":" + identifier);
        };
        invoke(stacks, forEachHidden, consumer);
        if (hidden.isEmpty()) {
            return List.of();
        }
        final List<String> ordered = new ArrayList<>(hidden);
        Collections.sort(ordered);
        return ordered;
    }

    @Nullable
    private Set<String> extractHiddenFlags(@NotNull List<ModSlotData> data) {
        boolean found = false;
        final Set<String> desired = new HashSet<>();
        for (ModSlotData slot : data) {
            final List<String> hiddenFlags = slot.hiddenFlags();
            if (hiddenFlags == null) {
                continue;
            }
            found = true;
            for (String entry : hiddenFlags) {
                if (entry != null && !entry.isBlank()) {
                    desired.add(entry);
                }
            }
        }
        return found ? desired : null;
    }

    private void applyHiddenFlags(@NotNull Object stacks, @NotNull Set<String> desiredFlags) {
        if (setHidden == null || forEachHidden == null) {
            return;
        }
        final List<String> currentFlags = captureHiddenFlags(stacks);
        if (currentFlags == null) {
            return;
        }
        final Set<String> current = new HashSet<>(currentFlags);
        for (String entry : current) {
            if (!desiredFlags.contains(entry)) {
                setHiddenFlag(stacks, entry, false);
            }
        }
        for (String entry : desiredFlags) {
            if (!current.contains(entry)) {
                setHiddenFlag(stacks, entry, true);
            }
        }
    }

    private void setHiddenFlag(@NotNull Object stacks, @NotNull String entry, boolean set) {
        if (setHidden == null) {
            return;
        }
        final int separator = entry.indexOf(':');
        if (separator <= 0 || separator >= entry.length() - 1) {
            return;
        }
        invoke(stacks, setHidden, entry.substring(0, separator), entry.substring(separator + 1), set);
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

            final Class<?> api = Class.forName("lain.mods.cos.api.CosArmorAPI");
            getCaStacks = api.getMethod("getCAStacks", java.util.UUID.class);
            final Class<?> stacksBase = Class.forName("lain.mods.cos.api.inventory.CAStacksBase");
            getSlots = stacksBase.getMethod("getSlots");
            getStackInSlot = stacksBase.getMethod("getStackInSlot", int.class);
            final Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            setStackInSlot = stacksBase.getMethod("setStackInSlot", int.class, nmsItemStack);
            try {
                isSkinArmor = stacksBase.getMethod("isSkinArmor", int.class);
                setSkinArmor = stacksBase.getMethod("setSkinArmor", int.class, boolean.class);
                forEachHidden = stacksBase.getMethod("forEachHidden", BiConsumer.class);
                setHidden = stacksBase.getMethod("setHidden", String.class, String.class, boolean.class);
            } catch (Throwable e) {
                plugin.debug("CosmeticArmorReworked flags not available; syncing items only", e);
            }
            available = true;
        } catch (Throwable e) {
            available = false;
            plugin.debug("CosmeticArmorReworked integration not available", e);
        }
    }

    @Nullable
    private Object getCosArmorStacks(@NotNull Player player) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return getCaStacks.invoke(null, player.getUniqueId());
        } catch (Throwable e) {
            plugin.debug("Failed to access CosmeticArmorReworked stacks", e);
            return null;
        }
    }

    @Nullable
    private Object invoke(@NotNull Object target, @NotNull Method method, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable e) {
            plugin.debug("Failed to invoke CosmeticArmorReworked method: " + method.getName(), e);
            return null;
        }
    }

}
