package br.com.balaio.commerce.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class JwtCommerceSecurityConfig {
    @Bean
    JwtDecoder commerceJwtDecoder(@Value("${balaio.jwt.public-key-location}") String local,
            @Value("${balaio.jwt.issuer}") String emissor,
            @Value("${balaio.jwt.audience}") String audiencia,
            ResourceLoader resources) {
        if (local.isBlank() || emissor.isBlank() || audiencia.isBlank()) {
            throw new IllegalStateException("Configuração de JWT do commerce incompleta.");
        }
        try {
            var recurso = resources.getResource(local);
            if (!recurso.exists() || recurso.contentLength() > 16384) {
                throw new IllegalArgumentException();
            }
            String pem = new String(recurso.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
            String inicio = "-----BEGIN PUBLIC KEY-----";
            String fim = "-----END PUBLIC KEY-----";
            if (!pem.startsWith(inicio) || !pem.endsWith(fim)) {
                throw new IllegalArgumentException();
            }
            byte[] der = Base64.getDecoder().decode(pem.substring(inicio.length(), pem.length() - fim.length())
                    .replaceAll("\\s", ""));
            var chave = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if (chave.getModulus().bitLength() < 3072) {
                throw new IllegalArgumentException();
            }
            var decoder = NimbusJwtDecoder.withPublicKey(chave).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            var padrao = JwtValidators.createDefaultWithIssuer(emissor);
            decoder.setJwtValidator(jwt -> {
                var resultado = padrao.validate(jwt);
                if (resultado.hasErrors()) return resultado;
                if (!jwt.getAudience().contains(audiencia) || jwt.getSubject() == null
                        || jwt.getId() == null || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null) {
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                }
                try {
                    if (!java.util.UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                    }
                } catch (IllegalArgumentException exception) {
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                }
                return OAuth2TokenValidatorResult.success();
            });
            return decoder;
        } catch (Exception exception) {
            throw new IllegalStateException("Chave pública JWT do commerce inválida.", exception);
        }
    }

    @Bean
    SecurityFilterChain commerceSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/pagamentos/mercado-pago/notificacoes").permitAll()
                        .requestMatchers("/api/v1/checkout", "/api/v1/pedidos/**",
                                "/api/v1/artesao/pedidos-pendentes", "/api/v1/artesao/vendas").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})
                        .authenticationEntryPoint((request, response, error) -> erro(response, HttpStatus.UNAUTHORIZED,
                                "NAO_AUTENTICADO", "Autenticação necessária.")))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, error) -> erro(response,
                        HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Operação não permitida.")))
                .build();
    }

    private void erro(jakarta.servlet.http.HttpServletResponse response, HttpStatus status,
            String codigo, String mensagem) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"dados\":null,\"erro\":{\"codigo\":\"" + codigo
                + "\",\"mensagem\":\"" + mensagem + "\"}}");
    }
}
