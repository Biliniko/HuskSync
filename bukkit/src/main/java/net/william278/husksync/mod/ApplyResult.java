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

public record ApplyResult(@NotNull Status status, @NotNull String reason) {

    public enum Status {
        SUCCESS,
        PENDING_CONFIRMATION,
        FAILED,
        SKIPPED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isPendingConfirmation() {
        return status == Status.PENDING_CONFIRMATION;
    }

    @NotNull
    public static ApplyResult success(@NotNull String reason) {
        return new ApplyResult(Status.SUCCESS, reason);
    }

    @NotNull
    public static ApplyResult pending(@NotNull String reason) {
        return new ApplyResult(Status.PENDING_CONFIRMATION, reason);
    }

    @NotNull
    public static ApplyResult failed(@NotNull String reason) {
        return new ApplyResult(Status.FAILED, reason);
    }

    @NotNull
    public static ApplyResult skipped(@NotNull String reason) {
        return new ApplyResult(Status.SKIPPED, reason);
    }

}
