package it.tonicminds.inventory

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Reservation state machine (Pillar B):
 *
 *   (new) → RESERVED  → CONFIRMED                     (payment authorized)
 *                     → RELEASED                      (saga compensation: payment failed)
 *                     → EXPIRED                       (TTL passed, swept by the reconciler)
 *   (new) → REJECTED                                  (sold out at reserve time)
 *
 * The UNIQUE(order_id, product_id) constraint makes this row double as the
 * IDEMPOTENCY RECORD for the money path: a duplicated `order-placed` delivery either
 * finds this row (replay → return the stored outcome) or loses the insert race and has
 * its whole transaction — including its seat decrement — rolled back. Dedup and
 * decrement live in the SAME transaction by construction (see InventoryService.reserve).
 */
@Entity
@Table(
    name = "reservations",
    uniqueConstraints = [UniqueConstraint(name = "uq_reservations_order_product", columnNames = ["order_id", "product_id"])],
)
data class Reservation(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "order_id", nullable = false, length = 64)
    val orderId: String = "",

    @Column(name = "product_id", nullable = false, length = 64)
    val productId: String = "",

    @Column(nullable = false)
    val qty: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ReservationStatus = ReservationStatus.RESERVED,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant = Instant.EPOCH,
)

enum class ReservationStatus {
    /** Seats are being held; payment may still confirm or the hold may lapse. */
    RESERVED,

    /** Terminal: payment authorized, seats sold. */
    CONFIRMED,

    /** Terminal: explicitly released as a saga compensation; seats restocked. */
    RELEASED,

    /** Terminal: TTL elapsed before confirmation; seats restocked by the reconciler. */
    EXPIRED,

    /** Terminal: there were not enough seats at reserve time (sold out). */
    REJECTED,
}
