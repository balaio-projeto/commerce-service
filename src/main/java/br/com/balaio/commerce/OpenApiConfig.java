package br.com.balaio.commerce;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {
    @Bean
    OpenAPI commerceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Balaio commerce-service")
                        .description("Sacola, frete, checkout, pedidos e pagamentos (Mercado Pago sandbox).")
                        .version("v1"))
                .components(new Components().addSecuritySchemes("bearer-jwt", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
