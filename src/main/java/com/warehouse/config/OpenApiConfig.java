package com.warehouse.config;

import com.warehouse.web.ApiPaths;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    @Bean
    public OpenAPI warehouseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Warehouse API")
                        .version("1.0")
                        .description("REST API системы управления складом"));
    }

    /**
     * Keeps transitional unversioned aliases out of the canonical API contract.
     *
     * @return customizer that exposes only v1 controller paths
     */
    @Bean
    public OpenApiCustomizer canonicalV1PathsOnly() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().entrySet().removeIf(
                    entry -> !entry.getKey().startsWith(ApiPaths.V1_API_ROOT + "/")
            );
        };
    }
}
