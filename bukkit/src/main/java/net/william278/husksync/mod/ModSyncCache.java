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

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public class ModSyncCache<T> {

    public static final long DEFAULT_TTL_MS = 30_000L;

    private final long ttlMs;
    private final LongSupplier clock;
    private final Map<UUID, Entry<T>> cached = new ConcurrentHashMap<>();
    private final Map<UUID, Entry<T>> trusted = new ConcurrentHashMap<>();
    private final Map<UUID, Entry<T>> pending = new ConcurrentHashMap<>();

    private record Entry<T>(@NotNull T data, long timestamp) {
    }

    public ModSyncCache() {
        this(DEFAULT_TTL_MS);
    }

    public ModSyncCache(long ttlMs) {
        this(ttlMs, System::currentTimeMillis);
    }

    ModSyncCache(long ttlMs, @NotNull LongSupplier clock) {
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    public void storeTrusted(@NotNull UUID uuid, @NotNull T data) {
        final Entry<T> entry = new Entry<>(data, clock.getAsLong());
        cached.put(uuid, entry);
        trusted.put(uuid, entry);
    }

    public void storePending(@NotNull UUID uuid, @NotNull T data) {
        final Entry<T> entry = new Entry<>(data, clock.getAsLong());
        pending.put(uuid, entry);
        cached.put(uuid, entry);
        trusted.put(uuid, entry);
    }

    public void confirmPending(@NotNull UUID uuid, @NotNull T data) {
        pending.remove(uuid);
        storeTrusted(uuid, data);
    }

    @NotNull
    public CaptureResult<T> capture(@NotNull UUID uuid, @NotNull Optional<T> direct,
                                    @NotNull String unavailableReason) {
        final Optional<T> pendingData = getPending(uuid);
        if (pendingData.isPresent()) {
            if (direct.isPresent() && Objects.equals(direct.get(), pendingData.get())) {
                confirmPending(uuid, direct.get());
                return CaptureResult.captured(direct.get(), "pending confirmed");
            }
            return CaptureResult.fallbackPending(pendingData.get(), unavailableReason);
        }

        if (direct.isPresent()) {
            storeTrusted(uuid, direct.get());
            return CaptureResult.captured(direct.get());
        }

        return fallback(uuid, unavailableReason);
    }

    @NotNull
    public CaptureResult<T> fallback(@NotNull UUID uuid, @NotNull String reason) {
        final Optional<T> pendingData = getPending(uuid);
        if (pendingData.isPresent()) {
            return CaptureResult.fallbackPending(pendingData.get(), reason);
        }

        final Optional<T> cachedData = getCached(uuid);
        if (cachedData.isPresent()) {
            return CaptureResult.fallbackCached(cachedData.get(), reason);
        }

        final Entry<T> trustedEntry = trusted.get(uuid);
        if (trustedEntry != null) {
            return CaptureResult.fallbackTrusted(trustedEntry.data(), reason);
        }

        return CaptureResult.unavailable(reason);
    }

    @NotNull
    public Optional<T> getCached(@NotNull UUID uuid) {
        return getFresh(cached, uuid);
    }

    @NotNull
    public Optional<T> getPending(@NotNull UUID uuid) {
        return Optional.ofNullable(pending.get(uuid)).map(Entry::data);
    }

    @NotNull
    public Optional<T> getTrusted(@NotNull UUID uuid) {
        return Optional.ofNullable(trusted.get(uuid)).map(Entry::data);
    }

    public void clear(@NotNull UUID uuid) {
        cached.remove(uuid);
        trusted.remove(uuid);
        pending.remove(uuid);
    }

    @NotNull
    private Optional<T> getFresh(@NotNull Map<UUID, Entry<T>> source, @NotNull UUID uuid) {
        final Entry<T> entry = source.get(uuid);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() - entry.timestamp() > ttlMs) {
            source.remove(uuid);
            return Optional.empty();
        }
        return Optional.of(entry.data());
    }

}
