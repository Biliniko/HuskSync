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

import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Iron's Spellbooks Integration Tests")
public class IronsSpellbooksIntegrationTests {

    @Test
    @DisplayName("Magic Data Sanitization Keeps Progression And Clears Runtime State")
    public void testMagicDataSanitizationKeepsProgressionAndClearsRuntimeState() {
        final FakeNbt selection = new FakeNbt()
                .with("slot", "mainhand")
                .with("index", 1)
                .with("lastSlot", "offhand")
                .with("lastIndex", 0);
        final FakeNbt tag = new FakeNbt()
                .with("mana", 42)
                .with("cooldowns", List.of("cooldown"))
                .with("recasts", List.of("recast"))
                .with("isCasting", true)
                .with("castingSpellId", "irons_spellbooks:fireball")
                .with("castingEquipmentSlot", "mainhand")
                .with("castingSpellLevel", 4)
                .with("heartStopAccumulatedDamage", 7.5f)
                .with("evasionHitsRemaining", 2)
                .with("learnedSpells", List.of("irons_spellbooks:fireball", "irons_spellbooks:heal"))
                .with("spellSelection", selection)
                .with("irons_spellbooks_data", new FakeNbt())
                .with("unknown_key", 1);

        IronsSpellbooksIntegration.sanitizeMagicData(tag.proxy());

        assertTrue(tag.values.containsKey("mana"));
        assertTrue(tag.values.containsKey("cooldowns"));
        assertTrue(tag.values.containsKey("learnedSpells"));
        assertTrue(tag.values.containsKey("spellSelection"));
        assertFalse(tag.values.containsKey("recasts"));
        assertFalse(tag.values.containsKey("heartStopAccumulatedDamage"));
        assertFalse(tag.values.containsKey("evasionHitsRemaining"));
        assertFalse(tag.values.containsKey("irons_spellbooks_data"));
        assertFalse(tag.values.containsKey("unknown_key"));
        assertEquals(false, tag.values.get("isCasting"));
        assertEquals("", tag.values.get("castingSpellId"));
        assertEquals("", tag.values.get("castingEquipmentSlot"));
        assertEquals(0, tag.values.get("castingSpellLevel"));
        assertEquals(List.of("irons_spellbooks:fireball", "irons_spellbooks:heal"), tag.values.get("learnedSpells"));
        assertSame(selection, tag.values.get("spellSelection"));
        assertEquals("mainhand", selection.values.get("slot"));
        assertEquals(1, selection.values.get("index"));
        assertEquals("offhand", selection.values.get("lastSlot"));
        assertEquals(0, selection.values.get("lastIndex"));
    }

    @Test
    @DisplayName("Magic Data Sanitization Rejects Missing Persistent Payload")
    public void testMagicDataSanitizationRejectsMissingPersistentPayload() {
        final FakeNbt tag = new FakeNbt()
                .with("isCasting", true)
                .with("castingSpellId", "irons_spellbooks:fireball");

        IronsSpellbooksIntegration.sanitizeMagicData(tag.proxy());

        assertTrue(tag.values.isEmpty());
        assertFalse(IronsSpellbooksIntegration.hasRequiredMagicData(tag.proxy()));
    }

    private static final class FakeNbt implements InvocationHandler {

        private final Map<String, Object> values = new LinkedHashMap<>();
        private ReadWriteNBT proxy;

        private FakeNbt with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        private ReadWriteNBT proxy() {
            if (proxy == null) {
                proxy = (ReadWriteNBT) Proxy.newProxyInstance(
                        ReadWriteNBT.class.getClassLoader(),
                        new Class<?>[]{ReadWriteNBT.class},
                        this
                );
            }
            return proxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getKeys" -> values.keySet();
                case "hasTag" -> values.containsKey((String) args[0]);
                case "removeKey" -> {
                    values.remove((String) args[0]);
                    yield null;
                }
                case "setInteger", "setBoolean", "setString" -> {
                    values.put((String) args[0], args[1]);
                    yield null;
                }
                case "getOrCreateCompound" -> {
                    final String key = (String) args[0];
                    final Object existing = values.get(key);
                    if (existing instanceof FakeNbt fake) {
                        yield fake.proxy();
                    }
                    final FakeNbt created = new FakeNbt();
                    values.put(key, created);
                    yield created.proxy();
                }
                case "toString" -> values.toString();
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

    }

}
