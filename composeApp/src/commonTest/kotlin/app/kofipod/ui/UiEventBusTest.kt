// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class UiEventBusTest {
    @Test
    fun `emit delivers Snackbar event to a single active subscriber`() =
        runTest {
            val bus = UiEventBus()
            val collected = async { bus.events.first() }
            // Yield so the collector subscribes before we emit; tryEmit() drops if no subscriber.
            yield()
            bus.emit(UiEvent.Snackbar("hello"))
            assertEquals(UiEvent.Snackbar("hello"), collected.await())
        }

    @Test
    fun `emit delivers the same event to multiple concurrent subscribers`() =
        runTest {
            val bus = UiEventBus()
            val a = async { bus.events.first() }
            val b = async { bus.events.first() }
            yield()
            bus.emit(UiEvent.Snackbar("broadcast"))
            assertEquals(UiEvent.Snackbar("broadcast"), a.await())
            assertEquals(UiEvent.Snackbar("broadcast"), b.await())
        }

    @Test
    fun `events emitted before any subscriber attaches are dropped — replay = 0`() =
        runTest {
            val bus = UiEventBus()
            // Emit BEFORE subscribing: the bus has replay = 0, so this event must not be
            // delivered to a subscriber that arrives later. This pins down the design choice
            // that prevents stale snackbars from popping up after navigation.
            bus.emit(UiEvent.Snackbar("ghost"))
            val collected = async { bus.events.first() }
            yield()
            bus.emit(UiEvent.Snackbar("live"))
            assertEquals(UiEvent.Snackbar("live"), collected.await())
        }

    @Test
    fun `multiple emissions to a single subscriber preserve order`() =
        runTest {
            val bus = UiEventBus()
            val collected = async { bus.events.take(3).toList() }
            yield()
            bus.emit(UiEvent.Snackbar("one"))
            bus.emit(UiEvent.Snackbar("two"))
            bus.emit(UiEvent.Snackbar("three"))
            assertEquals(
                listOf(
                    UiEvent.Snackbar("one"),
                    UiEvent.Snackbar("two"),
                    UiEvent.Snackbar("three"),
                ),
                collected.await(),
            )
        }
}
