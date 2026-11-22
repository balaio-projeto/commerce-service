package br.com.balaio.commerce.order.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemPedido(String slug, int quantidade, BigDecimal preco, String disponibilidade,
        UUID reservaId, String reservaEstado) { }
