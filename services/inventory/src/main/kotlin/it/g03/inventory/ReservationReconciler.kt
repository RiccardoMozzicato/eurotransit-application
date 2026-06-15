package it.g03.inventory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Periodically expires overdue reservations (design refinement #3): without this, a saga
 * that crashed between reserve and confirm/release would strand seats forever.
 *
 * Async lifecycle (Pillar A), demonstrated rather than claimed:
 * - the [CoroutineScope] is the FAILURE DOMAIN: SupervisorJob so one failed sweep does not
 *   kill the loop;
 * - cooperative cancellation on SIGTERM: Spring calls [destroy] during context shutdown
 *   (before the JVM exits within terminationGracePeriodSeconds), `scope.cancel()` makes the
 *   `delay` throw CancellationException and the loop ends cleanly — no orphaned tasks.
 *   An in-flight sweep transaction completes (JDBC is not interruptible) and the loop then
 *   observes cancellation at the next suspension point.
 */

@Component  // a Spring-managed bean, auto-detected by component scanning
class ReservationReconciler(
    private val service: InventoryService,
    @Value("\${inventory.reservation.sweep-interval}") private val sweepInterval: Duration,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Starts the sweep loop once the app context is fully up (not before beans are ready)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scope.launch {
            log.info("reservation reconciler started (sweep every {})", sweepInterval)
            while (isActive) {
                delay(sweepInterval.toMillis())
                try {
                    val expired = service.expireOverdue(Instant.now())
                    if (expired > 0) log.info("expired {} overdue reservations (seats restocked)", expired)
                } catch (e: Exception) {
                    // Supervisor semantics: log and keep sweeping; transient DB errors must
                    // not kill the reconciler. Note: the only suspension point (delay) sits
                    // OUTSIDE this try, so a CancellationException from cooperative shutdown
                    // is never swallowed here — it ends the loop as intended.
                    log.warn("reservation sweep failed, will retry: {}", e.message)
                }
            }
        }
    }

    // Called by Spring on context shutdown (SIGTERM): cancels the scope for a clean, cooperative stop
    override fun destroy() {
        log.info("cancelling reservation reconciler (cooperative shutdown)")
        scope.cancel()
    }
}
