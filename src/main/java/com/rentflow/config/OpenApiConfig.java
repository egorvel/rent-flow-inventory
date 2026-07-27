package com.rentflow.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info =
                @Info(
                        title = "RentFlow Inventory API",
                        version = "v1",
                        description = "CRUD API for the RentFlow equipment catalogue."),
        tags = @Tag(name = "Inventory", description = "Equipment catalogue operations."))
public class OpenApiConfig {}
