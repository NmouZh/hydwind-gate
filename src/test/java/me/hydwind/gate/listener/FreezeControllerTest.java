package me.hydwind.gate.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FreezeControllerTest {

    @Test
    void loginIsAllowedWhetherOrNotThePlayerIsAdmin() {
        assertTrue(FreezeController.isCommandAllowed("/login", false));
        assertTrue(FreezeController.isCommandAllowed("login HWG-AB2C", false));
        assertTrue(FreezeController.isCommandAllowed("/minecraft:login HWG-AB2C", false));
        assertTrue(FreezeController.isCommandAllowed("/LOGIN", true));
    }

    @Test
    void hydwindIsAllowedOnlyWithAdminPermission() {
        assertTrue(FreezeController.isCommandAllowed("/hydwind status", true));
        assertFalse(FreezeController.isCommandAllowed("/hydwind status", false));
        assertFalse(FreezeController.isCommandAllowed("/hydwind", false));
    }

    @Test
    void allOtherCommandsAreCancelled() {
        assertFalse(FreezeController.isCommandAllowed("/say hello", true));
        assertFalse(FreezeController.isCommandAllowed("/tp Player", true));
        assertFalse(FreezeController.isCommandAllowed("", true));
    }
}