package it.g03.inventory

import jakarta.persistence.*

/**
 * The contended resource (Pillar B): finite seats for a product (a train departure).
 *
 * `available` is NEVER updated through the entity state — that would be a lost-update
 * waiting to happen. All decrements/restocks go through the atomic conditional UPDATEs
 * in [InventoryItemRepository], which is what makes "never oversell" hold under
 * concurrency. The PostgreSQL schema adds a CHECK (available >= 0) as a last line of
 * defence (see db/migration/V1__baseline.sql).
 */
@Entity
@Table(name = "inventory_items")
data class InventoryItem(
    @Id
    @Column(name = "product_id", length = 64)
    val productId: String,

    @Column(name = "total_seats", nullable = false)
    val totalSeats: Int,

    @Column(nullable = false)
    var available: Int,
) {
    // No-arg constructor for JPA
    constructor() : this("", 0, 0)
}
