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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Curios Integration Tests")
public class CuriosIntegrationTests {

    @Test
    @DisplayName("Native Payload Metadata Is Separated From Item Slots")
    public void testNativePayloadMetadataIsSeparatedFromItemSlots() {
        final String payload = "{Curios:[{Identifier:\"ring\",StacksHandler:{Visible:1b}}]}";
        final ModSlotData metadata = new ModSlotData("__curios_native__", -1, null, null, null, payload);
        final ModSlotData ring = new ModSlotData("ring", 0, "{id:\"minecraft:air\",Count:1b}", null, null);
        final ModSlotData cosmetic = new ModSlotData("ring:cosmetic", 0, null, null, null);
        final List<ModSlotData> slots = List.of(metadata, ring, cosmetic);

        assertTrue(metadata.isMetadata());
        assertFalse(ring.isMetadata());
        assertEquals(payload, CuriosIntegration.extractNativePayload(slots).orElseThrow());
        assertEquals(List.of(ring, cosmetic), CuriosIntegration.itemSlots(slots));
    }

    @Test
    @DisplayName("Legacy Mod Slot Data Has No Native Payload")
    public void testLegacyModSlotDataHasNoNativePayload() {
        final ModSlotData legacy = new ModSlotData("amulet", 0, null, null, null);

        assertFalse(legacy.isMetadata());
        assertNull(legacy.nativeNbt());
        assertTrue(CuriosIntegration.extractNativePayload(List.of(legacy)).isEmpty());
    }

    @Test
    @DisplayName("Legacy Item Only Snapshot Deserializes Without Native Payload")
    public void testLegacyItemOnlySnapshotDeserializesWithoutNativePayload() {
        final Type type = new TypeToken<Map<String, List<ModSlotData>>>() { }.getType();
        final Map<String, List<ModSlotData>> data = new Gson().fromJson("""
                {
                  "curios": [
                    {
                      "slotKey": "ring",
                      "slotIndex": 0,
                      "itemNbt": "{id:\\\"minecraft:diamond\\\",Count:1b}",
                      "skinArmor": null,
                      "hiddenFlags": null
                    }
                  ]
                }
                """, type);

        final ModSlotData slot = data.get("curios").get(0);
        assertEquals("ring", slot.slotKey());
        assertEquals(0, slot.slotIndex());
        assertNull(slot.nativeNbt());
        assertFalse(slot.isMetadata());
    }

}
