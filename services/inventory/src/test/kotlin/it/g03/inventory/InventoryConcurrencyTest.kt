package it.g03.inventory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "two customers must not buy the last seat" test (Pillar B, chaos #2 precondition).
 * N concurrent transactions race for 1 remaining seat: the atomic conditional UPDATE must
 * let EXACTLY ONE win, regardless of interleaving. This is the in-process proof of the
 * invariant; the chaos experiment later reproduces it under pod kill in the cluster.
 */
@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=localhost:9092"])
class InventoryConcurrencyTest {

    @Autowired lateinit var service: InventoryService
    @Autowired lateinit var items: InventoryItemRepository
    @Autowired lateinit var reservations: ReservationRepository

    private val pool = Executors.newFixedThreadPool(8)

    @BeforeEach
    fun cleanSlate() {
        reservations.deleteAll()
        items.deleteAll()
    }

    @AfterEach
    fun shutdownPool() {
        pool.shutdownNow()
    }

    @Test
    fun `only one of 8 concurrent customers gets the last seat`() {
        items.save(InventoryItem(productId = "LAST-SEAT", totalSeats = 1, available = 1))
        val start = CountDownLatch(1)

        val futures = (1..8).map { i ->
            pool.submit(
                Callable {
                    start.await()
                    // each call runs in its own transaction (service proxy entry point)
                    runCatching { service.reserve("order-$i", "LAST-SEAT", 1) }
                },
            )
        }
        start.countDown()
        val results = futures.map { it.get() }

        val outcomes = results.mapNotNull { it.getOrNull() }
        val winners = outcomes.count { it.reservation.status == ReservationStatus.RESERVED }
        val rejected = outcomes.count { it.reservation.status == ReservationStatus.REJECTED }

        assertEquals(1, winners, "exactly one customer may win the last seat")
        assertEquals(outcomes.size - 1, rejected, "everyone else must be told sold-out")
        assertEquals(0, items.findById("LAST-SEAT").orElseThrow().available)
        assertTrue(
            items.findById("LAST-SEAT").orElseThrow().available >= 0,
            "never oversell: availability can never go negative",
        )
    }

    @Test
    fun `concurrent duplicates of the SAME order decrement exactly once`() {
        items.save(InventoryItem(productId = "DUP-SEAT", totalSeats = 5, available = 5))
        val start = CountDownLatch(1)

        // 8 concurrent deliveries of the same logical operation (orderId, productId):
        // the unique constraint + same-transaction decrement guarantee a single commit;
        // losers throw (constraint) and at-least-once redelivery would replay them.
        val futures = (1..8).map {
            pool.submit(
                Callable {
                    start.await()
                    runCatching { service.reserve("order-dup", "DUP-SEAT", 1) }
                },
            )
        }
        start.countDown()
        val results = futures.map { it.get() }

        val committed = results.mapNotNull { it.getOrNull() }
        assertTrue(committed.isNotEmpty(), "at least one delivery must commit")
        assertEquals(4, items.findById("DUP-SEAT").orElseThrow().available, "one decrement only")
        assertEquals(1, reservations.findAll().count { it.orderId == "order-dup" })
    }
}
