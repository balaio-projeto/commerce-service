package br.com.balaio.commerce.security;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;

/** Real HTTP + production filter/decoder; probe avoids business/database side effects. */
@SpringBootTest(
    classes = JwtHttpTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtHttpTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
      })
  @Import({JwtCommerceSecurityConfig.class, Probe.class})
  static class App {}

  @RestController
  static class Probe {
    @RequestMapping(value = "/api/v1/estado/sacola", method = RequestMethod.GET)
    public String protectedRoute() {
      return "ok";
    }

    @GetMapping("/api/v1/pecas/test/perguntas")
    public String publicRoute() {
      return "public";
    }
  }

  static final Path PUBLIC = publicKey();

  static Path publicKey() {
    try {
      var p = Files.createTempFile("pi169-public-", ".pem");
      Files.writeString(
          p,
          "-----BEGIN PUBLIC KEY-----\n"
              + Base64.getMimeEncoder()
                  .encodeToString(JwtContractTest.KEYS.getPublic().getEncoded())
              + "\n-----END PUBLIC KEY-----\n");
      return p;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(PUBLIC);
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("balaio.jwt.public-key-location", () -> PUBLIC.toUri().toString());
    r.add("balaio.jwt.issuer", () -> "balaio-test");
    r.add("balaio.jwt.audience", () -> "balaio-api");
  }

  @LocalServerPort int port;

  HttpResponse<String> call(String token) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/estado/sacola"))
            .method("GET", HttpRequest.BodyPublishers.noBody())
            .header("X-User-Id", UUID.randomUUID().toString())
            .header("X-Role", "admin");
    if (token != null) b.header("Authorization", "Bearer " + token);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void validSignatureReachesProtectedRoute() throws Exception {
    assertThat(
            call(JwtContractTest.sign(
                    JwtContractTest.claims(), "{\"alg\":\"RS256\"}", JwtContractTest.KEYS))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void identityHeadersDoNotAuthenticate() throws Exception {
    var r = call(null);
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.body()).contains("NAO_AUTENTICADO", "dados", "erro");
  }

  static java.util.stream.Stream<Arguments> invalid() {
    return JwtContractTest.invalid();
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void invalidSignedClaimsReturn401(String reason, Map<String, Object> claims) throws Exception {
    var r = call(JwtContractTest.sign(claims, "{\"alg\":\"RS256\"}", JwtContractTest.KEYS));
    assertThat(r.statusCode()).as(reason).isEqualTo(401);
    assertThat(r.body()).contains("NAO_AUTENTICADO");
  }

  @Test
  void publicRouteNeedsNoToken() throws Exception {
    var r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/pecas/test/perguntas"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
  }

  @Test
  void invalidSignatureReturns401() throws Exception {
    assertThat(
            call(JwtContractTest.sign(
                    JwtContractTest.claims(), "{\"alg\":\"RS256\"}", JwtContractTest.keys()))
                .statusCode())
        .isEqualTo(401);
  }
}
