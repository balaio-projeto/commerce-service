package br.com.balaio.commerce.security;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;

class JwtContractTest {
  @TempDir Path temp;
  static final KeyPair KEYS = keys();

  static KeyPair keys() {
    try {
      var g = KeyPairGenerator.getInstance("RSA");
      g.initialize(3072);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  JwtDecoder decoder() throws Exception {
    Path pub = temp.resolve("public.pem");
    Files.writeString(
        pub,
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n");
    return new JwtCommerceSecurityConfig()
        .commerceJwtDecoder(
            pub.toUri().toString(), "balaio-test", "balaio-api", new DefaultResourceLoader());
  }

  static Map<String, Object> claims() {
    long now = Instant.now().getEpochSecond();
    var c = new LinkedHashMap<String, Object>();
    c.put("sub", UUID.randomUUID().toString());
    c.put("papel", "comprador");
    c.put("iss", "balaio-test");
    c.put("aud", List.of("balaio-api"));
    c.put("iat", now);
    c.put("exp", now + 3600);
    c.put("jti", UUID.randomUUID().toString());
    return c;
  }

  static String sign(Map<String, Object> c, String header, KeyPair pair) throws Exception {
    var b = Base64.getUrlEncoder().withoutPadding();
    String input =
        b.encodeToString(header.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + b.encodeToString(JsonMapper.builder().build().writeValueAsBytes(c));
    var s = Signature.getInstance("SHA256withRSA");
    s.initSign(pair.getPrivate());
    s.update(input.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    return input + "." + b.encodeToString(s.sign());
  }

  @ParameterizedTest
  @ValueSource(strings = {"comprador", "artesao", "admin"})
  void acceptsRealSignatureAndKnownRoles(String role) throws Exception {
    var c = claims();
    c.put("papel", role);
    assertThat(decoder().decode(sign(c, "{\"alg\":\"RS256\"}", KEYS)).getSubject())
        .isEqualTo(c.get("sub"));
  }

  static Stream<Arguments> invalid() {
    var cases = new ArrayList<Arguments>();
    for (String k : List.of("sub", "papel", "iss", "aud", "iat", "exp", "jti")) {
      var c = claims();
      c.remove(k);
      cases.add(Arguments.of("missing " + k, c));
      var d = claims();
      d.put(k, Map.of("wrong", "type"));
      cases.add(Arguments.of("type " + k, d));
    }
    for (var entry :
        Map.<String, Object>of(
                "iss",
                "wrong",
                "aud",
                List.of("wrong"),
                "sub",
                "not-uuid",
                "papel",
                "administrador",
                "jti",
                "")
            .entrySet()) {
      var c = claims();
      c.put(entry.getKey(), entry.getValue());
      cases.add(Arguments.of("value " + entry.getKey(), c));
    }
    long n = Instant.now().getEpochSecond();
    var expired = claims();
    expired.put("iat", n - 3600);
    expired.put("exp", n - 1);
    cases.add(Arguments.of("strict expiration", expired));
    var future = claims();
    future.put("iat", n + 120);
    future.put("exp", n + 3600);
    cases.add(Arguments.of("future iat", future));
    var duration = claims();
    duration.put("exp", n + 7200);
    cases.add(Arguments.of("duration", duration));
    var nbf = claims();
    nbf.put("nbf", n + 120);
    cases.add(Arguments.of("future nbf", nbf));
    return cases.stream();
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void rejectsInvalidSignedClaims(String reason, Map<String, Object> c) throws Exception {
    String token = sign(c, "{\"alg\":\"RS256\"}", KEYS);
    var decoder = decoder();
    assertThatThrownBy(() -> decoder.decode(token)).as(reason).isInstanceOf(JwtException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"HS256", "RS512", "none"})
  void rejectsOtherAlgorithms(String alg) throws Exception {
    String token = sign(claims(), "{\"alg\":\"" + alg + "\"}", KEYS);
    var decoder = decoder();
    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsOtherKeyAndTampering() throws Exception {
    var d = decoder();
    String other = sign(claims(), "{\"alg\":\"RS256\"}", keys());
    assertThatThrownBy(() -> d.decode(other)).isInstanceOf(JwtException.class);
    String valid = sign(claims(), "{\"alg\":\"RS256\"}", KEYS);
    String[] p = valid.split("\\.");
    p[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes());
    String changed = String.join(".", p);
    assertThatThrownBy(() -> d.decode(changed)).isInstanceOf(JwtException.class);
  }
}
