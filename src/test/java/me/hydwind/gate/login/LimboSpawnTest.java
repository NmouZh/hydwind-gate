package me.hydwind.gate.login;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LimboSpawnTest {

    @Test
    void parsesConfiguredCoordinates() {
        LimboSpawn spawn = LimboSpawn.parse("1.5, 64, -3.5");

        assertEquals(1.5, spawn.x());
        assertEquals(64.0, spawn.y());
        assertEquals(-3.5, spawn.z());
        assertEquals(0.0f, spawn.yaw());
        assertEquals(0.0f, spawn.pitch());
    }

    @Test
    void trimsWhitespaceAroundComponents() {
        LimboSpawn spawn = LimboSpawn.parse("  0 , 64 , 0  ");

        assertEquals(0.0, spawn.x());
        assertEquals(64.0, spawn.y());
        assertEquals(0.0, spawn.z());
    }

    @Test
    void fallsBackToDefaultForMissingOrInvalidValues() {
        LimboSpawn expected = LimboSpawn.DEFAULT;
        assertEquals(expected, LimboSpawn.parse("1, 2"));
        assertEquals(expected, LimboSpawn.parse("abc, 64, 0"));
        assertEquals(expected, LimboSpawn.parse(""));
        assertEquals(expected, LimboSpawn.parse(null));
        assertEquals(expected, LimboSpawn.parse("1, NaN, 0"));
        assertEquals(expected, LimboSpawn.parse("1, Infinity, 0"));
    }
}