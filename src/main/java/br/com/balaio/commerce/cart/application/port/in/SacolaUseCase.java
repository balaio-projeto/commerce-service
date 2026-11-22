package br.com.balaio.commerce.cart.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.cart.domain.ItemSacola;

public interface SacolaUseCase {
    List<ItemSacola> consultar(UUID comprador);
    List<ItemSacola> substituir(UUID comprador, List<ItemSacola> itens);
    Revalidacao revalidar(UUID comprador);

    record ItemRevalidado(String slug, int quantidade, BigDecimal precoAtual,
            String disponibilidade, String situacao) { }
    record Revalidacao(List<ItemRevalidado> itens, boolean podeProsseguir, Instant revalidadoEm) { }
}
