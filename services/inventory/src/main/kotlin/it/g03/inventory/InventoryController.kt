package it.g03.inventory

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class ReserveRequest(val orderId: String, val productId: String, val qty: Int = 1)

data class ReservationResponse(
    val orderId: String,
    val productId: String,
    val qty: Int,
    val status: ReservationStatus,
    val expiresAt: Instant,
    /** true when this call hit the idempotent replay path (duplicate delivery/retry). */
    val replayed: Boolean,
)

private fun Reservation.toResponse(replayed: Boolean = false) =
    ReservationResponse(orderId, productId, qty, status, expiresAt, replayed)

/**
 * Internal synchronous API, called by Orders inside its async pipeline ("a reservation
 * decision must be made now"). Not exposed through the public ingress.
 *
 * Domain outcomes (RESERVED vs REJECTED/sold-out) are returned as 200 + status field, not
 * as HTTP errors: sold-out is a valid business answer the saga must branch on, while HTTP
 * errors are reserved for transport/contract problems (and would trip retries and the
 * caller's circuit breaker for something that is not a failure).
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryController(
    private val service: InventoryService,
    private val items: InventoryItemRepository,
) {

    @PostMapping("/reservations")
    fun reserve(@RequestBody request: ReserveRequest): ReservationResponse {
        val outcome = service.reserve(request.orderId, request.productId, request.qty)
        return outcome.reservation.toResponse(replayed = outcome.replayed)
    }

    @PostMapping("/reservations/{orderId}/{productId}/confirm")
    fun confirm(@PathVariable orderId: String, @PathVariable productId: String): ReservationResponse =
        service.confirm(orderId, productId).toResponse()

    @PostMapping("/reservations/{orderId}/{productId}/release")
    fun release(@PathVariable orderId: String, @PathVariable productId: String): ReservationResponse =
        service.release(orderId, productId).toResponse()

    /** Availability snapshot — also used to verify the "never oversell" invariant in chaos runs. */
    @GetMapping("/items/{productId}")
    fun item(@PathVariable productId: String): InventoryItem =
        items.findById(productId).orElseThrow { NoSuchElementException("unknown product: $productId") }

    @GetMapping("/items")
    fun items(): List<InventoryItem> = items.findAll()

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
}
