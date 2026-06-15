package it.g03.payments

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * The idempotency record of "must not double-charge" (Pillar B).
 *
 * UNIQUE(order_id) means there can be AT MOST ONE authorization outcome per order:
 * a retried/duplicated authorize either finds this row (replay → return the stored
 * outcome, charge nothing) or loses the insert race and has its transaction rolled
 * back. Same proven pattern as Inventory's reservation record.
 */

@Entity   // maps this class to a database table (JPA persistent entity)
@Table(    // sets the table name and the UNIQUE constraint that enforces "must not double-charge"
    name = "payment_authorizations",
    uniqueConstraints = [UniqueConstraint(name = "uq_payment_auth_order", columnNames = ["order_id"])],
)
data class PaymentAuthorization(
    @Id   // primary key of the entity
    val id: UUID = UUID.randomUUID(),

    /** The idempotency key of the money path (one order → one payment). */
    @Column(name = "order_id", nullable = false, length = 64)   // maps this field to a table column
    val orderId: String = "",

    @Column(name = "amount_cents", nullable = false)   // maps this field to a table column
    val amountCents: Long = 0,

    @Enumerated(EnumType.STRING)   // store the enum by its name (AUTHORIZED/DECLINED), not its ordinal
    @Column(nullable = false, length = 16)   // maps this field to a table column
    val status: AuthorizationStatus = AuthorizationStatus.AUTHORIZED,

    @Column(name = "created_at", nullable = false)   // maps this field to a table column
    val createdAt: Instant = Instant.now(),
)

enum class AuthorizationStatus {
    /** Terminal: the (mock) PSP authorized the charge. */
    AUTHORIZED,

    /** Terminal: the (mock) PSP declined — a BUSINESS outcome, not a transport failure. */
    DECLINED,
}
