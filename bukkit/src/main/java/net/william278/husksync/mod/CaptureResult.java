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
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public record CaptureResult<T>(@NotNull Status status, @Nullable T data, @NotNull String reason) {

    public enum Status {
        CAPTURED,
        FALLBACK_PENDING,
        FALLBACK_CACHED,
        FALLBACK_TRUSTED,
        UNAVAILABLE
    }

    @NotNull
    public Optional<T> dataOptional() {
        return Optional.ofNullable(data);
    }

    public boolean hasData() {
        return data != null;
    }

    public boolean isFallback() {
        return status == Status.FALLBACK_PENDING
                || status == Status.FALLBACK_CACHED
                || status == Status.FALLBACK_TRUSTED;
    }

    @NotNull
    public static <T> CaptureResult<T> captured(@NotNull T data) {
        return new CaptureResult<>(Status.CAPTURED, data, "captured");
    }

    @NotNull
    public static <T> CaptureResult<T> captured(@NotNull T data, @NotNull String reason) {
        return new CaptureResult<>(Status.CAPTURED, data, reason);
    }

    @NotNull
    public static <T> CaptureResult<T> fallbackPending(@NotNull T data, @NotNull String reason) {
        return new CaptureResult<>(Status.FALLBACK_PENDING, data, reason);
    }

    @NotNull
    public static <T> CaptureResult<T> fallbackCached(@NotNull T data, @NotNull String reason) {
        return new CaptureResult<>(Status.FALLBACK_CACHED, data, reason);
    }

    @NotNull
    public static <T> CaptureResult<T> fallbackTrusted(@NotNull T data, @NotNull String reason) {
        return new CaptureResult<>(Status.FALLBACK_TRUSTED, data, reason);
    }

    @NotNull
    public static <T> CaptureResult<T> unavailable(@NotNull String reason) {
        return new CaptureResult<>(Status.UNAVAILABLE, null, reason);
    }

}
