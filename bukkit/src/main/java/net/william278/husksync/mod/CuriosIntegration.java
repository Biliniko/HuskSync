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
import java.util.*;
import java.util.stream.Collectors;

public class CuriosIntegration implements ModIntegration {

    private static final String MOD_ID = "curios";
    private static final String DISPLAY_NAME = "Curios";
    private static final String COSMETIC_SUFFIX = ":cosmetic";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BRIGHT_CYAN = "\u001B[96m";
    private static final String ANSI_BRIGHT_YELLOW = "\u001B[93m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";

    private final BukkitHuskSync plugin;
    private final ModDataManager manager;
    private final Map<String, Method> methodCache = new HashMap<>();

    private boolean resolved = false;
    private boolean available = false;
    private Method getCuriosInventory;
    private Object curiosInventoryCapability;

    public CuriosIntegration(@NotNull BukkitHuskSync plugin, @NotNull ModDataManager manager) {
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
        final Object curiosHandler = getCuriosHandler(player);
        if (curiosHandler == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "handler missing")
                    + " (capture skipped) for " + player.getName()));
            return List.of();
        }

        final Map<?, ?> curios = castMap(invoke(curiosHandler, "getCurios"));
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
            final Object stacks = invoke(stacksHandler, "getStacks");
            if (stacks == null) {
                skippedNoStacks[0]++;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing stacks")
                        + " for " + slotKey + " (capture)"));
                return;
            }
            final Integer slots = (Integer) invoke(stacks, "getSlots");
            if (slots == null || slots <= 0) {
                skippedNoSlots[0]++;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "no slots")
                        + " for " + slotKey + " (capture)"));
                return;
            }
            for (int i = 0; i < slots; i++) {
                final Object nmsItem = invoke(stacks, "getStackInSlot", i);
                final ItemStack bukkitItem = manager.toBukkitItem(nmsItem);
                data.add(new ModSlotData(slotKey, i, manager.serializeItem(bukkitItem), null, null));
                slotEntries[0]++;
            }

            final Boolean hasCosmetic = (Boolean) invoke(stacksHandler, "hasCosmetic");
            if (Boolean.TRUE.equals(hasCosmetic)) {
                final Object cosmeticStacks = invoke(stacksHandler, "getCosmeticStacks");
                if (cosmeticStacks == null) {
                    skippedNoStacks[0]++;
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing cosmetic stacks")
                            + " for " + slotKey + " (capture)"));
                    return;
                }
                final Integer cosmeticSlots = (Integer) invoke(cosmeticStacks, "getSlots");
                if (cosmeticSlots == null || cosmeticSlots <= 0) {
                    skippedNoSlots[0]++;
                    plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "no cosmetic slots")
                            + " for " + slotKey + " (capture)"));
                    return;
                }
                for (int i = 0; i < cosmeticSlots; i++) {
                    final Object nmsItem = invoke(cosmeticStacks, "getStackInSlot", i);
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
    public void apply(@NotNull Player player, @NotNull List<ModSlotData> data) {
        final Object curiosHandler = getCuriosHandler(player);
        if (curiosHandler == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "handler missing")
                    + " (apply skipped) for " + player.getName()));
            return;
        }

        final Map<?, ?> curios = castMap(invoke(curiosHandler, "getCurios"));
        if (curios == null || curios.isEmpty()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "no curios handlers")
                    + " (apply skipped) for " + player.getName()));
            return;
        }

        final String availableKeys = curios.keySet().stream()
                .filter(String.class::isInstance)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));

        final Map<String, List<ModSlotData>> bySlot = data.stream()
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
        for (Map.Entry<String, List<ModSlotData>> entry : bySlot.entrySet()) {
            final boolean cosmetic = entry.getKey().endsWith(COSMETIC_SUFFIX);
            final String slotKey = cosmetic
                    ? entry.getKey().substring(0, entry.getKey().length() - COSMETIC_SUFFIX.length())
                    : entry.getKey();
            final Object stacksHandler = curios.get(slotKey);
            if (stacksHandler == null) {
                skippedMissingKey++;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing slot key")
                        + " " + slotKey + " for " + player.getName()));
                continue;
            }
            final Object stacks = invoke(stacksHandler, cosmetic ? "getCosmeticStacks" : "getStacks");
            if (stacks == null) {
                skippedNoStacks++;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "missing stacks")
                        + " for " + slotKey + " (cosmetic=" + cosmetic + ")"));
                continue;
            }
            final Integer slots = (Integer) invoke(stacks, "getSlots");
            if (slots == null || slots <= 0) {
                skippedNoStacks++;
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
                invoke(stacks, "setStackInSlot", slot.slotIndex(),
                        nmsItem == null ? manager.getEmptyNmsItem() : nmsItem);
                applied++;
            }
        }
        plugin.debug(formatDebug("apply summary for " + player.getName()
                + ": applied=" + color(ANSI_BRIGHT_CYAN, String.valueOf(applied))
                + ", missingKeys=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedMissingKey))
                + ", noStacks=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedNoStacks))
                + ", outOfRange=" + color(ANSI_BRIGHT_YELLOW, String.valueOf(skippedOutOfRange))));
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
            getCuriosInventory = findMethod(curiosApi, "getCuriosInventory", 1);
            try {
                final Class<?> curiosCapability = Class.forName("top.theillusivec4.curios.api.CuriosCapability");
                curiosInventoryCapability = curiosCapability.getField("INVENTORY").get(null);
            } catch (Throwable e) {
                plugin.debug("Curios capability access not available", e);
            }
            available = getCuriosInventory != null || curiosInventoryCapability != null;
        } catch (Throwable e) {
            available = false;
            plugin.debug("Curios integration not available", e);
        }
    }

    @Nullable
    private Object getCuriosHandler(@NotNull Player player) {
        if (!isAvailable()) {
            return null;
        }
        try {
            final Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object handler = null;
            if (getCuriosInventory != null) {
                final Object optional = getCuriosInventory.invoke(null, handle);
                handler = resolveOptional(optional);
            }
            if (handler == null && curiosInventoryCapability != null) {
                final Object capability = invoke(handle, "getCapability", curiosInventoryCapability);
                handler = resolveOptional(capability);
                if (handler == null) {
                    final Object sidedCapability = invoke(handle, "getCapability",
                            curiosInventoryCapability, null);
                    handler = resolveOptional(sidedCapability);
                }
            }
            return handler;
        } catch (Throwable e) {
            plugin.debug("Failed to access Curios handler", e);
            return null;
        }
    }

    @Nullable
    private Object resolveOptional(@Nullable Object optional) {
        if (optional == null) {
            return null;
        }
        if (optional instanceof Optional<?> opt) {
            return opt.orElse(null);
        }
        final Object resolved = invoke(optional, "resolve");
        if (resolved instanceof Optional<?> opt) {
            return opt.orElse(null);
        }
        return invoke(optional, "orElse", (Object) null);
    }

    @Nullable
    private Object invoke(@NotNull Object target, @NotNull String methodName, Object... args) {
        final Method method = findMethod(target.getClass(), methodName, args.length);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable e) {
            plugin.debug("Failed to invoke Curios method: " + methodName, e);
            return null;
        }
    }

    @Nullable
    private Method findMethod(@NotNull Class<?> type, @NotNull String name, int params) {
        final String key = type.getName() + "#" + name + "#" + params;
        if (methodCache.containsKey(key)) {
            return methodCache.get(key);
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == params) {
                method.setAccessible(true);
                methodCache.put(key, method);
                return method;
            }
        }
        methodCache.put(key, null);
        return null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private Map<?, ?> castMap(@Nullable Object map) {
        if (map instanceof Map<?, ?> m) {
            return m;
        }
        return null;
    }

}
