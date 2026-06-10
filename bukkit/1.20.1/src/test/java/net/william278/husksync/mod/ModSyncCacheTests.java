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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Mod Sync Cache Tests")
public class ModSyncCacheTests {

    @Test
    @DisplayName("Direct Capture Stores Trusted Data")
    public void testDirectCaptureStoresTrustedData() {
        final AtomicLong now = new AtomicLong(1_000L);
        final ModSyncCache<String> cache = new ModSyncCache<>(100L, now::get);
        final UUID uuid = UUID.randomUUID();

        final CaptureResult<String> captured = cache.capture(uuid, Optional.of("remote"), "missing");
        assertEquals(CaptureResult.Status.CAPTURED, captured.status());
        assertEquals(Optional.of("remote"), cache.getTrusted(uuid));

        now.addAndGet(10L);
        final CaptureResult<String> fallback = cache.capture(uuid, Optional.empty(), "no direct data");
        assertEquals(CaptureResult.Status.FALLBACK_CACHED, fallback.status());
        assertEquals(Optional.of("remote"), fallback.dataOptional());
    }

    @Test
    @DisplayName("Pending Data Blocks Divergent Direct Capture")
    public void testPendingDataBlocksDivergentDirectCapture() {
        final AtomicLong now = new AtomicLong(1_000L);
        final ModSyncCache<String> cache = new ModSyncCache<>(100L, now::get);
        final UUID uuid = UUID.randomUUID();

        cache.storePending(uuid, "remote");

        final CaptureResult<String> divergent = cache.capture(uuid, Optional.of("local-empty"), "direct mismatch");
        assertEquals(CaptureResult.Status.FALLBACK_PENDING, divergent.status());
        assertEquals(Optional.of("remote"), divergent.dataOptional());
        assertEquals(Optional.of("remote"), cache.getPending(uuid));

        final CaptureResult<String> confirmed = cache.capture(uuid, Optional.of("remote"), "direct mismatch");
        assertEquals(CaptureResult.Status.CAPTURED, confirmed.status());
        assertEquals(Optional.of("remote"), confirmed.dataOptional());
        assertTrue(cache.getPending(uuid).isEmpty());
    }

    @Test
    @DisplayName("Pending Data Survives Cache Expiry Until Confirmed")
    public void testPendingDataSurvivesCacheExpiryUntilConfirmed() {
        final AtomicLong now = new AtomicLong(1_000L);
        final ModSyncCache<String> cache = new ModSyncCache<>(100L, now::get);
        final UUID uuid = UUID.randomUUID();

        cache.storePending(uuid, "remote");
        now.addAndGet(101L);

        final CaptureResult<String> captured = cache.capture(uuid, Optional.of("local"), "direct mismatch");
        assertEquals(CaptureResult.Status.FALLBACK_PENDING, captured.status());
        assertEquals(Optional.of("remote"), captured.dataOptional());
        assertEquals(Optional.of("remote"), cache.getPending(uuid));

        final CaptureResult<String> confirmed = cache.capture(uuid, Optional.of("remote"), "direct mismatch");
        assertEquals(CaptureResult.Status.CAPTURED, confirmed.status());
        assertTrue(cache.getPending(uuid).isEmpty());
        assertEquals(Optional.of("remote"), cache.getTrusted(uuid));
    }

    @Test
    @DisplayName("Cached Data Expires When No Pending Data Exists")
    public void testCachedDataExpiresWhenNoPendingDataExists() {
        final AtomicLong now = new AtomicLong(1_000L);
        final ModSyncCache<String> cache = new ModSyncCache<>(100L, now::get);
        final UUID uuid = UUID.randomUUID();

        cache.storeTrusted(uuid, "remote");
        now.addAndGet(101L);

        final CaptureResult<String> captured = cache.capture(uuid, Optional.of("local"), "direct mismatch");
        assertEquals(CaptureResult.Status.CAPTURED, captured.status());
        assertEquals(Optional.of("local"), captured.dataOptional());
        assertEquals(Optional.of("local"), cache.getTrusted(uuid));
    }

    @Test
    @DisplayName("Trusted Data Survives Cached Data Expiry")
    public void testTrustedDataSurvivesCachedDataExpiry() {
        final AtomicLong now = new AtomicLong(1_000L);
        final ModSyncCache<String> cache = new ModSyncCache<>(100L, now::get);
        final UUID uuid = UUID.randomUUID();

        cache.storeTrusted(uuid, "remote");
        now.addAndGet(101L);

        final CaptureResult<String> fallback = cache.fallback(uuid, "no direct data");
        assertEquals(CaptureResult.Status.FALLBACK_TRUSTED, fallback.status());
        assertEquals(Optional.of("remote"), fallback.dataOptional());
    }

}
