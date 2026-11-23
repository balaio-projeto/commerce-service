package br.com.balaio.commerce.shipping.integration;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import br.com.balaio.commerce.shipping.application.FreteException;
import br.com.balaio.commerce.shipping.application.port.out.ProvedorFrete;
import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;
import tools.jackson.databind.JsonNode;

public class MelhorEnvioClient implements ProvedorFrete {
    public static final String PROVEDOR_SANDBOX = "melhor_envio_sandbox";
    public static final String PROVEDOR_PRODUCAO = "melhor_envio";
    public static final String PREFIXO_ID = "me-";
    private static final Pattern ID_OPCAO = Pattern.compile("me-[0-9]{1,6}");
    private static final Set<String> HOSTS_SANDBOX = Set.of("sandbox.melhorenvio.com.br", "localhost", "127.0.0.1");
    private static final Set<String> HOSTS_PRODUCAO = Set.of("melhorenvio.com.br", "www.melhorenvio.com.br");

    public record Pacote(int alturaCm, int larguraCm, int comprimentoCm, BigDecimal pesoKg) { }

    private final RestClient http;
    private final String provedor;
    private final String token;
    private final String cepOrigem;
    private final String servicos;
    private final Pacote pacote;

    public MelhorEnvioClient(RestClient.Builder builder, String baseUrl, String token, String cepOrigem, String userAgent,
            String servicos, Pacote pacote) {
        URI endereco = URI.create(baseUrl);
        String host = endereco.getHost();
        boolean producao = host != null && HOSTS_PRODUCAO.contains(host);
        if (host == null || (!producao && !HOSTS_SANDBOX.contains(host)))
            throw new IllegalStateException("Melhor Envio aceita apenas os endereços oficiais de sandbox ou produção.");
        if (producao && !"https".equals(endereco.getScheme()))
            throw new IllegalStateException("Melhor Envio em produção exige HTTPS.");
        if (cepOrigem == null || !cepOrigem.replaceAll("\\D", "").matches("[0-9]{8}"))
            throw new IllegalStateException("CEP de origem do frete inválido.");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", userAgent).build();
        this.provedor = producao ? PROVEDOR_PRODUCAO : PROVEDOR_SANDBOX;
        this.token = token;
        this.cepOrigem = cepOrigem.replaceAll("\\D", "");
        this.servicos = servicos == null ? "" : servicos.trim();
        this.pacote = pacote;
    }

    @Override
    public String provedor() {
        return provedor;
    }

    @Override
    public boolean reconheceOpcao(String freteId) {
        return freteId != null && ID_OPCAO.matcher(freteId).matches();
    }

    @Override
    public List<OpcaoFrete> cotar(String cepDestino, List<VolumeFrete> volumes) {
        JsonNode resposta;
        try {
            resposta = http.post().uri("/api/v2/me/shipment/calculate")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(corpo(cepDestino, volumes)).retrieve().body(JsonNode.class);
        } catch (RestClientException falha) {
            throw indisponivel();
        }
        if (resposta == null || !resposta.isArray()) throw indisponivel();
        var opcoes = new ArrayList<OpcaoFrete>();
        for (JsonNode servico : resposta) {
            if (servico.hasNonNull("error") || !servico.hasNonNull("id")) continue;
            BigDecimal valor = valor(servico);
            if (valor == null || valor.signum() < 0) continue;
            JsonNode faixa = servico.path("custom_delivery_range").isObject()
                    ? servico.path("custom_delivery_range") : servico.path("delivery_range");
            Integer minimo = inteiro(faixa.path("min"), servico.path("custom_delivery_time"), servico.path("delivery_time"));
            Integer maximo = inteiro(faixa.path("max"), servico.path("custom_delivery_time"), servico.path("delivery_time"));
            String transportadora = servico.path("company").path("name").asString("");
            String nome = servico.path("name").asString("Serviço");
            opcoes.add(new OpcaoFrete(PREFIXO_ID + servico.path("id").asString(),
                    transportadora.isBlank() ? nome : transportadora + " " + nome,
                    OpcaoFrete.prazoEmTexto(minimo, maximo), minimo, maximo, valor,
                    transportadora.isBlank() ? null : transportadora, provedor));
        }
        opcoes.sort((a, b) -> a.valor().compareTo(b.valor()));
        return opcoes;
    }

    private Map<String, Object> corpo(String cepDestino, List<VolumeFrete> volumes) {
        var produtos = new ArrayList<Map<String, Object>>();
        for (var volume : volumes) {
            var produto = new LinkedHashMap<String, Object>();
            produto.put("id", volume.slug());
            produto.put("width", pacote.larguraCm());
            produto.put("height", pacote.alturaCm());
            produto.put("length", pacote.comprimentoCm());
            produto.put("weight", pacote.pesoKg());
            produto.put("insurance_value", volume.valorUnitario() == null ? BigDecimal.ZERO : volume.valorUnitario());
            produto.put("quantity", volume.quantidade());
            produtos.add(produto);
        }
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("from", Map.of("postal_code", cepOrigem));
        corpo.put("to", Map.of("postal_code", cepDestino));
        corpo.put("products", produtos);
        corpo.put("options", Map.of("receipt", false, "own_hand", false));
        if (!servicos.isBlank()) corpo.put("services", servicos);
        return corpo;
    }

    private BigDecimal valor(JsonNode servico) {
        String texto = servico.hasNonNull("custom_price") ? servico.path("custom_price").asString()
                : servico.path("price").asString("");
        try {
            return texto.isBlank() ? null : new BigDecimal(texto).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException invalido) {
            return null;
        }
    }

    private Integer inteiro(JsonNode... candidatos) {
        for (JsonNode candidato : candidatos)
            if (candidato.isNumber() || (candidato.isString() && candidato.asString().matches("[0-9]{1,3}")))
                return candidato.asInt();
        return null;
    }

    private FreteException indisponivel() {
        return new FreteException(FreteException.Tipo.COTACAO_INDISPONIVEL, "Não foi possível cotar o frete agora; tente novamente.");
    }
}
