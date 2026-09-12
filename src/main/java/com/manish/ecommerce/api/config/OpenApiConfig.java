package com.manish.ecommerce.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ecommerceOpenApi(@Value("${server.port:8080}") int port) {
        return new OpenAPI()
                .info(new Info()
                        .title("E-commerce API")
                        .version("v1")
                        .description("""
                                REST API for categories, products, customers, orders, payments and product reviews.

                                **Business rules**
                                - Stock is reserved when an order is placed, not at shipment.
                                - Cancelling an order restores stock and refunds a completed payment.
                                - A customer may only review a product they have received (a DELIVERED order containing it).
                                - Order status moves one way: PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED;
                                  CANCELLED is reachable from any state before SHIPPED. DELIVERED, CANCELLED and REFUNDED are final.
                                """)
                        .contact(new Contact().name("Manish Kumar").email("manishkumarjamia@gmail.com"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("http://localhost:" + port).description("Local")));
    }
}
