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

/**
 * Represents a single mod container slot item
 *
 * @param slotKey     the mod-specific slot key
 * @param slotIndex   the slot index within the slot key
 * @param itemNbt     serialized item stack NBT, or null if empty
 * @param skinArmor   cosmetic armor skin flag (CosmeticArmorReworked), or null if not applicable
 * @param hiddenFlags hidden render flags (CosmeticArmorReworked), or null if not applicable
 * @param nativeNbt   serialized native integration NBT payload, or null if not applicable
 */
public record ModSlotData(@NotNull String slotKey, int slotIndex, @Nullable String itemNbt,
                          @Nullable Boolean skinArmor, @Nullable java.util.List<String> hiddenFlags,
                          @Nullable String nativeNbt) {

    public ModSlotData(@NotNull String slotKey, int slotIndex, @Nullable String itemNbt,
                       @Nullable Boolean skinArmor, @Nullable java.util.List<String> hiddenFlags) {
        this(slotKey, slotIndex, itemNbt, skinArmor, hiddenFlags, null);
    }

    public boolean isMetadata() {
        return slotIndex < 0;
    }

}
