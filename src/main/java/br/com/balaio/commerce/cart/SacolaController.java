package br.com.balaio.commerce.cart;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.order.PedidoException;
import tools.jackson.databind.JsonNode;

@RestController
public class SacolaController {
    private final SacolaService sacola;

    public SacolaController(SacolaService sacola) { this.sacola = sacola; }

    public record Envelope(Object dados, Object erro) { }

    @GetMapping("/api/v1/estado/sacola")
    public Envelope consultar(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(sacola.consultar(comprador(jwt)), null);
    }

    @PostMapping("/api/v1/sacola/revalidar")
    public Envelope revalidar(@AuthenticationPrincipal Jwt jwt) {
        return new Envelope(sacola.revalidar(comprador(jwt)), null);
    }

    @PutMapping("/api/v1/estado/sacola")
    public Envelope substituir(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode corpo) {
        UUID id = comprador(jwt);
        if (corpo == null || !corpo.isObject() || corpo.size() != 1 || !corpo.has("sacola")
                || !corpo.path("sacola").isArray())
            throw invalida();
        var itens = new java.util.ArrayList<SacolaService.Item>();
        for (JsonNode item : corpo.path("sacola")) {
            if (!item.isObject() || item.size() != 2 || !item.path("slug").isString()
                    || !item.path("quantidade").isInt() || !item.has("slug") || !item.has("quantidade"))
                throw invalida();
            itens.add(new SacolaService.Item(item.path("slug").asText(), item.path("quantidade").asInt()));
        }
        return new Envelope(sacola.substituir(id, List.copyOf(itens)), null);
    }

    private UUID comprador(Jwt jwt) {
        if (jwt == null || !"comprador".equals(jwt.getClaimAsString("papel")))
            throw new PedidoException(403, "SEM_PERMISSAO", "Operação não permitida.");
        return UUID.fromString(jwt.getSubject());
    }

    private PedidoException invalida() {
        return new PedidoException(400, "REQUISICAO_INVALIDA", "Sacola inválida.");
    }
}
