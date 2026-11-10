package org.Cry_Legende.sHShopGuiTop.network;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesBufferTest {

    @Test
    void salesOfTheSameItemAddUp() {
        SalesBuffer buffer = new SalesBuffer();
        buffer.add("cobblestone", "minecraft:cobblestone", 64, 32.0);
        buffer.add("cobblestone", "minecraft:cobblestone", 10, 5.0);
        Map<String, SalesBuffer.Delta> taken = buffer.drain();
        assertEquals(74, taken.get("cobblestone").sold());
        assertEquals(37.0, taken.get("cobblestone").revenue());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void failedWriteGivesBackWhatItTookWithoutLosingNewSales() {
        SalesBuffer buffer = new SalesBuffer();
        buffer.add("dirt", "minecraft:dirt", 5, 1.0);
        Map<String, SalesBuffer.Delta> taken = buffer.drain();
        buffer.add("dirt", "minecraft:dirt", 3, 0.5); // vendu pendant l'écriture qui échoue
        buffer.restore(taken);
        Map<String, SalesBuffer.Delta> again = buffer.drain();
        assertEquals(8, again.get("dirt").sold());
        assertEquals(1.5, again.get("dirt").revenue());
    }

    @Test
    void drainTwiceNeverCountsASaleTwice() {
        SalesBuffer buffer = new SalesBuffer();
        buffer.add("sand", "minecraft:sand", 1, 1.0);
        assertEquals(1, buffer.drain().size());
        assertTrue(buffer.drain().isEmpty());
    }
}
