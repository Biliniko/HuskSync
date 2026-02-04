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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SolCarrotIntegration {

    private static final String MOD_ID = "solcarrot";
    private static final String API_CLASS = "com.cazsius.solcarrot.api.SOLCarrotAPI";
    private static final String CAPABILITY_HANDLER_CLASS = "com.cazsius.solcarrot.tracking.CapabilityHandler";
    private static final String TAG_PARSER_CLASS = "net.minecraft.nbt.TagParser";
    private static final String COMPOUND_TAG_CLASS = "net.minecraft.nbt.CompoundTag";
    private static final long CAPTURE_CACHE_TTL_MS = 5000L;
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    private static final String ANSI_BRIGHT_CYAN = "\u001B[96m";
    private static final String ANSI_BRIGHT_YELLOW = "\u001B[93m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";

    private final BukkitHuskSync plugin;
    private final Map<String, Method> methodCache = new HashMap<>();
    private final Map<UUID, CachedFood> cachedFoodList = new ConcurrentHashMap<>();

    private boolean resolved;
    private boolean available;

    @Nullable
    private Method getFoodCapability;
    @Nullable
    private Method syncFoodList;
    @Nullable
    private Method parseTag;

    private record CachedFood(@NotNull String snbt, long timestamp) {
    }

    public SolCarrotIntegration(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

        @NotNull
    public Optional<String> capture(@NotNull Player player) {
        if (!isAvailable() || getFoodCapability == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture skipped")
                    + " (integration unavailable) for " + player.getName()));
            return Optional.empty();
        }
        final Optional<String> direct = captureFromCapability(player, "capture");
        if (direct.isPresent()) {
            cache(player.getUniqueId(), direct.get());
            return direct;
        }
        final Optional<CachedFood> cached = getCached(player.getUniqueId());
        if (cached.isPresent()) {
            final long ageMs = System.currentTimeMillis() - cached.get().timestamp();
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "capture using cached data")
                    + " ageMs=" + color(ANSI_BRIGHT_CYAN, String.valueOf(ageMs))
                    + " for " + player.getName()));
            return Optional.of(cached.get().snbt());
        }
        plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "capture failed")
                + " (no cached data) for " + player.getName()));
        return Optional.empty();
    }

    public void cachePlayerData(@NotNull Player player) {
        if (!isAvailable() || getFoodCapability == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "pre-cache skipped")
                    + " (integration unavailable) for " + player.getName()));
            return;
        }
        final Optional<String> direct = captureFromCapability(player, "pre-cache");
        direct.ifPresent(snbt -> {
            cache(player.getUniqueId(), snbt);
            plugin.debug(formatDebug("pre-cache stored for "
                    + color(ANSI_BRIGHT_CYAN, player.getName())
                    + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))));
        });
    }

        public void apply(@NotNull Player player, @Nullable String snbt) {
        if (!isAvailable() || getFoodCapability == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply skipped")
                    + " (integration unavailable) for " + player.getName()));
            return;
        }
        if (snbt == null || snbt.isBlank()) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply skipped")
                    + " (empty NBT) for " + player.getName()));
            return;
        }
        plugin.debug(formatDebug("apply start for " + color(ANSI_BRIGHT_CYAN, player.getName())
                + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))));
        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply failed")
                    + " (nms player null) for " + player.getName()));
            return;
        }
        final Object capability = invokeStatic(getFoodCapability, nmsPlayer);
        if (capability == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply failed")
                    + " (capability null) for " + player.getName()));
            return;
        }
        final Object tag = parseCompoundTag(snbt);
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "apply failed")
                    + " (parseTag null) for " + player.getName()));
            return;
        }
        invoke(capability, "deserializeNBT", tag);
        plugin.debug(formatDebug("apply deserialized for " + color(ANSI_BRIGHT_CYAN, player.getName())));
        if (syncFoodList != null) {
            invokeStatic(syncFoodList, nmsPlayer);
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "syncFoodList invoked")
                    + " for " + player.getName()));
        } else {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "syncFoodList missing")
                    + " (capability applied only) for " + player.getName()));
        }
    }

    @NotNull
    private Optional<String> captureFromCapability(@NotNull Player player, @NotNull String phase) {
        plugin.debug(formatDebug(phase + " start for " + color(ANSI_BRIGHT_CYAN, player.getName())));
        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (nms player null) for " + player.getName()));
            return Optional.empty();
        }
        final Object capability = invokeStatic(getFoodCapability, nmsPlayer);
        if (capability == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (capability null) for " + player.getName()));
            return Optional.empty();
        }
        final Object tag = invoke(capability, "serializeNBT");
        if (tag == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, phase + " failed")
                    + " (serializeNBT returned null) for " + player.getName()));
            return Optional.empty();
        }
        final String snbt = tag.toString();
        plugin.debug(formatDebug(phase + " ok for " + color(ANSI_BRIGHT_CYAN, player.getName())
                + ", bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))));
        return Optional.of(snbt);
    }

        private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            plugin.debug(formatDebug("resolving integration"));
            final Class<?> modList = Class.forName("net.minecraftforge.fml.ModList");
            final Object modListInstance = modList.getMethod("get").invoke(null);
            final boolean loaded = (boolean) modList.getMethod("isLoaded", String.class)
                    .invoke(modListInstance, MOD_ID);
            if (!loaded) {
                available = false;
                plugin.debug(formatDebug(color(ANSI_BRIGHT_YELLOW, "mod not loaded") + " (" + MOD_ID + ")"));
                return;
            }

            final Class<?> apiClass = Class.forName(API_CLASS);
            getFoodCapability = findMethod(apiClass, "getFoodCapability", 1);
            final Class<?> handlerClass = Class.forName(CAPABILITY_HANDLER_CLASS);
            syncFoodList = findMethod(handlerClass, "syncFoodList", 1);

            final Class<?> parserClass = Class.forName(TAG_PARSER_CLASS);
            final Class<?> compoundTagClass = Class.forName(COMPOUND_TAG_CLASS);
            parseTag = findTagParser(parserClass, compoundTagClass);

            plugin.debug(formatDebug("resolved getFoodCapability=" + color(ANSI_BRIGHT_CYAN,
                    String.valueOf(getFoodCapability != null))
                    + ", syncFoodList=" + color(ANSI_BRIGHT_CYAN, String.valueOf(syncFoodList != null))
                    + ", parseTag=" + color(ANSI_BRIGHT_CYAN, String.valueOf(parseTag != null))));

            // syncFoodList is optional; capability access + NBT parsing are the hard requirements
            available = getFoodCapability != null && parseTag != null;
            plugin.debug(formatDebug("integration available=" + color(ANSI_BRIGHT_CYAN, String.valueOf(available))));
        } catch (Throwable e) {
            available = false;
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "integration not available")), e);
        }
    }

        @Nullable
    private Method findTagParser(@NotNull Class<?> parserClass, @NotNull Class<?> compoundTagClass) {
        Method method = findMethod(parserClass, "parseTag", 1);
        if (method != null && compoundTagClass.isAssignableFrom(method.getReturnType())) {
            plugin.debug(formatDebug("TagParser method=" + color(ANSI_BRIGHT_CYAN, "parseTag")));
            return method;
        }
        method = findMethod(parserClass, "parse", 1);
        if (method != null && compoundTagClass.isAssignableFrom(method.getReturnType())) {
            plugin.debug(formatDebug("TagParser method=" + color(ANSI_BRIGHT_CYAN, "parse")));
            return method;
        }
        for (Method candidate : parserClass.getMethods()) {
            if (candidate.getParameterCount() == 1
                    && candidate.getParameterTypes()[0] == String.class
                    && compoundTagClass.isAssignableFrom(candidate.getReturnType())) {
                candidate.setAccessible(true);
                plugin.debug(formatDebug("TagParser method="
                        + color(ANSI_BRIGHT_CYAN, candidate.getName())));
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
            return parseTag.invoke(null, snbt);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "parse NBT failed")
                    + " bytes=" + color(ANSI_BRIGHT_CYAN, String.valueOf(snbt.length()))), e);
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
    private Object invoke(@NotNull Object target, @NotNull String methodName, Object... args) {
        final Method method = findMethod(target.getClass(), methodName, args.length);
        if (method == null) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "method missing")
                    + " " + methodName));
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "invoke failed")
                    + " " + methodName), e);
            return null;
        }
    }

    @Nullable
    private Object invokeStatic(@NotNull Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (Throwable e) {
            plugin.debug(formatDebug(color(ANSI_BRIGHT_RED, "invoke static failed")
                    + " " + method.getName()), e);
            return null;
        }
    }

    private void cache(@NotNull UUID uuid, @NotNull String snbt) {
        if (snbt.isBlank()) {
            return;
        }
        cachedFoodList.put(uuid, new CachedFood(snbt, System.currentTimeMillis()));
    }

    @NotNull
    private Optional<CachedFood> getCached(@NotNull UUID uuid) {
        final CachedFood cached = cachedFoodList.get(uuid);
        if (cached == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - cached.timestamp() > CAPTURE_CACHE_TTL_MS) {
            cachedFoodList.remove(uuid);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    @NotNull
    private String formatDebug(@NotNull String message) {
        return ANSI_BRIGHT_MAGENTA + "[SoL:Carrot]" + ANSI_RESET + " " + message;
    }

    @NotNull
    private String color(@NotNull String color, @NotNull String message) {
        return color + message + ANSI_RESET;
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

}
