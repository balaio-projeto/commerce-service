package br.com.balaio.commerce.promotion.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public record DadosCupom(String codigo, String descricao, String tipo, BigDecimal valor, BigDecimal descontoMaximo,
        BigDecimal pedidoMinimo, OffsetDateTime inicio, OffsetDateTime fim, Integer limiteUsos,
        Integer limitePorComprador, Boolean ativo) {
    public static final Pattern FORMATO_CODIGO = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,39}");
    private static final int DESCRICAO_MINIMA = 3;
    private static final int DESCRICAO_MAXIMA = 200;
    private static final BigDecimal CEM = new BigDecimal("100");
    private static final BigDecimal VALOR_MAXIMO = new BigDecimal("100000");

    public static Optional<String> codigoNormalizado(String codigo) {
        if (codigo == null || codigo.isBlank()) return Optional.empty();
        return Optional.of(codigo.trim().toUpperCase(Locale.ROOT));
    }

    public DadosCupom normalizado() {
        Optional<TipoCupom> tipoConhecido = TipoCupom.de(tipo);
        boolean freteGratis = tipoConhecido.filter(TipoCupom.FRETE_GRATIS::equals).isPresent();
        return new DadosCupom(codigoNormalizado(codigo).orElse(null), descricao == null ? null : descricao.trim(), tipo,
                freteGratis ? BigDecimal.ZERO : valor, descontoMaximo,
                pedidoMinimo == null ? BigDecimal.ZERO : pedidoMinimo, inicio, fim, limiteUsos, limitePorComprador,
                ativo == null ? Boolean.TRUE : ativo);
    }

    public TipoCupom tipoCupom() {
        return TipoCupom.de(tipo).orElseThrow();
    }

    public Map<String, String> problemas(int usosRegistrados) {
        var problemas = new LinkedHashMap<String, String>();
        if (codigo != null && !FORMATO_CODIGO.matcher(codigo).matches())
            problemas.put("codigo", "Use de 3 a 40 letras sem acento, números, hífen ou sublinhado.");
        if (descricao == null || descricao.length() < DESCRICAO_MINIMA || descricao.length() > DESCRICAO_MAXIMA)
            problemas.put("descricao", "Descreva o desconto em 3 a 200 caracteres.");
        Optional<TipoCupom> tipoConhecido = TipoCupom.de(tipo);
        if (tipoConhecido.isEmpty()) problemas.put("tipo", "Escolha percentual, valor fixo ou frete grátis.");
        tipoConhecido.flatMap(this::problemaDoValor).ifPresent(problema -> problemas.put("valor", problema));
        if (descontoMaximo != null && (descontoMaximo.signum() <= 0 || foraDeCentavos(descontoMaximo)
                || descontoMaximo.compareTo(VALOR_MAXIMO) > 0))
            problemas.put("descontoMaximo", "Informe um teto positivo em reais ou deixe em branco.");
        if (pedidoMinimo == null || pedidoMinimo.signum() < 0 || foraDeCentavos(pedidoMinimo)
                || pedidoMinimo.compareTo(VALOR_MAXIMO) > 0)
            problemas.put("pedidoMinimo", "Informe um pedido mínimo em reais, a partir de R$ 0,00.");
        if (inicio != null && fim != null && !fim.isAfter(inicio))
            problemas.put("fim", "O fim da validade precisa ser depois do início.");
        if (limiteUsos != null && limiteUsos < 1)
            problemas.put("limiteUsos", "Informe pelo menos 1 uso ou deixe em branco para ilimitado.");
        else if (limiteUsos != null && limiteUsos < usosRegistrados)
            problemas.put("limiteUsos", "O limite não pode ser menor que os " + usosRegistrados + " usos já registrados.");
        if (limitePorComprador != null && limitePorComprador < 1)
            problemas.put("limitePorComprador", "Informe pelo menos 1 uso por comprador ou deixe em branco.");
        return problemas;
    }

    private Optional<String> problemaDoValor(TipoCupom tipoCupom) {
        return switch (tipoCupom) {
            case PERCENTUAL -> valor == null || valor.signum() <= 0 || valor.compareTo(CEM) > 0 || foraDeCentavos(valor)
                    ? Optional.of("Informe uma porcentagem maior que 0 e até 100.") : Optional.empty();
            case VALOR_FIXO -> valor == null || valor.signum() <= 0 || foraDeCentavos(valor) || valor.compareTo(VALOR_MAXIMO) > 0
                    ? Optional.of("Informe um valor em reais maior que zero.") : Optional.empty();
            case FRETE_GRATIS -> Optional.empty();
        };
    }

    private static boolean foraDeCentavos(BigDecimal valor) {
        return valor.stripTrailingZeros().scale() > 2;
    }
}
