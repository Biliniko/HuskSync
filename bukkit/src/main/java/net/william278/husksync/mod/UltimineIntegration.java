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
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UltimineIntegration {

    private static final String MOD_ID = "ultimine_addition";
    private static final String SERVICE_CLASS =
            "net.ixdarklord.ultimine_addition.core.forge.ServicePlatformPlayersImpl";
    private static final long CAPTURE_CACHE_TTL_MS = 5000L;

    private final BukkitHuskSync plugin;
    private final Map<String, Method> methodCache = new HashMap<>();
    private final Map<UUID, CachedAbility> cachedAbility = new ConcurrentHashMap<>();

    private boolean resolved;
    private boolean available;

    @Nullable
    private Method getAbility;
    @Nullable
    private Method setAbility;

    private record CachedAbility(boolean ability, long timestamp) {
    }

    public UltimineIntegration(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

    @NotNull
    public Optional<Boolean> capture(@NotNull Player player) {
        if (!isAvailable() || getAbility == null) {
            plugin.debug("Ultimine capture skipped (integration unavailable) for " + player.getName());
            return Optional.empty();
        }
        final Optional<CachedAbility> cached = getCached(player.getUniqueId());
        if (!player.isOnline()) {
            if (cached.isPresent()) {
                cached.ifPresent(entry -> plugin.debug("Ultimine capture using cached data (player offline) ageMs="
                        + (System.currentTimeMillis() - entry.timestamp()) + " for " + player.getName()));
                return cached.map(CachedAbility::ability);
            }
            plugin.debug("Ultimine capture skipped (player offline, no cache) for " + player.getName());
            return Optional.empty();
        }
        final Optional<Boolean> direct = captureFromHandle(player, "capture");
        if (direct.isPresent()) {
            cache(player.getUniqueId(), direct.get());
            plugin.debug("Ultimine capture direct=" + direct.get() + " for " + player.getName());
            return direct;
        }
        cached.ifPresent(entry -> plugin.debug("Ultimine capture using cached data ageMs="
                + (System.currentTimeMillis() - entry.timestamp()) + " for " + player.getName()));
        return cached.map(CachedAbility::ability);
    }

    public void cachePlayerData(@NotNull Player player) {
        if (!isAvailable() || getAbility == null) {
            plugin.debug("Ultimine pre-cache skipped (integration unavailable) for " + player.getName());
            return;
        }
        captureFromHandle(player, "pre-cache").ifPresent(ability -> {
            cache(player.getUniqueId(), ability);
            plugin.debug("Ultimine pre-cache stored for " + player.getName() + " value=" + ability);
        });
    }

    public void apply(@NotNull Player player, boolean ability) {
        if (!isAvailable() || setAbility == null) {
            plugin.debug("Ultimine apply skipped (integration unavailable) for " + player.getName());
            return;
        }
        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug("Ultimine apply skipped (nms player null) for " + player.getName());
            return;
        }
        plugin.debug("Ultimine apply value=" + ability + " for " + player.getName());
        invokeStatic(setAbility, nmsPlayer, ability);
    }

    @NotNull
    private Optional<Boolean> captureFromHandle(@NotNull Player player, @NotNull String phase) {
        final Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug("Ultimine " + phase + " failed (nms player null) for " + player.getName());
            return Optional.empty();
        }
        final Object result = invokeStatic(getAbility, nmsPlayer);
        if (result instanceof Boolean value) {
            plugin.debug("Ultimine " + phase + " ok for " + player.getName());
            return Optional.of(value);
        }
        plugin.debug("Ultimine " + phase + " failed (result null) for " + player.getName());
        return Optional.empty();
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        plugin.debug("Ultimine resolve start");
        try {
            final Class<?> modList = Class.forName("net.minecraftforge.fml.ModList");
            final Object modListInstance = modList.getMethod("get").invoke(null);
            final boolean loaded = (boolean) modList.getMethod("isLoaded", String.class)
                    .invoke(modListInstance, MOD_ID);
            if (!loaded) {
                available = false;
                plugin.debug("Ultimine resolve: mod not loaded (" + MOD_ID + ")");
                return;
            }
            plugin.debug("Ultimine resolve: mod loaded (" + MOD_ID + ")");

            final Class<?> service = Class.forName(SERVICE_CLASS);
            getAbility = findMethod(service, "isPlayerUltimineCapable", 1);
            setAbility = findMethod(service, "setPlayerUltimineCapability", 2);
            available = getAbility != null && setAbility != null;
            plugin.debug("Ultimine resolve: getAbility=" + (getAbility != null)
                    + ", setAbility=" + (setAbility != null)
                    + ", available=" + available);
        } catch (Throwable e) {
            available = false;
            plugin.debug("Ultimine integration not available", e);
        }
    }

    @Nullable
    private Object getHandle(@NotNull Player player) {
        try {
            final Method method = findMethod(player.getClass(), "getHandle", 0);
            if (method == null) {
                plugin.debug("Ultimine getHandle missing for " + player.getName());
                return null;
            }
            return method.invoke(player);
        } catch (Throwable e) {
            plugin.debug("Ultimine getHandle failed for " + player.getName(), e);
            return null;
        }
    }

    @Nullable
    private Object invokeStatic(@Nullable Method method, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            final boolean isStatic = Modifier.isStatic(method.getModifiers());
            final Object target;
            if (isStatic) {
                target = null;
            } else {
                plugin.debug("Ultimine method is non-static: " + method.getDeclaringClass().getName()
                        + "#" + method.getName());
                target = method.getDeclaringClass().getDeclaredConstructor().newInstance();
            }
            if (!method.canAccess(target)) {
                method.setAccessible(true);
            }
            return method.invoke(target, args);
        } catch (Throwable e) {
            plugin.debug("Failed to invoke Ultimine method: " + method.getName(), e);
            return null;
        }
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
                if (Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    methodCache.put(key, method);
                    return method;
                }
                if (fallback == null) {
                    fallback = method;
                }
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
        }
        methodCache.put(key, fallback);
        return fallback;
    }

    private void cache(@NotNull UUID uuid, boolean ability) {
        cachedAbility.put(uuid, new CachedAbility(ability, System.currentTimeMillis()));
        plugin.debug("Ultimine cache stored for " + uuid + " value=" + ability);
    }

    @NotNull
    private Optional<CachedAbility> getCached(@NotNull UUID uuid) {
        final CachedAbility cached = cachedAbility.get(uuid);
        if (cached == null) {
            plugin.debug("Ultimine cache miss for " + uuid);
            return Optional.empty();
        }
        final long age = System.currentTimeMillis() - cached.timestamp();
        if (age > CAPTURE_CACHE_TTL_MS) {
            cachedAbility.remove(uuid);
            plugin.debug("Ultimine cache expired for " + uuid + " ageMs=" + age);
            return Optional.empty();
        }
        plugin.debug("Ultimine cache hit for " + uuid + " ageMs=" + age);
        return Optional.of(cached);
    }

}