package br.com.balaio.commerce.dashboard.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ResumoPainelUseCase {
    Resumo resumo(UUID artesao, String tokenArtesao);

    record Resumo(String versaoContrato, BigDecimal faturamentoMes, int pedidosAAceitar, int emProducao,
            int enviados, Long pecasAtivas, List<Aviso> avisos, boolean parcial, List<String> fontesIndisponiveis,
            long duracaoMs) { }
    record Aviso(String slug, String nome, String motivo) { }
}
