package it.g03.catalog

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest  // boot the full Spring app context to test controller + repository wiring

class CatalogControllerTest {

    // Injects the controller under test
    @Autowired lateinit var controller: CatalogController
    // Injects the repository so the test can prepare and clean the database state directly
    @Autowired lateinit var products: ProductRepository

    @BeforeEach  // runs before each test method to start from a known database state
    fun seed() {
        products.deleteAll()
        products.save(Product("TRENO-TEST", "Test departure", 1500))
    }

    // Verifies the two "happy-path" controller endpoints: list all products and fetch one by id
    @Test
    fun `lists and fetches products`() {
        assertEquals(1, controller.all().size)
        assertEquals(1500, controller.byId("TRENO-TEST").priceCents)
    }

    // Verifies the "error-path" controller endpoint: propagates not-found error for an unknown id
    @Test
    fun `unknown product is a domain not-found error`() {
        assertFailsWith<NoSuchElementException> { controller.byId("NO-SUCH-TRAIN") }
    }
}
