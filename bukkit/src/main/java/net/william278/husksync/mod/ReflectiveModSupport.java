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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ReflectiveModSupport {

    private final BukkitHuskSync plugin;
    private final String label;
    private final Map<String, Method> methodCache = Collections.synchronizedMap(new HashMap<>());

    public ReflectiveModSupport(@NotNull BukkitHuskSync plugin, @NotNull String label) {
        this.plugin = plugin;
        this.label = label;
    }

    @Nullable
    public Method findMethod(@NotNull Class<?> type, @NotNull String name, int params) {
        return findMethod(type, name, params, false);
    }

    @Nullable
    public Method findMethodPreferStatic(@NotNull Class<?> type, @NotNull String name, int params) {
        return findMethod(type, name, params, true);
    }

    @Nullable
    private Method findMethod(@NotNull Class<?> type, @NotNull String name, int params, boolean preferStatic) {
        final String key = type.getName() + "#" + name + "#" + params + "#" + preferStatic;
        if (methodCache.containsKey(key)) {
            return methodCache.get(key);
        }

        Method fallback = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != params) {
                continue;
            }
            if (!preferStatic) {
                method.setAccessible(true);
                if (fallback == null) {
                    fallback = method;
                }
                if (method.getDeclaringClass() == type) {
                    methodCache.put(key, method);
                    return method;
                }
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                method.setAccessible(true);
                methodCache.put(key, method);
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != params) {
                    continue;
                }
                if (!preferStatic || Modifier.isStatic(method.getModifiers())) {
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

    @Nullable
    public Object getHandle(@NotNull Player player) {
        try {
            final Method method = findMethod(player.getClass(), "getHandle", 0);
            if (method == null) {
                plugin.debug(label + " getHandle missing for " + player.getName());
                return null;
            }
            return method.invoke(player);
        } catch (Throwable e) {
            plugin.debug(label + " getHandle failed for " + player.getName(), e);
            return null;
        }
    }

    @Nullable
    public Object invoke(@Nullable Object target, @NotNull String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        final Method method = findMethod(target.getClass(), methodName, args.length);
        if (method == null) {
            return null;
        }
        return invoke(method, target, args);
    }

    @Nullable
    public Object invoke(@Nullable Method method, @Nullable Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            if (!method.canAccess(target)) {
                method.setAccessible(true);
            }
            return method.invoke(target, args);
        } catch (Throwable e) {
            plugin.debug(label + " method invoke failed: " + method.getName(), e);
            return null;
        }
    }

    @Nullable
    public Object invokeStatic(@Nullable Method method, Object... args) {
        return invoke(method, (Object) null, args);
    }

    @Nullable
    public Object invokeStaticPreferred(@Nullable Method method, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            final boolean isStatic = Modifier.isStatic(method.getModifiers());
            final Object target;
            if (isStatic) {
                target = null;
            } else {
                plugin.debug(label + " method is non-static: " + method.getDeclaringClass().getName()
                        + "#" + method.getName());
                target = method.getDeclaringClass().getDeclaredConstructor().newInstance();
            }
            if (!method.canAccess(target)) {
                method.setAccessible(true);
            }
            return method.invoke(target, args);
        } catch (Throwable e) {
            plugin.debug(label + " method invoke failed: " + method.getName(), e);
            return null;
        }
    }

    @Nullable
    public Object resolveOptional(@Nullable Object optional) {
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

}
