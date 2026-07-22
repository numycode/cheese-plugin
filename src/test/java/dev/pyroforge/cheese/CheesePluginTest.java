package dev.pyroforge.cheese;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CheesePluginTest {

    @Test
    void toolchainSmokeTest() {
        assertTrue(Runtime.version().feature() >= 25);
    }
}
