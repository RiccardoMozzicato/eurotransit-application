package it.g03.inventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// Spring Boot entry point for the Inventory service
// @SpringBootApplication enables component scanning and auto-configuration starting from this package
@SpringBootApplication
class InventoryApplication

// main: launches the Spring application context and embedded server
fun main(args: Array<String>) {
	runApplication<InventoryApplication>(*args)
}
