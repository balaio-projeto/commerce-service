package br.com.balaio.commerce.promotion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public record Cupom(UUID id, String codigo, String descricao, TipoCupom tipo, BigDecimal valor,
        BigDecimal descontoMaximo, BigDecimal pedidoMinimo, OffsetDateTime inicio, OffsetDateTime fim,
        Integer limiteUsos, Integer limitePorComprador, int usos, boolean ativo,
        OffsetDateTime criadoEm, OffsetDateTime atualizadoEm) {
    public static final String CUSTEADO_PELA_PLATAFORMA = "plataforma";
    private static final BigDecimal CEM = new BigDecimal("100");
    private static final int CENTAVOS = 2;

    public boolean automatico() {
        return codigo == null;
    }

    public SituacaoCupom situacao(OffsetDateTime agora) {
        if (!ativo) return SituacaoCupom.INATIVO;
        if (inicio != null && agora.isBefore(inicio)) return SituacaoCupom.AGENDADO;
        if (fim != null && agora.isAfter(fim)) return SituacaoCupom.EXPIRADO;
        if (esgotado()) return SituacaoCupom.ESGOTADO;
        return SituacaoCupom.ATIVO;
    }

    public Optional<Recusa> recusa(OffsetDateTime agora, BigDecimal subtotalProdutos, int usosDoComprador) {
        return switch (situacao(agora)) {
            case INATIVO -> Optional.of(Recusa.de(RecusaCupom.INATIVO));
            case AGENDADO -> Optional.of(Recusa.de(RecusaCupom.AINDA_NAO_VALIDO));
            case EXPIRADO -> Optional.of(Recusa.de(RecusaCupom.EXPIRADO));
            case ESGOTADO -> Optional.of(Recusa.de(RecusaCupom.ESGOTADO));
            case ATIVO -> recusaDoComprador(subtotalProdutos, usosDoComprador);
        };
    }

    private Optional<Recusa> recusaDoComprador(BigDecimal subtotalProdutos, int usosDoComprador) {
        if (limitePorComprador != null && usosDoComprador >= limitePorComprador)
            return Optional.of(Recusa.de(RecusaCupom.LIMITE_POR_COMPRADOR));
        if (subtotalProdutos.compareTo(pedidoMinimo) < 0)
            return Optional.of(Recusa.de(RecusaCupom.PEDIDO_MINIMO, emReais(pedidoMinimo)));
        return Optional.empty();
    }

    public DescontoCalculado calcular(BigDecimal subtotalProdutos, BigDecimal frete) {
        BigDecimal produtos = switch (tipo) {
            case PERCENTUAL -> limitarAoTeto(subtotalProdutos.multiply(valor).divide(CEM, CENTAVOS, RoundingMode.HALF_UP));
            case VALOR_FIXO -> limitarAoTeto(valor);
            case FRETE_GRATIS -> BigDecimal.ZERO;
        };
        BigDecimal descontoFrete = tipo == TipoCupom.FRETE_GRATIS ? limitarAoTeto(frete) : BigDecimal.ZERO;
        return new DescontoCalculado(centavos(produtos.min(subtotalProdutos)), centavos(descontoFrete));
    }

    private boolean esgotado() {
        return limiteUsos != null && usos >= limiteUsos;
    }

    private BigDecimal limitarAoTeto(BigDecimal desconto) {
        BigDecimal positivo = desconto.max(BigDecimal.ZERO);
        return descontoMaximo == null ? positivo : positivo.min(descontoMaximo);
    }

    private static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(CENTAVOS, RoundingMode.HALF_UP);
    }

    static String emReais(BigDecimal valor) {
        var formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + formato.format(valor);
    }

    public record Recusa(RecusaCupom motivo, String mensagem) {
        public static Recusa de(RecusaCupom motivo, Object... detalhes) {
            return new Recusa(motivo, motivo.mensagem(detalhes));
        }
    }
}
