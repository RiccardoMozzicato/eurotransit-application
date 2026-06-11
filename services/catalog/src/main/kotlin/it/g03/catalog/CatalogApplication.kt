package it.g03.catalog

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// Spring Boot entry point for the Catalog service
// @SpringBootApplication enables component scanning and auto-configuration starting from this package
@SpringBootApplication
class CatalogApplication

// Main bootstrap method: launches the Spring application context and embedded server.
fun main(args: Array<String>) {
	runApplication<CatalogApplication>(*args)
}
