package br.com.balaio.commerce.payment.integration;

import br.com.balaio.commerce.payment.application.port.out.PagamentoGateway;

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
import java.util.function.Supplier;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

@Component
public class CheckoutProClient implements PagamentoGateway {
    private final RestClient http;
    private final MercadoPagoConfig config;

    public CheckoutProClient(RestClient.Builder builder, MercadoPagoConfig config) {
        this.config = config;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = builder.baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public Preferencia criar(UUID pedido, List<Item> itens, BigDecimal frete) {
        var linhas = new ArrayList<Map<String, Object>>();
        for (var item : itens)
            linhas.add(Map.of("id", item.slug(), "title", item.slug(), "quantity", item.quantidade(),
                    "unit_price", item.preco(), "currency_id", "BRL"));
        if (frete != null && frete.signum() > 0)
            linhas.add(Map.of("id", "frete", "title", "Entrega", "quantity", 1,
                    "unit_price", frete, "currency_id", "BRL"));
        String retorno = config.returnUrl() + "/confirmation/" + pedido;
        var corpo = Map.of("items", linhas, "external_reference", pedido.toString(),
                "back_urls", Map.of("success", retorno, "pending", retorno, "failure", retorno),
                "notification_url", config.notificationUrl(), "expires", true,
                "expiration_date_from", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "expiration_date_to", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(13).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        JsonNode resposta = consultar(() -> http.post().uri("/checkout/preferences").headers(h -> h.setBearerAuth(config.accessToken()))
                .body(corpo).retrieve().body(JsonNode.class));
        return preferencia(resposta, pedido.toString());
    }

    @Override
    public List<Preferencia> buscar(String referencia) {
        JsonNode resposta = consultar(() -> http.get().uri("/checkout/preferences/search?external_reference={reference}", referencia)
                .headers(h -> h.setBearerAuth(config.accessToken())).retrieve().body(JsonNode.class));
        var encontradas = new ArrayList<Preferencia>();
        if (resposta == null) throw new IllegalStateException("Busca de preferências inválida.");
        for (JsonNode elemento : resposta.path("elements")) {
            String id = elemento.path("id").asText();
            if (!id.isBlank()) {
                JsonNode detalhe = consultar(() -> http.get().uri("/checkout/preferences/{id}", id)
                        .headers(h -> h.setBearerAuth(config.accessToken())).retrieve().body(JsonNode.class));
                if (detalhe != null && referencia.equals(detalhe.path("external_reference").asText()))
                    encontradas.add(preferencia(detalhe, referencia));
            }
        }
        return encontradas;
    }

    @Override
    public Pagamento pagamento(String id) {
        JsonNode p = consultar(() -> http.get().uri("/v1/payments/{id}", id).headers(h -> h.setBearerAuth(config.accessToken()))
                .retrieve().body(JsonNode.class));
        if (p == null || !id.equals(p.path("id").asText())) throw new IllegalStateException("Pagamento inválido.");
        return new Pagamento(id, p.path("external_reference").asText(),
                new BigDecimal(p.path("transaction_amount").asText()), p.path("currency_id").asText(),
                p.path("status").asText(), p.path("live_mode").asBoolean(true), p.path("order").path("id").asText(),
                new BigDecimal(p.path("transaction_amount_refunded").asText("0")));
    }

    @Override
    public List<String> buscarPagamentos(String referencia) {
        JsonNode resposta = consultar(() -> http.get().uri(
                "/v1/payments/search?sort=date_created&criteria=desc&external_reference={reference}&limit=30",
                referencia).headers(h -> h.setBearerAuth(config.accessToken())).retrieve().body(JsonNode.class));
        if (resposta == null) throw new IllegalStateException("Busca de pagamentos inválida.");
        var ids = new ArrayList<String>();
        for (JsonNode item : resposta.path("results")) {
            if (referencia.equals(item.path("external_reference").asText())) ids.add(item.path("id").asText());
        }
        return ids;
    }

    @Override
    public Ordem ordem(String id) {
        JsonNode o = consultar(() -> http.get().uri("/merchant_orders/{id}", id).headers(h -> h.setBearerAuth(config.accessToken()))
                .retrieve().body(JsonNode.class));
        if (o == null) throw new IllegalStateException("Pedido comercial inválido.");
        return new Ordem(o.path("preference_id").asText(), o.path("external_reference").asText());
    }

    @Override
    public void cancelar(String paymentId) {
        consultar(() -> http.put().uri("/v1/payments/{id}", paymentId).headers(h -> h.setBearerAuth(config.accessToken()))
                .body(Map.of("status", "cancelled")).retrieve().toBodilessEntity());
    }

    @Override
    public void reembolsar(String paymentId, UUID pedidoId) {
        consultar(() -> http.post().uri("/v1/payments/{id}/refunds", paymentId)
                .headers(h -> { h.setBearerAuth(config.accessToken()); h.set("X-Idempotency-Key", pedidoId.toString()); })
                .body(Map.of()).retrieve().toBodilessEntity());
    }

    private <T> T consultar(Supplier<T> requisicao) {
        try {
            return requisicao.get();
        } catch (RestClientException erro) {
            throw new ProvedorIndisponivel(erro);
        }
    }

    private Preferencia preferencia(JsonNode p, String referencia) {
        if (p == null || p.path("id").asText().isBlank()
                || (!p.path("external_reference").isMissingNode()
                    && !referencia.equals(p.path("external_reference").asText())))
            throw new IllegalStateException("Preferência do Mercado Pago inválida.");
        String url = p.path(config.campoUrlCheckout()).asText();
        URI destino = URI.create(url);
        if (!"https".equals(destino.getScheme()) || !config.hostCheckoutPermitido(destino.getHost())
                || destino.getUserInfo() != null || destino.getQuery() == null
                || !destino.getQuery().contains("pref_id="))
            throw new IllegalStateException("URL de checkout da preferência inválida.");
        return new Preferencia(p.path("id").asText(), url);
    }
}
