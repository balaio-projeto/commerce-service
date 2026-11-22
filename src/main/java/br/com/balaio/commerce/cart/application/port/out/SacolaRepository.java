package br.com.balaio.commerce.cart.application.port.out;

import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.cart.domain.ItemSacola;

public interface SacolaRepository {
    List<ItemSacola> consultar(UUID comprador);
    List<ItemSacola> substituir(UUID comprador, List<ItemSacola> itens);
}
