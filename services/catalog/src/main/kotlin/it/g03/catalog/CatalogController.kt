package it.g03.catalog

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Public read API (exposed through the ingress at /api/catalog). Read-mostly and tolerant
 * of staleness by contract (in fact, it sits OUTSIDE the money path) and is a
 * candidate for caching/replicas later.
 * No service layer: there is no business logic to host between the controller and the repository!
 */

@RestController   // exposes this class as a REST controller (will return values as HTTP responses)

@RequestMapping("/api/catalog") // sets the base path for every endpoint defined in this controller
class CatalogController(private val products: ProductRepository) {

    // GET /api/catalog/products -> returns the full list of products
    @GetMapping("/products")
    fun all(): List<Product> = products.findAll()

    // GET /api/catalog/products/{productId} -> looks up a single product by its id
    @GetMapping("/products/{productId}")
    fun byId(@PathVariable productId: String): Product =
        products.findById(productId).orElseThrow { NoSuchElementException("unknown product: $productId") }

    // Converts a missing product into a 404 response with a small JSON error body
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))
}
