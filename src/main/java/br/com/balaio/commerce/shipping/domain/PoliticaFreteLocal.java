package br.com.balaio.commerce.shipping.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record PoliticaFreteLocal(boolean retiradaAtiva, boolean entregaLocalAtiva, int digitosDoPrefixoCep,
        BigDecimal valorEntregaLocal) {
    public static final String PROVEDOR = "balaio_local";
    public static final String ID_RETIRADA = "retirada-atelie";
    public static final String ID_ENTREGA_LOCAL = "entrega-local";
    public static final int DIGITOS_PADRAO = 5;
    public static final BigDecimal VALOR_PADRAO_ENTREGA_LOCAL = BigDecimal.ZERO;
    private static final String PRAZO_COMBINADO = "Dia e horário combinados pela conversa do pedido";
    private static final Set<String> IDS = Set.of(ID_RETIRADA, ID_ENTREGA_LOCAL);

    public PoliticaFreteLocal {
        if (digitosDoPrefixoCep < 1 || digitosDoPrefixoCep > 8)
            throw new IllegalArgumentException("O prefixo de CEP para entrega local deve ter de 1 a 8 dígitos.");
        if (valorEntregaLocal == null || valorEntregaLocal.signum() < 0)
            throw new IllegalArgumentException("O valor da entrega local não pode ser negativo.");
        valorEntregaLocal = valorEntregaLocal.setScale(2, RoundingMode.HALF_UP);
    }

    public static PoliticaFreteLocal padrao() {
        return new PoliticaFreteLocal(true, true, DIGITOS_PADRAO, VALOR_PADRAO_ENTREGA_LOCAL);
    }

    public boolean reconhece(String freteId) {
        return freteId != null && IDS.contains(freteId);
    }

    public boolean proximos(String cepOrigem, String cepDestino) {
        Optional<String> origem = Cep.normalizado(cepOrigem);
        Optional<String> destino = Cep.normalizado(cepDestino);
        return origem.isPresent() && destino.isPresent()
                && origem.get().regionMatches(0, destino.get(), 0, digitosDoPrefixoCep);
    }

    public List<OpcaoFrete> opcoes(Optional<OrigemAtelie> origem, String cepDestino) {
        if (origem.isEmpty()) return List.of();
        var atelie = origem.get();
        var opcoes = new ArrayList<OpcaoFrete>();
        if (entregaLocalAtiva && atelie.aceitaEntregaLocal() && proximos(atelie.cep(), cepDestino))
            opcoes.add(new OpcaoFrete(ID_ENTREGA_LOCAL, "Entrega local do ateliê", PRAZO_COMBINADO, null, null,
                    valorEntregaLocal, null, PROVEDOR, OpcaoFrete.MODALIDADE_ENTREGA_LOCAL,
                    "Você está perto do ateliê: o próprio artesão entrega"
                            + (vazio(atelie.cidade()) ? "" : " em " + atelie.cidade().trim())
                            + ". O endereço e o horário são combinados pela conversa do pedido."));
        if (retiradaAtiva && atelie.retiradaDisponivel())
            opcoes.add(new OpcaoFrete(ID_RETIRADA, "Retirar no ateliê", PRAZO_COMBINADO, null, null,
                    BigDecimal.ZERO.setScale(2), null, PROVEDOR, OpcaoFrete.MODALIDADE_RETIRADA,
                    "Retirada em " + atelie.bairro().trim() + ", " + atelie.cidade().trim()
                            + ". O endereço exato é combinado pela conversa do pedido."));
        return opcoes;
    }

    private static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }
}
