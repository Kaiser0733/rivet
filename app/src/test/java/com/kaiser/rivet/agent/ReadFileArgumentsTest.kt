package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadFileArgumentsTest {
    @Test fun acceptsTheSchemaIntegerInProviderNumberNotation() {
        assertEquals(0L, readFileArguments("""{"path":"index.html"}""").offset)
        assertEquals(ReadFileArguments("index.html", 0), readFileArguments("""{"path":"index.html","offset":0.0}"""))
        assertEquals(120L, readFileArguments("""{"path":"index.html","offset":1.2e2}""").offset)
    }

    @Test fun rejectsValuesOutsideTheDeclaredContract() {
        listOf("0.5", "9223372036854775808", "-1", "null", "true", "\"0\"").forEach { offset ->
            assertThrows(IllegalArgumentException::class.java) {
                readFileArguments("""{"path":"index.html","offset":$offset}""")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            readFileArguments("""{"path":"index.html","extra":true}""")
        }
    }
}
