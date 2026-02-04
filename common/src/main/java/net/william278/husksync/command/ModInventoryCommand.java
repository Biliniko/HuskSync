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

package net.william278.husksync.command;

import de.themoep.minedown.adventure.MineDown;
import net.william278.husksync.HuskSync;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.mod.ModDataProvider;
import net.william278.husksync.mod.ModDataView;
import net.william278.husksync.redis.RedisManager;
import net.william278.husksync.user.CommandUser;
import net.william278.husksync.user.OnlineUser;
import net.william278.husksync.user.User;
import net.william278.uniform.BaseCommand;
import net.william278.uniform.Permission;
import net.william278.uniform.element.ArgumentElement;
import org.jetbrains.annotations.NotNull;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ModInventoryCommand extends PluginCommand {

    private final DataSnapshot.SaveCause saveCause = DataSnapshot.SaveCause.MOD_CONTAINER_COMMAND;

    public ModInventoryCommand(@NotNull HuskSync plugin) {
        super("modinv", List.of("modinventory", "modcontainer"), Permission.Default.IF_OP,
                ExecutionScope.IN_GAME, plugin);
    }

    @Override
    public void provide(@NotNull BaseCommand<?> command) {
        command.addSyntax((ctx) -> {
            final String type = ctx.getArgument("type", String.class);
            final User user = ctx.getArgument("username", User.class);
            final CommandUser executor = user(command, ctx);
            if (!(executor instanceof OnlineUser online)) {
                plugin.getLocales().getLocale("error_in_game_command_only")
                        .ifPresent(executor::sendMessage);
                return;
            }
            this.showLatestItems(online, user, type);
        }, modType("type"), user("username"));
        command.addSyntax((ctx) -> {
            final String type = ctx.getArgument("type", String.class);
            final User user = ctx.getArgument("username", User.class);
            final UUID version = ctx.getArgument("version", UUID.class);
            final CommandUser executor = user(command, ctx);
            if (!(executor instanceof OnlineUser online)) {
                plugin.getLocales().getLocale("error_in_game_command_only")
                        .ifPresent(executor::sendMessage);
                return;
            }
            this.showSnapshotItems(online, user, type, version);
        }, modType("type"), user("username"), versionUuid());
    }

    private <S> ArgumentElement<S, String> modType(@NotNull String name) {
        return new ArgumentElement<>(name, reader -> reader.readString(), (context, builder) -> {
            plugin.getModDataProvider()
                    .ifPresent(provider -> provider.getAvailableIntegrationIds().forEach(builder::suggest));
            return builder.buildFuture();
        });
    }

    private Optional<ModDataProvider> getProvider(@NotNull OnlineUser viewer, @NotNull String type) {
        final Optional<ModDataProvider> optional = plugin.getModDataProvider();
        if (optional.isEmpty() || !optional.get().hasAvailableIntegrations()) {
            plugin.getLocales().getLocale("error_modinv_unavailable")
                    .ifPresent(viewer::sendMessage);
            return Optional.empty();
        }
        final ModDataProvider provider = optional.get();
        if (provider.getAvailableIntegrationIds().stream().noneMatch(id -> id.equalsIgnoreCase(type))) {
            plugin.getLocales().getLocale("error_modinv_unknown_type", type)
                    .ifPresent(viewer::sendMessage);
            return Optional.empty();
        }
        return Optional.of(provider);
    }

    private void showLatestItems(@NotNull OnlineUser viewer, @NotNull User user, @NotNull String type) {
        final Optional<ModDataProvider> provider = getProvider(viewer, type);
        if (provider.isEmpty()) {
            return;
        }
        plugin.getRedisManager().getOnlineUserData(user.getUuid(), user, saveCause).thenAccept(d -> d
                .or(() -> plugin.getDatabase().getLatestSnapshot(user))
                .or(() -> {
                    plugin.getLocales().getLocale("error_no_data_to_display")
                            .ifPresent(viewer::sendMessage);
                    return Optional.empty();
                })
                .flatMap(packed -> {
                    if (packed.isInvalid()) {
                        plugin.getLocales().getLocale("error_invalid_data", packed.getInvalidReason(plugin))
                                .ifPresent(viewer::sendMessage);
                        return Optional.empty();
                    }
                    return Optional.of(packed.unpack(plugin));
                })
                .ifPresent(snapshot -> this.showItems(
                        viewer, snapshot, user, type, provider.get(),
                        viewer.hasPermission(getPermission("edit"))
                )));
    }

    private void showSnapshotItems(@NotNull OnlineUser viewer, @NotNull User user,
                                   @NotNull String type, @NotNull UUID version) {
        final Optional<ModDataProvider> provider = getProvider(viewer, type);
        if (provider.isEmpty()) {
            return;
        }
        plugin.getDatabase().getSnapshot(user, version)
                .or(() -> {
                    plugin.getLocales().getLocale("error_invalid_version_uuid")
                            .ifPresent(viewer::sendMessage);
                    return Optional.empty();
                })
                .flatMap(packed -> {
                    if (packed.isInvalid()) {
                        plugin.getLocales().getLocale("error_invalid_data", packed.getInvalidReason(plugin))
                                .ifPresent(viewer::sendMessage);
                        return Optional.empty();
                    }
                    return Optional.of(packed.unpack(plugin));
                })
                .ifPresent(snapshot -> this.showItems(viewer, snapshot, user, type, provider.get(), false));
    }

    private void showItems(@NotNull OnlineUser viewer, @NotNull DataSnapshot.Unpacked snapshot,
                           @NotNull User user, @NotNull String type, @NotNull ModDataProvider provider,
                           boolean allowEdit) {
        final Optional<ModDataView> view = provider.getModDataView(type, snapshot);
        if (view.isEmpty()) {
            plugin.getLocales().getLocale("error_no_data_to_display")
                    .ifPresent(viewer::sendMessage);
            return;
        }

        final ModDataView modDataView = view.get();
        plugin.getLocales().getLocale("modinv_viewer_opened", user.getName(), modDataView.displayName(),
                        snapshot.getTimestamp().format(DateTimeFormatter
                                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)))
                .ifPresent(viewer::sendMessage);

        viewer.showGui(
                modDataView.items(),
                plugin.getLocales().getLocale("modinv_viewer_menu_title", user.getName(),
                                modDataView.displayName())
                        .orElse(new MineDown(String.format("%s's %s", user.getName(), modDataView.displayName()))),
                allowEdit,
                modDataView.size(),
                (itemsOnClose) -> {
                    if (allowEdit && !modDataView.items().equals(itemsOnClose)) {
                        plugin.runAsync(() -> this.updateItems(viewer, itemsOnClose, user, type, provider));
                    }
                }
        );
    }

    private void updateItems(@NotNull OnlineUser viewer, @NotNull Data.Items.Items items,
                             @NotNull User holder, @NotNull String type, @NotNull ModDataProvider provider) {
        final Optional<DataSnapshot.Packed> latestData = plugin.getDatabase().getLatestSnapshot(holder);
        if (latestData.isEmpty()) {
            plugin.getLocales().getLocale("error_no_data_to_display")
                    .ifPresent(viewer::sendMessage);
            return;
        }

        final DataSnapshot.Packed snapshot = latestData.get().copy();
        final boolean pin = plugin.getSettings().getSynchronization().doAutoPin(saveCause);
        snapshot.edit(plugin, (data) -> {
            provider.updateModData(data, type, items);
            data.setSaveCause(saveCause);
            data.setPinned(pin);
        });

        final RedisManager redis = plugin.getRedisManager();
        plugin.getDataSyncer().saveData(holder, snapshot, (user, data) -> {
            redis.getUserData(user).ifPresent(d -> redis.setUserData(user, snapshot));
            redis.sendUserDataUpdate(user, data);
        });
    }

}
