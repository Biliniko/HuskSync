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

import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;

/**
 * Provides access to mod container data for displaying and editing mod inventories
 */
public interface ModDataProvider {

    /**
     * Returns if any mod integrations are currently available
     *
     * @return true if at least one integration is available
     */
    boolean hasAvailableIntegrations();

    /**
     * Get the IDs of available mod integrations
     *
     * @return integration IDs
     */
    @NotNull
    Set<String> getAvailableIntegrationIds();

    /**
     * Create a view of mod data for a given snapshot and integration type
     *
     * @param type     the integration ID
     * @param snapshot the snapshot to read from
     * @return the mod data view, if available
     */
    @NotNull
    Optional<ModDataView> getModDataView(@NotNull String type, @NotNull DataSnapshot.Unpacked snapshot);

    /**
     * Update a snapshot's mod data with edited items
     *
     * @param snapshot the snapshot to update
     * @param type     the integration ID
     * @param items    the edited items
     */
    void updateModData(@NotNull DataSnapshot.Unpacked snapshot, @NotNull String type,
                       @NotNull Data.Items.Items items);

}
