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
import net.william278.husksync.adapter.Adaptable;
import net.william278.husksync.data.BukkitData;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.data.Serializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class BukkitModSyncRegistry {

    private final BukkitHuskSync plugin;
    private final List<Entry<?>> entries;

    public BukkitModSyncRegistry(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        this.entries = List.of(
                new Entry<>(
                        Identifier.SOLCARROT_FOODLIST,
                        BukkitData.SolCarrotFoodList.class,
                        this::isSolCarrotAvailable,
                        this::captureSolCarrot,
                        this::applySolCarrot
                ),
                new Entry<>(
                        Identifier.ULTIMINE_ABILITY,
                        BukkitData.UltimineAbility.class,
                        this::isUltimineAvailable,
                        this::captureUltimine,
                        this::applyUltimine
                ),
                new Entry<>(
                        Identifier.MNA_PLAYERDATA,
                        BukkitData.MnaPlayerData.class,
                        this::isMnaAvailable,
                        this::captureMnaCaps,
                        this::applyMnaCaps
                ),
                new Entry<>(
                        Identifier.MNA_PERSISTENT_DATA,
                        BukkitData.MnaPersistentData.class,
                        this::isMnaAvailable,
                        this::captureMnaPersistent,
                        this::applyMnaPersistent
                ),
                new Entry<>(
                        Identifier.ARS_NOUVEAU_PLAYERDATA,
                        BukkitData.ArsNouveauPlayerData.class,
                        this::isArsNouveauAvailable,
                        this::captureArsNouveauCaps,
                        this::applyArsNouveauCaps
                ),
                new Entry<>(
                        Identifier.ARS_NOUVEAU_PERSISTENT_DATA,
                        BukkitData.ArsNouveauPersistentData.class,
                        this::isArsNouveauAvailable,
                        this::captureArsNouveauPersistent,
                        this::applyArsNouveauPersistent
                )
        );
    }

    public boolean hasAvailableIntegrations() {
        return entries.stream().anyMatch(Entry::isAvailable);
    }

    public void registerSerializers() {
        entries.stream()
                .filter(Entry::isAvailable)
                .forEach(entry -> entry.registerSerializer(plugin));
    }

    @NotNull
    public Optional<? extends Data> capture(@NotNull Identifier identifier, @NotNull Player player) {
        return entries.stream()
                .filter(entry -> entry.identifier().equals(identifier))
                .findFirst()
                .filter(Entry::isAvailable)
                .flatMap(entry -> entry.capture(player));
    }

    @NotNull
    public ApplyResult apply(@NotNull Identifier identifier, @NotNull Player player, @NotNull Data data) {
        return entries.stream()
                .filter(entry -> entry.identifier().equals(identifier))
                .findFirst()
                .map(entry -> {
                    final ApplyResult result = entry.apply(player, data);
                    logApplyResult(identifier, player, result);
                    return result;
                })
                .orElseGet(() -> ApplyResult.skipped("unknown mod sync identifier"));
    }

    public void cachePlayerData(@NotNull Player player) {
        if (isSolCarrotAvailable()) {
            cacheSolCarrot(player);
        }
        if (isUltimineAvailable()) {
            cacheUltimine(player);
        }
        if (isMnaAvailable()) {
            cacheMna(player);
        }
        if (isArsNouveauAvailable()) {
            cacheArsNouveau(player);
        }
    }

    private boolean isSolCarrotAvailable() {
        final SolCarrotIntegration integration = plugin.getSolCarrotIntegration();
        return integration != null && integration.isAvailable();
    }

    @NotNull
    private Optional<BukkitData.SolCarrotFoodList> captureSolCarrot(@NotNull Player player) {
        final SolCarrotIntegration integration = plugin.getSolCarrotIntegration();
        if (integration == null || !integration.isAvailable()) {
            return Optional.empty();
        }
        return integration.capture(player).map(BukkitData.SolCarrotFoodList::from);
    }

    @NotNull
    private ApplyResult applySolCarrot(@NotNull Player player, @NotNull BukkitData.SolCarrotFoodList data) {
        final SolCarrotIntegration integration = plugin.getSolCarrotIntegration();
        if (integration == null) {
            return ApplyResult.skipped("solcarrot integration missing");
        }
        return integration.apply(player, data.getNbt());
    }

    private void cacheSolCarrot(@NotNull Player player) {
        final SolCarrotIntegration integration = plugin.getSolCarrotIntegration();
        if (integration != null) {
            integration.cachePlayerData(player);
        }
    }

    private boolean isUltimineAvailable() {
        final UltimineIntegration integration = plugin.getUltimineIntegration();
        return integration != null && integration.isAvailable();
    }

    @NotNull
    private Optional<BukkitData.UltimineAbility> captureUltimine(@NotNull Player player) {
        final UltimineIntegration integration = plugin.getUltimineIntegration();
        if (integration == null || !integration.isAvailable()) {
            plugin.debug("Ultimine capture skipped (integration unavailable) for " + player.getName());
            return Optional.empty();
        }
        return integration.capture(player).map(BukkitData.UltimineAbility::from);
    }

    @NotNull
    private ApplyResult applyUltimine(@NotNull Player player, @NotNull BukkitData.UltimineAbility data) {
        final UltimineIntegration integration = plugin.getUltimineIntegration();
        if (integration == null) {
            return ApplyResult.skipped("ultimine integration missing");
        }
        plugin.debug("Ultimine apply snapshot value=" + data.isCanUltimine() + " for " + player.getName());
        return integration.apply(player, data.isCanUltimine());
    }

    private void cacheUltimine(@NotNull Player player) {
        final UltimineIntegration integration = plugin.getUltimineIntegration();
        if (integration != null) {
            plugin.debug("Ultimine logout pre-cache for " + player.getName());
            integration.cachePlayerData(player);
        }
    }

    private boolean isMnaAvailable() {
        final MnaIntegration integration = plugin.getMnaIntegration();
        return integration != null && integration.isAvailable();
    }

    @NotNull
    private Optional<BukkitData.MnaPlayerData> captureMnaCaps(@NotNull Player player) {
        final MnaIntegration integration = plugin.getMnaIntegration();
        if (integration == null || !integration.isAvailable()) {
            plugin.debug("MNA capture skipped (integration unavailable) for " + player.getName());
            return Optional.empty();
        }
        return integration.captureCaps(player).map(BukkitData.MnaPlayerData::from);
    }

    @NotNull
    private ApplyResult applyMnaCaps(@NotNull Player player, @NotNull BukkitData.MnaPlayerData data) {
        final MnaIntegration integration = plugin.getMnaIntegration();
        if (integration == null) {
            return ApplyResult.skipped("mna integration missing");
        }
        return integration.applyCaps(player, data.getCapsNbt());
    }

    @NotNull
    private Optional<BukkitData.MnaPersistentData> captureMnaPersistent(@NotNull Player player) {
        final MnaIntegration integration = plugin.getMnaIntegration();
        if (integration == null || !integration.isAvailable()) {
            plugin.debug("MNA persistent capture skipped (integration unavailable) for " + player.getName());
            return Optional.empty();
        }
        return integration.capturePersistent(player).map(BukkitData.MnaPersistentData::from);
    }

    @NotNull
    private ApplyResult applyMnaPersistent(@NotNull Player player, @NotNull BukkitData.MnaPersistentData data) {
        final MnaIntegration integration = plugin.getMnaIntegration();
        if (integration == null) {
            return ApplyResult.skipped("mna integration missing");
        }
        return integration.applyPersistent(player, data.getNbt());
    }

    private void cacheMna(@NotNull Player player) {
        final MnaIntegration integration = plugin.getMnaIntegration();
        if (integration != null) {
            plugin.debug("MNA logout pre-cache for " + player.getName());
            integration.cachePlayerData(player);
        }
    }

    private boolean isArsNouveauAvailable() {
        final ArsNouveauIntegration integration = plugin.getArsNouveauIntegration();
        return integration != null && integration.isAvailable();
    }

    @NotNull
    private Optional<BukkitData.ArsNouveauPlayerData> captureArsNouveauCaps(@NotNull Player player) {
        final ArsNouveauIntegration integration = plugin.getArsNouveauIntegration();
        if (integration == null || !integration.isAvailable()) {
            plugin.debug("Ars Nouveau capture skipped (integration unavailable) for " + player.getName());
            return Optional.empty();
        }
        return integration.captureCaps(player).map(BukkitData.ArsNouveauPlayerData::from);
    }

    @NotNull
    private ApplyResult applyArsNouveauCaps(@NotNull Player player, @NotNull BukkitData.ArsNouveauPlayerData data) {
        final ArsNouveauIntegration integration = plugin.getArsNouveauIntegration();
        if (integration == null) {
            return ApplyResult.skipped("ars nouveau integration missing");
        }
        return integration.applyCaps(player, data.getCapsNbt());
    }

    @NotNull
    private Optional<BukkitData.ArsNouveauPersistentData> captureArsNouveauPersistent(@NotNull Player player) {
        final ArsNouveauIntegration integration = plugin.getArsNouveauIntegration();
        if (integration == null || !integration.isAvailable()) {
            plugin.debug("Ars Nouveau persistent capture skipped (integration unavailable) for " + player.getName());
            return Optional.empty();
        }
        return integration.capturePersistent(player).map(BukkitData.ArsNouveauPersistentData::from);
    }

    @NotNull
    private ApplyResult applyArsNouveauPersistent(@NotNull Player player,
                                                  @NotNull BukkitData.ArsNouveauPersistentData data) {
        final ArsNouveauIntegration integration = plugin.getArsNouveauIntegration();
        if (integration == null) {
            return ApplyResult.skipped("ars nouveau integration missing");
        }
        return integration.applyPersistent(player, data.getNbt());
    }

    private void cacheArsNouveau(@NotNull Player player) {
        final ArsNouveauIntegration integration = plugin.getArsNouveauIntegration();
        if (integration != null) {
            plugin.debug("Ars Nouveau logout pre-cache for " + player.getName());
            integration.cachePlayerData(player);
        }
    }

    private void logApplyResult(@NotNull Identifier identifier, @NotNull Player player, @NotNull ApplyResult result) {
        if (result.status() == ApplyResult.Status.SUCCESS || result.status() == ApplyResult.Status.PENDING_CONFIRMATION) {
            plugin.debug("Mod sync apply " + result.status().name().toLowerCase()
                    + " for " + identifier.getKeyValue() + " on " + player.getName()
                    + " (" + result.reason() + ")");
            return;
        }
        plugin.debug("Mod sync apply " + result.status().name().toLowerCase()
                + " for " + identifier.getKeyValue() + " on " + player.getName()
                + " (" + result.reason() + ")");
    }

    private record Entry<T extends BukkitData & Adaptable>(
            @NotNull Identifier identifier,
            @NotNull Class<T> dataType,
            @NotNull BooleanSupplier available,
            @NotNull Function<Player, Optional<T>> capture,
            @NotNull BiFunction<Player, T, ApplyResult> apply
    ) {

        boolean isAvailable() {
            return available.getAsBoolean();
        }

        @NotNull
        Optional<T> capture(@NotNull Player player) {
            return capture.apply(player);
        }

        @NotNull
        ApplyResult apply(@NotNull Player player, @NotNull Data data) {
            if (!dataType.isInstance(data)) {
                return ApplyResult.failed("unexpected data type: " + data.getClass().getSimpleName());
            }
            return apply.apply(player, dataType.cast(data));
        }

        void registerSerializer(@NotNull BukkitHuskSync plugin) {
            plugin.registerSerializer(identifier, new Serializer.Json<>(plugin, dataType));
        }

    }

}
