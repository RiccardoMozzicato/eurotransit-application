// ANNOTATED EXEMPLAR — the other four services follow exactly this structure, differing
// only in the role-tailored dependency set (see each service's own comments).
plugins {
	// Compiles Kotlin for the JVM.
	kotlin("jvm") version "2.2.21"
	// Kotlin classes are `final` by default, but Spring needs to subclass @Component/
	// @Service/etc. to create proxies (for @Transactional, AOP...): this plugin opens them.
	kotlin("plugin.spring") version "2.2.21"
	// Provides bootRun/bootJar (the runnable fat-jar the Dockerfile copies).
	id("org.springframework.boot") version "4.0.3"
	// Imports the Spring Boot BOM: that's why most dependencies below have NO version —
	// they are managed centrally and guaranteed mutually compatible.
	id("io.spring.dependency-management") version "1.1.7"
	// JPA needs a no-arg constructor on @Entity classes; this plugin generates it for
	// Kotlin classes (pairs with the allOpen block at the bottom).
	kotlin("plugin.jpa") version "2.2.21"
}

group = "it.g03"
version = "0.0.1-SNAPSHOT"
description = "EuroTransit Orders service — saga orchestrator of the money path (place → reserve → authorize → confirm)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Resilience4j version is pinned explicitly: the published starter targets Spring Boot 3.
// Compatibility with Spring Boot 4 MUST be verified at first build (see docs/agent-log.md).
val resilience4jVersion = "2.3.0"

dependencies {
	// Synchronous entry API (POST /api/orders) + JPA-owned Orders DB + transactional outbox
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Asynchronous pipeline (Pillar A): Kafka eventing + Kotlin coroutines / Flows
	implementation("org.springframework.kafka:spring-kafka")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

	// Resilience (Pillar C): circuit breaker / retry / bulkhead / time limiter on Orders → Payments|Inventory
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
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

// Hibernate creates lazy-loading proxies by SUBCLASSING entities: final Kotlin classes
// would break that, so classes with these annotations are compiled as `open`.
allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
