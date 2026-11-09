package br.com.balaio.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:commerce-test",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none",
    "balaio.worker.enabled=false",
    "payment.mercadopago.worker-enabled=false",
    "payment.mercadopago.environment=sandbox",
    "payment.mercadopago.access-token=APP_USR-test-placeholder",
    "payment.mercadopago.webhook-secret=webhook-test-placeholder",
    "payment.mercadopago.notification-url=https://test.example/webhook",
    "payment.mercadopago.frontend-return-url=https://test.example"
})
class CommerceServiceApplicationTests {
	@MockitoBean JwtDecoder decoder;

	@Test
	void contextLoads() {
	}

}
