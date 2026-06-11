plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "it.g03"
version = "0.0.1-SNAPSHOT"
description = "EuroTransit Payments service — synchronous authorization with strict idempotency (no double-charge)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// See docs/agent-log.md: Resilience4j starter targets Spring Boot 3; verify on Boot 4.
val resilience4jVersion = "2.3.0"

dependencies {
	// Synchronous authorize API + idempotency store (key + persisted result)
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Emits payment-authorized; coroutines for any async confirmation work
	implementation("org.springframework.kafka:spring-kafka")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

	// Resilience (Pillar C): protect the outbound call to the (mock) external PSP
	implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
	implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")
	implementation("io.github.resilience4j:resilience4j-micrometer:$resilience4jVersion")

	// Observability (Pillar D)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp")

	// Schema migrations (postgres profile only): Flyway owns the schema, Hibernate validates.
	implementation("org.flywaydb:flyway-core")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.h2database:h2")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
