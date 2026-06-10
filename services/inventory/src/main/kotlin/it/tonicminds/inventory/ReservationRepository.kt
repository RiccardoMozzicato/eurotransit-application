package it.tonicminds.inventory

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {

    /** Idempotency lookup: the (orderId, productId) pair identifies the operation. */
    fun findByOrderIdAndProductId(orderId: String, productId: String): Reservation?

    /** Reconciler sweep: holds whose TTL elapsed before a confirm/release arrived. */
    fun findByStatusAndExpiresAtBefore(status: ReservationStatus, cutoff: Instant): List<Reservation>
}
