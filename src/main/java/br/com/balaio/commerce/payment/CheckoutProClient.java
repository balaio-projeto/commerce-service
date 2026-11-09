package br.com.balaio.commerce.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

@Component
public class CheckoutProClient {
    private final RestClient http;
    private final MercadoPagoSandboxConfig config;

    public CheckoutProClient(MercadoPagoSandboxConfig config) {
        this.config = config;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    public record Item(String slug, int quantidade, BigDecimal preco) { }
    public record Preferencia(String id, String checkoutUrl) { }
    public record Pagamento(String id, String referencia, BigDecimal valor, String moeda,
                           String status, boolean liveMode, String ordemId, BigDecimal valorReembolsado) { }
    public record Ordem(String preferenciaId, String referencia) { }

    public Preferencia criar(UUID pedido, List<Item> itens, BigDecimal frete) {
        var linhas = new ArrayList<Map<String, Object>>();
        for (var item : itens)
            linhas.add(Map.of("id", item.slug(), "title", item.slug(), "quantity", item.quantidade(),
                    "unit_price", item.preco(), "currency_id", "BRL"));
        linhas.add(Map.of("id", "frete", "title", "Entrega", "quantity", 1,
                "unit_price", frete, "currency_id", "BRL"));
        String retorno = config.returnUrl() + "/confirmation/" + pedido;
        var corpo = Map.of("items", linhas, "external_reference", pedido.toString(),
                "back_urls", Map.of("success", retorno, "pending", retorno, "failure", retorno),
                "notification_url", config.notificationUrl(), "expires", true,
                "expiration_date_from", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "expiration_date_to", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(13).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        JsonNode resposta = http.post().uri("/checkout/preferences").headers(h -> h.setBearerAuth(config.accessToken()))
                .body(corpo).retrieve().body(JsonNode.class);
        return preferencia(resposta, pedido.toString());
    }

    public List<Preferencia> buscar(String referencia) {
        JsonNode resposta = http.get().uri("/checkout/preferences/search?external_reference={reference}", referencia)
                .headers(h -> h.setBearerAuth(config.accessToken())).retrieve().body(JsonNode.class);
        var encontradas = new ArrayList<Preferencia>();
        if (resposta == null) throw new IllegalStateException("Busca de preferências inválida.");
        for (JsonNode elemento : resposta.path("elements")) {
            String id = elemento.path("id").asText();
            if (!id.isBlank()) {
                JsonNode detalhe = http.get().uri("/checkout/preferences/{id}", id)
                        .headers(h -> h.setBearerAuth(config.accessToken())).retrieve().body(JsonNode.class);
                if (detalhe != null && referencia.equals(detalhe.path("external_reference").asText()))
                    encontradas.add(preferencia(detalhe, referencia));
            }
        }
        return encontradas;
    }

    public Pagamento pagamento(String id) {
        JsonNode p = http.get().uri("/v1/payments/{id}", id).headers(h -> h.setBearerAuth(config.accessToken()))
                .retrieve().body(JsonNode.class);
        if (p == null || !id.equals(p.path("id").asText())) throw new IllegalStateException("Pagamento inválido.");
        return new Pagamento(id, p.path("external_reference").asText(),
                new BigDecimal(p.path("transaction_amount").asText()), p.path("currency_id").asText(),
                p.path("status").asText(), p.path("live_mode").asBoolean(true), p.path("order").path("id").asText(),
                new BigDecimal(p.path("transaction_amount_refunded").asText("0")));
    }

    public List<String> buscarPagamentos(String referencia) {
        JsonNode resposta = http.get().uri(
                "/v1/payments/search?sort=date_created&criteria=desc&external_reference={reference}&limit=30",
                referencia).headers(h -> h.setBearerAuth(config.accessToken())).retrieve().body(JsonNode.class);
        if (resposta == null) throw new IllegalStateException("Busca de pagamentos inválida.");
        var ids = new ArrayList<String>();
        for (JsonNode item : resposta.path("results")) {
            if (referencia.equals(item.path("external_reference").asText())) ids.add(item.path("id").asText());
        }
        return ids;
    }

    public Ordem ordem(String id) {
        JsonNode o = http.get().uri("/merchant_orders/{id}", id).headers(h -> h.setBearerAuth(config.accessToken()))
                .retrieve().body(JsonNode.class);
        if (o == null) throw new IllegalStateException("Pedido comercial inválido.");
        return new Ordem(o.path("preference_id").asText(), o.path("external_reference").asText());
    }

    public void cancelar(String paymentId) {
        http.put().uri("/v1/payments/{id}", paymentId).headers(h -> h.setBearerAuth(config.accessToken()))
                .body(Map.of("status", "cancelled")).retrieve().toBodilessEntity();
    }

    public void reembolsar(String paymentId, UUID pedidoId) {
        http.post().uri("/v1/payments/{id}/refunds", paymentId)
                .headers(h -> { h.setBearerAuth(config.accessToken()); h.set("X-Idempotency-Key", pedidoId.toString()); })
                .body(Map.of()).retrieve().toBodilessEntity();
    }

    private Preferencia preferencia(JsonNode p, String referencia) {
        if (p == null || p.path("id").asText().isBlank()
                || (!p.path("external_reference").isMissingNode()
                    && !referencia.equals(p.path("external_reference").asText())))
            throw new IllegalStateException("Preferência do Mercado Pago inválida.");
        String url = p.path("sandbox_init_point").asText();
        URI destino = URI.create(url);
        if (!"https".equals(destino.getScheme()) || !"sandbox.mercadopago.com".equals(destino.getHost())
                || destino.getUserInfo() != null || destino.getQuery() == null
                || !destino.getQuery().contains("pref_id="))
            throw new IllegalStateException("URL sandbox da preferência inválida.");
        return new Preferencia(p.path("id").asText(), url);
    }
}
