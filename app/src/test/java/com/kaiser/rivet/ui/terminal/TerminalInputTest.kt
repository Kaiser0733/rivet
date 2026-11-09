package com.kaiser.rivet.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalInputTest {
    @Test fun ctrlThenCProducesTheInterruptByte() {
        assertEquals("\u0003", terminalText("c", ctrl = true, alt = false))
        assertEquals("\u0003", terminalText("C", ctrl = true, alt = false))
    }

    @Test fun altThenALetterProducesTheMetaSequence() {
        assertEquals("\u001ba", terminalText("a", ctrl = false, alt = true))
        assertEquals("\u001bA", terminalText("A", ctrl = false, alt = true))
    }

    @Test fun enterBecomesCarriageReturnAndTextIsOtherwiseExact() {
        assertEquals("ls\r", terminalText("ls\n", ctrl = false, alt = false))
        assertEquals("printf hi", terminalText("printf hi", ctrl = false, alt = false))
    }

    @Test fun controlCodesCoverTheShellEditingKeys() {
        assertEquals('\u0000', controlCode(' '))
        assertEquals('\u0000', controlCode('@'))
        assertEquals('\u001b', controlCode('['))
        assertEquals('\u001c', controlCode('\\'))
        assertEquals('\u001d', controlCode(']'))
        assertEquals('\u001e', controlCode('^'))
        assertEquals('\u001f', controlCode('_'))
        assertEquals('\u007f', controlCode('?'))
    }

    @Test fun gridSizeUsesTheCellSizeAndRespectsTheEmulatorMinimum() {
        assertEquals(100 to 30, terminalGridSize(800f, 480f, 8f, 16f))
        assertEquals(2 to 2, terminalGridSize(1f, 1f, 8f, 16f))
        assertEquals(80 to 24, terminalGridSize(800f, 480f, 0f, 0f))
    }
}
