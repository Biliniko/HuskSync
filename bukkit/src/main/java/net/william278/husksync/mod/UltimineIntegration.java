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
import java.util.Optional;

public class UltimineIntegration {

    private static final String MOD_ID = "ultimine_addition";
    private static final String SERVICE_CLASS =
            "net.ixdarklord.ultimine_addition.core.forge.ServicePlatformPlayersImpl";
    private static final String CAPABILITY_PROVIDER_CLASS =
            "net.ixdarklord.ultimine_addition.common.data.player.forge.PlayerUltimineCapabilityProvider";

    private final BukkitHuskSync plugin;
    private final ReflectiveModSupport reflection;
    private final ModSyncCache<Boolean> abilityCache = new ModSyncCache<>();

    private boolean resolved;
    private boolean available;

    @Nullable
    private Method getAbility;
    @Nullable
    private Method setAbility;
    @Nullable
    private Object playerAbilityCapability;

    public UltimineIntegration(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        this.reflection = new ReflectiveModSupport(plugin, "Ultimine");
    }

    public boolean isAvailable() {
        resolve();
        return available;
    }

    @NotNull
    public Optional<Boolean> capture(@NotNull Player player) {
        if (!isAvailable() || (getAbility == null && playerAbilityCapability == null)) {
            plugin.debug("Ultimine capture skipped (integration unavailable) for " + player.getName());
            return captureFallback(player, "integration unavailable");
        }
        if (!player.isOnline()) {
            return captureFallback(player, "player offline");
        }
        final Optional<Boolean> direct = captureFromHandle(player, "capture");
        final CaptureResult<Boolean> result = abilityCache.capture(player.getUniqueId(), direct, "direct capture failed");
        logCaptureResult(player, result);
        return result.dataOptional();
    }

    public void cachePlayerData(@NotNull Player player) {
        if (!isAvailable() || (getAbility == null && playerAbilityCapability == null)) {
            plugin.debug("Ultimine pre-cache skipped (integration unavailable) for " + player.getName());
            return;
        }
        captureFromHandle(player, "pre-cache").ifPresent(ability -> {
            abilityCache.storeTrusted(player.getUniqueId(), ability);
            plugin.debug("Ultimine pre-cache stored for " + player.getName() + " value=" + ability);
        });
    }

    @NotNull
    public ApplyResult apply(@NotNull Player player, boolean ability) {
        abilityCache.storePending(player.getUniqueId(), ability);
        if (!isAvailable() || setAbility == null) {
            plugin.debug("Ultimine apply skipped (integration unavailable) for " + player.getName());
            return ApplyResult.pending("integration unavailable");
        }
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug("Ultimine apply skipped (nms player null) for " + player.getName());
            return ApplyResult.pending("nms player null");
        }
        plugin.debug("Ultimine apply value=" + ability + " for " + player.getName());
        reflection.invokeStaticPreferred(setAbility, nmsPlayer, ability);
        plugin.debug("Ultimine apply pending confirmation for " + player.getName());
        return ApplyResult.pending("awaiting capture confirmation");
    }

    @NotNull
    private Optional<Boolean> captureFromHandle(@NotNull Player player, @NotNull String phase) {
        final Object nmsPlayer = reflection.getHandle(player);
        if (nmsPlayer == null) {
            plugin.debug("Ultimine " + phase + " failed (nms player null) for " + player.getName());
            return Optional.empty();
        }
        final Optional<Boolean> capability = captureFromCapability(nmsPlayer, player, phase);
        if (capability.isPresent()) {
            return capability;
        }
        if (getAbility == null) {
            plugin.debug("Ultimine " + phase + " failed (capability unavailable) for " + player.getName());
            return Optional.empty();
        }

        final Object result = reflection.invokeStaticPreferred(getAbility, nmsPlayer);
        if (result instanceof Boolean value) {
            if (!value) {
                plugin.debug("Ultimine " + phase
                        + " ignored fallback false (capability unavailable) for " + player.getName());
                return Optional.empty();
            }
            plugin.debug("Ultimine " + phase + " ok via fallback service for " + player.getName());
            return Optional.of(true);
        }
        plugin.debug("Ultimine " + phase + " failed (result null) for " + player.getName());
        return Optional.empty();
    }

    @NotNull
    private Optional<Boolean> captureFromCapability(@NotNull Object nmsPlayer, @NotNull Player player,
                                                    @NotNull String phase) {
        if (playerAbilityCapability == null) {
            return Optional.empty();
        }

        Object optional = reflection.invoke(nmsPlayer, "getCapability", playerAbilityCapability);
        Object capability = reflection.resolveOptional(optional);
        if (capability == null) {
            optional = reflection.invoke(nmsPlayer, "getCapability", playerAbilityCapability, null);
            capability = reflection.resolveOptional(optional);
        }
        if (capability == null) {
            plugin.debug("Ultimine " + phase + " failed (capability missing) for " + player.getName());
            return Optional.empty();
        }

        final Object result = reflection.invoke(capability, "getAbility");
        if (result instanceof Boolean value) {
            plugin.debug("Ultimine " + phase + " ok via capability for " + player.getName());
            return Optional.of(value);
        }
        plugin.debug("Ultimine " + phase + " failed (capability value null) for " + player.getName());
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
            getAbility = reflection.findMethodPreferStatic(service, "isPlayerUltimineCapable", 1);
            setAbility = reflection.findMethodPreferStatic(service, "setPlayerUltimineCapability", 2);
            try {
                final Class<?> provider = Class.forName(CAPABILITY_PROVIDER_CLASS);
                playerAbilityCapability = provider.getField("CAPABILITY").get(null);
            } catch (Throwable e) {
                plugin.debug("Ultimine resolve: capability access unavailable", e);
            }
            available = setAbility != null && (getAbility != null || playerAbilityCapability != null);
            plugin.debug("Ultimine resolve: getAbility=" + (getAbility != null)
                    + ", setAbility=" + (setAbility != null)
                    + ", capability=" + (playerAbilityCapability != null)
                    + ", available=" + available);
        } catch (Throwable e) {
            available = false;
            plugin.debug("Ultimine integration not available", e);
        }
    }

    @NotNull
    private Optional<Boolean> captureFallback(@NotNull Player player, @NotNull String reason) {
        final CaptureResult<Boolean> result = abilityCache.fallback(player.getUniqueId(), reason);
        logCaptureResult(player, result);
        return result.dataOptional();
    }

    private void logCaptureResult(@NotNull Player player, @NotNull CaptureResult<Boolean> result) {
        switch (result.status()) {
            case CAPTURED -> plugin.debug("Ultimine capture direct=" + result.data()
                    + " for " + player.getName() + " (" + result.reason() + ")");
            case FALLBACK_PENDING -> plugin.debug("Ultimine capture using pending applied data value=" + result.data()
                    + " for " + player.getName() + " (" + result.reason() + ")");
            case FALLBACK_CACHED -> plugin.debug("Ultimine capture using cached data value=" + result.data()
                    + " for " + player.getName() + " (" + result.reason() + ")");
            case FALLBACK_TRUSTED -> plugin.debug("Ultimine capture using trusted data value=" + result.data()
                    + " for " + player.getName() + " (" + result.reason() + ")");
            case UNAVAILABLE -> plugin.debug("Ultimine capture skipped (no fallback data) for " + player.getName()
                    + " (" + result.reason() + ")");
        }
    }

}
