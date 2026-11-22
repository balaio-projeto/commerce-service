package br.com.balaio.commerce.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Pedido(UUID id, UUID comprador, UUID artesao, String estado, String encerramento,
        String motivo, BigDecimal frete, BigDecimal total, OffsetDateTime criadoEm) { }
