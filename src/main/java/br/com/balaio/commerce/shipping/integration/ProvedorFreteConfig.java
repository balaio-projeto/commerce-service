package br.com.balaio.commerce.shipping.integration;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import br.com.balaio.commerce.shipping.application.port.out.ProvedorFrete;

@Configuration(proxyBeanMethods = false)
public class ProvedorFreteConfig {
    @Bean
    public ProvedorFrete provedorFrete(RestClient.Builder builder,
            @Value("${frete.melhor-envio.base-url:https://sandbox.melhorenvio.com.br}") String baseUrl,
            @Value("${frete.melhor-envio.token:}") String token,
            @Value("${frete.melhor-envio.cep-origem:50030230}") String cepOrigem,
            @Value("${frete.melhor-envio.user-agent:Balaio homologacao}") String userAgent,
            @Value("${frete.melhor-envio.servicos:}") String servicos,
            @Value("${frete.pacote.altura-cm:15}") int altura,
            @Value("${frete.pacote.largura-cm:20}") int largura,
            @Value("${frete.pacote.comprimento-cm:25}") int comprimento,
            @Value("${frete.pacote.peso-kg:1.0}") BigDecimal peso) {
        if (token == null || token.isBlank()) return new FreteFixoDemonstrativo();
        return new MelhorEnvioSandboxClient(builder, baseUrl, token.trim(), cepOrigem, userAgent, servicos,
                new MelhorEnvioSandboxClient.Pacote(altura, largura, comprimento, peso));
    }
}
