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
import java.util.function.Consumer;

public class ForgeEventBridge {

    private static final String EVENT_BUS_CLASS = "net.minecraftforge.common.MinecraftForge";
    private static final String EVENT_PRIORITY_CLASS = "net.minecraftforge.eventbus.api.EventPriority";
    private static final String LOGOUT_EVENT_CLASS =
            "net.minecraftforge.event.entity.player.PlayerEvent$PlayerLoggedOutEvent";

    private final BukkitHuskSync plugin;
    private final Map<String, Method> methodCache = new HashMap<>();
    private boolean registered;

    public ForgeEventBridge(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        register();
    }

    public boolean isRegistered() {
        return registered;
    }

    private void register() {
        try {
            final Class<?> forgeClass = Class.forName(EVENT_BUS_CLASS);
            final Object eventBus = forgeClass.getField("EVENT_BUS").get(null);
            final Class<?> priorityClass = Class.forName(EVENT_PRIORITY_CLASS);
            @SuppressWarnings("unchecked")
            final Object normalPriority = Enum.valueOf((Class<Enum>) priorityClass, "NORMAL");
            final Class<?> logoutEvent = Class.forName(LOGOUT_EVENT_CLASS);
            final Method addListener = eventBus.getClass().getMethod(
                    "addListener", priorityClass, boolean.class, Class.class, Consumer.class
            );

            final Consumer<Object> listener = event -> {
                final Player player = resolvePlayer(event);
                if (player == null || plugin.isDisabling()) {
                    return;
                }
                final ModDataManager manager = plugin.getModDataManager();
                if (manager != null && manager.hasAvailableIntegrations()) {
                    manager.cachePlayerModData(player);
                }

                final SolCarrotIntegration solCarrot = plugin.getSolCarrotIntegration();
                if (solCarrot != null) {
                    solCarrot.cachePlayerData(player);
                }

                final UltimineIntegration ultimine = plugin.getUltimineIntegration();
                if (ultimine != null) {
                    plugin.debug("Ultimine logout pre-cache for " + player.getName());
                    ultimine.cachePlayerData(player);
                }

                final MnaIntegration mna = plugin.getMnaIntegration();
                if (mna != null) {
                    plugin.debug("MNA logout pre-cache for " + player.getName());
                    mna.cachePlayerData(player);
                }

                final ArsNouveauIntegration arsNouveau = plugin.getArsNouveauIntegration();
                if (arsNouveau != null) {
                    plugin.debug("Ars Nouveau logout pre-cache for " + player.getName());
                    arsNouveau.cachePlayerData(player);
                }
            };

            addListener.invoke(eventBus, normalPriority, false, logoutEvent, listener);
            registered = true;
            plugin.debug("Registered Forge logout event bridge for mod data.");
        } catch (Throwable e) {
            plugin.debug("Failed to register Forge logout event bridge for mod data", e);
        }
    }

    @Nullable
    private Player resolvePlayer(@NotNull Object event) {
        Object entity = invoke(event, "getEntity");
        if (entity == null) {
            entity = invoke(event, "getPlayer");
        }
        if (entity == null) {
            return null;
        }
        final Object bukkitEntity = invoke(entity, "getBukkitEntity");
        return bukkitEntity instanceof Player player ? player : null;
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

}
