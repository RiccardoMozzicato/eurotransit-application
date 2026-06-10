package it.tonicminds.inventory

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=localhost:9092"])
class InventoryServiceTest {

    @Autowired lateinit var service: InventoryService
    @Autowired lateinit var items: InventoryItemRepository
    @Autowired lateinit var reservations: ReservationRepository

    private fun available(productId: String) = items.findById(productId).orElseThrow().available

    @BeforeEach
    fun cleanSlate() {
        reservations.deleteAll()
        items.deleteAll()
        items.save(InventoryItem(productId = "TRENO-TEST", totalSeats = 10, available = 10))
    }

    @Test
    fun `reserve decrements availability and records RESERVED`() {
        val outcome = service.reserve("order-1", "TRENO-TEST", 2)

        assertEquals(ReservationStatus.RESERVED, outcome.reservation.status)
        assertFalse(outcome.replayed)
        assertEquals(8, available("TRENO-TEST"))
    }

    @Test
    fun `sold out yields REJECTED and does not change availability`() {
        service.reserve("order-1", "TRENO-TEST", 10)

        val outcome = service.reserve("order-2", "TRENO-TEST", 1)

        assertEquals(ReservationStatus.REJECTED, outcome.reservation.status)
        assertEquals(0, available("TRENO-TEST"))
    }

    @Test
    fun `duplicate reserve is an idempotent replay - one decrement only`() {
        val first = service.reserve("order-1", "TRENO-TEST", 3)
        val replay = service.reserve("order-1", "TRENO-TEST", 3)

        assertTrue(replay.replayed)
        assertEquals(first.reservation.id, replay.reservation.id)
        assertEquals(ReservationStatus.RESERVED, replay.reservation.status)
        assertEquals(7, available("TRENO-TEST"), "a duplicated delivery must not decrement twice")
    }

    @Test
    fun `replay preserves the original outcome even after restock`() {
        service.reserve("order-1", "TRENO-TEST", 10)
        val rejected = service.reserve("order-2", "TRENO-TEST", 1) // sold out
        service.release("order-1", "TRENO-TEST") // seats come back

        val replay = service.reserve("order-2", "TRENO-TEST", 1)

        // The reservation outcome for an orderId is immutable: a retry-as-new-purchase
        // must use a new orderId (documented idempotency semantics).
        assertTrue(replay.replayed)
        assertEquals(ReservationStatus.REJECTED, rejected.reservation.status)
        assertEquals(ReservationStatus.REJECTED, replay.reservation.status)
    }

    @Test
    fun `confirm moves RESERVED to CONFIRMED and is idempotent`() {
        service.reserve("order-1", "TRENO-TEST", 1)

        assertEquals(ReservationStatus.CONFIRMED, service.confirm("order-1", "TRENO-TEST").status)
        assertEquals(ReservationStatus.CONFIRMED, service.confirm("order-1", "TRENO-TEST").status)
        assertEquals(9, available("TRENO-TEST"), "confirm must not restock")
    }

    @Test
    fun `release restocks and is idempotent`() {
        service.reserve("order-1", "TRENO-TEST", 4)

        assertEquals(ReservationStatus.RELEASED, service.release("order-1", "TRENO-TEST").status)
        assertEquals(ReservationStatus.RELEASED, service.release("order-1", "TRENO-TEST").status)
        assertEquals(10, available("TRENO-TEST"), "released seats come back exactly once")
    }

    @Test
    fun `confirm after TTL expires the hold and restocks (slow-payment race)`() {
        // Simulate a hold whose TTL elapsed before the payment came back: design
        // refinement #3 — Orders must get EXPIRED and compensate, never a stale confirm.
        // (state prepared via save: @Modifying queries need a transaction, tests have none)
        items.save(InventoryItem(productId = "TRENO-TEST", totalSeats = 10, available = 9))
        reservations.save(
            Reservation(
                orderId = "order-slow", productId = "TRENO-TEST", qty = 1,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        val result = service.confirm("order-slow", "TRENO-TEST")

        assertEquals(ReservationStatus.EXPIRED, result.status)
        assertEquals(10, available("TRENO-TEST"), "expired hold must restock")
    }

    @Test
    fun `reconciler sweep expires overdue holds and restocks`() {
        items.save(InventoryItem(productId = "TRENO-TEST", totalSeats = 10, available = 8))
        reservations.save(
            Reservation(
                orderId = "order-stranded", productId = "TRENO-TEST", qty = 2,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        val swept = service.expireOverdue(Instant.now())

        assertEquals(1, swept)
        assertEquals(10, available("TRENO-TEST"))
        assertEquals(
            ReservationStatus.EXPIRED,
            reservations.findByOrderIdAndProductId("order-stranded", "TRENO-TEST")!!.status,
        )
    }

    @Test
    fun `unknown product is a 404-style domain error`() {
        assertFailsWith<NoSuchElementException> { service.reserve("order-1", "NO-SUCH-TRAIN", 1) }
    }
}
