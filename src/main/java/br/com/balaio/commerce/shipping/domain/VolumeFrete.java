package br.com.balaio.commerce.shipping.domain;

import java.math.BigDecimal;

public record VolumeFrete(String slug, int quantidade, BigDecimal valorUnitario) { }
