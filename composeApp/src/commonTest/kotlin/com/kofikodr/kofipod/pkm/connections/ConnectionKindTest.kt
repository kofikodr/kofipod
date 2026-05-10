// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionKindTest {
    @Test fun wireRoundTrip() {
        ConnectionKind.entries.forEach { k ->
            assertEquals(k, ConnectionKind.fromWire(k.wire))
        }
    }

    @Test fun fromWireUnknownReturnsNull() {
        assertNull(ConnectionKind.fromWire("unknown"))
    }

    @Test fun wireValuesAreLowercaseStable() {
        assertEquals("markdown", ConnectionKind.Markdown.wire)
        assertEquals("obsidian", ConnectionKind.Obsidian.wire)
        assertEquals("readwise", ConnectionKind.Readwise.wire)
        assertEquals("notion", ConnectionKind.Notion.wire)
    }
}
