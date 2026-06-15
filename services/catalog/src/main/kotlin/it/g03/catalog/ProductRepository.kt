package it.g03.catalog

import org.springframework.data.jpa.repository.JpaRepository

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the Product entity in this case); the IdType is String because the productId is a string. Spring Data will generate the implementation at runtime
interface ProductRepository : JpaRepository<Product, String>
