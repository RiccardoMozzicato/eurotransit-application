package it.g03.orders

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/*
 * Synchronous gateways to the sibling services ("a decision must be made NOW" -> these calls happen INSIDE the async pipeline, see docs/design.md). They are interfaces so the saga is testable with fakes; the HTTP implementations carry connect/read TIMEOUTS on every call (Pillar C: no unbounded waits).
 * Phase 2 wraps these same gateways with Resilience4j (circuit breaker / retry+jitter / bulkhead)
 */

// Mirror of Inventory's response statuses (as always, strings to avoid duplicating the enum)
data class ReservationResult(val status: String, val replayed: Boolean)

// Mirror of Payments' response statuses
data class AuthorizationResult(val status: String, val replayed: Boolean)

interface InventoryGateway {
    fun reserve(orderId: String, productId: String, qty: Int): ReservationResult // place a hold on the seats
    fun confirm(orderId: String, productId: String): ReservationResult     // turn the hold into a firm sale
    fun release(orderId: String, productId: String): ReservationResult     // give the hold back (compensation)
}

interface PaymentsGateway {
    fun authorize(orderId: String, amountCents: Long): AuthorizationResult // charge/authorize the payment
}

// Shared bounded-timeout request factory: connect 2s, read 5s
private fun timeoutFactory() =
    JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    ).apply { setReadTimeout(Duration.ofSeconds(5)) }

// Exact mirrors of the sibling services' response bodies
private data class InventoryReservationDto(
    val orderId: String,
    val productId: String,
    val qty: Int,
    val status: String,
    val expiresAt: Instant,
    val replayed: Boolean,
)

private data class PaymentAuthorizationDto(
    val authorizationId: UUID,
    val orderId: String,
    val amountCents: Long,
    val status: String,
    val replayed: Boolean,
)

@Component // registers this HTTP gateway as the production InventoryGateway bean
class HttpInventoryGateway(
    // in-cluster the env var points at the Service DNS (http://inventory:8080)
    // the localhost default is for local multi-process dev
    @Value("\${app.inventory-base-url}") baseUrl: String,
) : InventoryGateway {

    private val client = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build()

    // POST /api/inventory/reservations -> place a hold on the seats for this order
    override fun reserve(orderId: String, productId: String, qty: Int): ReservationResult {
        val dto = client.post()
            .uri("/api/inventory/reservations")
            .body(mapOf("orderId" to orderId, "productId" to productId, "qty" to qty))
            .retrieve()
            .body(InventoryReservationDto::class.java)!!
        return ReservationResult(dto.status, dto.replayed)
    }

    // POST /api/inventory/reservations/{o}/{p}/confirm -> turn the hold into a firm sale (idempotent)
    override fun confirm(orderId: String, productId: String): ReservationResult {
        val dto = client.post()
            .uri("/api/inventory/reservations/{o}/{p}/confirm", orderId, productId)
            .retrieve()
            .body(InventoryReservationDto::class.java)!!
        return ReservationResult(dto.status, dto.replayed)
    }

    // POST /api/inventory/reservations/{o}/{p}/release -> give the hold back (saga compensation, idempotent)
    override fun release(orderId: String, productId: String): ReservationResult {
        val dto = client.post()
            .uri("/api/inventory/reservations/{o}/{p}/release", orderId, productId)
            .retrieve()
            .body(InventoryReservationDto::class.java)!!
        return ReservationResult(dto.status, dto.replayed)
    }
}

@Component // registers this HTTP gateway as the production PaymentsGateway bean
class HttpPaymentsGateway(
    @Value("\${app.payments-base-url}") baseUrl: String,
    // The circuit breaker is applied PROGRAMMATICALLY through the registry, NOT via the @CircuitBreaker annotation: the resilience4j starter targets Spring Boot 3, so the AOP aspect might not weave on Boot 4 (docs/agent-log.md #2) — but the CircuitBreakerRegistry bean is verified to bind (OrdersApplicationTests), so decorating through it is deterministic. Config: resilience4j.circuitbreaker.instances.payments in application.yml
    registry: CircuitBreakerRegistry,
    private val meterRegistry: MeterRegistry,
) : PaymentsGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build()
    private val breaker = registry.circuitBreaker("payments") // single-arg form: returns the instance pre-built from the "payments" config

    // Outcome-tagged failure counter for the Orders->Payments call (Pillar D, same idiom as InventoryService.outcomes): outcome = unavailable | client_error
    private fun failures(outcome: String) = meterRegistry.counter("orders_payment_failures_total", "outcome", outcome)

    // POST /api/payments/authorizations -> authorize the charge for this order (idempotent on the Payments side)
    override fun authorize(orderId: String, amountCents: Long): AuthorizationResult =
        try {
            // executeCallable records transport faults (connect/read timeout, 5xx) as breaker failures and rethrows them; when the breaker is OPEN it throws CallNotPermittedException WITHOUT calling Payments (fast-fail). A DECLINED is a normal 200 body, so it returns here cleanly and never trips the breaker — declining a card is a business outcome, not a fault
            breaker.executeCallable {
                val dto = client.post()
                    .uri("/api/payments/authorizations")
                    .body(mapOf("orderId" to orderId, "amountCents" to amountCents))
                    .retrieve()
                    .body(PaymentAuthorizationDto::class.java)!!
                AuthorizationResult(dto.status, dto.replayed)
            }
        } catch (e: CallNotPermittedException) {
            // breaker OPEN: the call was short-circuited, Payments was not hit
            unavailable(orderId, "circuit breaker open")
        } catch (e: HttpClientErrorException) {
            // A 4xx is a PERMANENT, deterministic client error (malformed / unprocessable authorization request), NOT a transient fault — retrying it would park the order forever. It is in the breaker's ignore-exceptions so it never trips the breaker; here we drive the order to a TERMINAL outcome instead of UNAVAILABLE. No charge happens on a 4xx, so mapping it to DECLINED is money-safe (the saga releases the hold and rejects)
            log.warn("payments rejected order {} with client error {} — terminal DECLINED", orderId, e.statusCode)
            failures("client_error").increment()
            AuthorizationResult("DECLINED", false)
        } catch (e: Exception) {
            // genuine transport fault recorded by the breaker above (connect/read timeout, 5xx, connection refused): map to the fallback so the saga parks the order
            unavailable(orderId, e.message ?: e.javaClass.simpleName)
        }

    // Circuit-breaker fallback: the saga maps "UNAVAILABLE" to the PAYMENT_PENDING parking state — it does NOT reject the order and does NOT propagate the fault (Pillar C: contain, don't propagate). Emitting a metric keeps the policy observable (deck 10)
    private fun unavailable(orderId: String, reason: String): AuthorizationResult {
        log.warn("payments unavailable for order {} ({}) — parking PAYMENT_PENDING", orderId, reason)
        failures("unavailable").increment()
        return AuthorizationResult("UNAVAILABLE", false)
    }
}
