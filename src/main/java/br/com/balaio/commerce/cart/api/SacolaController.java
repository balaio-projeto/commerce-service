package br.com.balaio.commerce.cart.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.PapelJwt;
import br.com.balaio.commerce.cart.application.SacolaException;
import br.com.balaio.commerce.cart.application.port.in.SacolaUseCase;
import br.com.balaio.commerce.cart.domain.ItemSacola;
import tools.jackson.databind.JsonNode;

@RestController
public class SacolaController {
    private final SacolaUseCase sacola;

    public SacolaController(SacolaUseCase sacola) { this.sacola = sacola; }

    @GetMapping("/api/v1/estado/sacola")
    public Envelope consultar(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(itens(sacola.consultar(PapelJwt.exigir(jwt, "comprador"))), null);
    }

    @PostMapping("/api/v1/sacola/revalidar")
    public Envelope revalidar(@AuthenticationPrincipal Jwt jwt) {
        var revalidacao = sacola.revalidar(PapelJwt.exigir(jwt, "comprador"));
        var itens = revalidacao.itens().stream().map(item -> new ItemRevalidado(item.slug(), item.quantidade(),
                item.precoAtual(), item.disponibilidade(), item.situacao())).toList();
        return new Envelope(new Revalidacao(itens, revalidacao.podeProsseguir(), revalidacao.revalidadoEm()), null);
    }

    @PutMapping("/api/v1/estado/sacola")
    public Envelope substituir(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode corpo) {
        UUID id = PapelJwt.exigir(jwt, "comprador");
        if (corpo == null || !corpo.isObject() || corpo.size() != 1 || !corpo.has("sacola")
                || !corpo.path("sacola").isArray())
            throw invalida();
        var itens = new ArrayList<ItemSacola>();
        for (JsonNode item : corpo.path("sacola")) {
            if (!item.isObject() || item.size() != 2 || !item.path("slug").isString()
                    || !item.path("quantidade").isInt() || !item.has("slug") || !item.has("quantidade"))
                throw invalida();
            itens.add(new ItemSacola(item.path("slug").asText(), item.path("quantidade").asInt()));
        }
        return new Envelope(itens(sacola.substituir(id, List.copyOf(itens))), null);
    }

    private List<Item> itens(List<ItemSacola> itens) {
        return itens.stream().map(item -> new Item(item.slug(), item.quantidade())).toList();
    }

    private SacolaException invalida() {
        return new SacolaException(SacolaException.Tipo.REQUISICAO_INVALIDA, "Sacola inválida.");
    }

    public record Item(String slug, int quantidade) { }
    public record ItemRevalidado(String slug, int quantidade, BigDecimal precoAtual,
            String disponibilidade, String situacao) { }
    public record Revalidacao(List<ItemRevalidado> itens, boolean podeProsseguir, Instant revalidadoEm) { }
}
