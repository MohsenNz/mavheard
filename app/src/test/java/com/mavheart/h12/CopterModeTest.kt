package com.mavheart.h12

import org.junit.Assert.*
import org.junit.Test

class CopterModeTest {
    @Test fun namesForKnownAndUnknownModes() {
        assertEquals("Stabilize", copterModeName(0))
        assertEquals("Loiter", copterModeName(5))
        assertEquals("Land", copterModeName(9))
        assertEquals("PosHold", copterModeName(16))
        assertEquals("Smart RTL", copterModeName(21))
        assertEquals("Mode 42", copterModeName(42))
    }

    @Test fun modeIdsAreUnique() {
        assertEquals(CopterMode.entries.size, CopterMode.entries.map { it.id }.toSet().size)
    }

    @Test fun copterTypes() {
        for (t in listOf(2, 3, 4, 13, 14, 15, 29, 35)) assertTrue("type $t", isCopterType(t))
        for (t in listOf(1, 10, 11, 12, 20)) assertFalse("type $t", isCopterType(t)) // plane, rover, boat, ...
    }

    @Test fun selectableSubsetIsTheSafeOne() {
        val ids = CopterMode.selectableModes.map { it.label }.toSet()
        assertEquals(
            setOf("Stabilize", "AltHold", "Loiter", "PosHold", "Land", "RTL", "Smart RTL",
                "Brake", "Guided", "Auto", "Circle", "Drift", "Sport"),
            ids,
        )
        for (m in listOf(CopterMode.FLIP, CopterMode.ACRO, CopterMode.THROW, CopterMode.AUTOTUNE))
            assertFalse(m.label, m.selectable)
    }
}
