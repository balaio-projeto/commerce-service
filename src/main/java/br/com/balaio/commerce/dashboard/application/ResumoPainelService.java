package br.com.balaio.commerce.dashboard.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.com.balaio.commerce.dashboard.application.port.in.ResumoPainelUseCase;
import br.com.balaio.commerce.dashboard.application.port.out.PecasDoPainel;
import br.com.balaio.commerce.dashboard.application.port.out.PecasDoPainel.PecaPainel;
import br.com.balaio.commerce.dashboard.application.port.out.VendasArtesaoRepository;

@Service
public class ResumoPainelService implements ResumoPainelUseCase {
    public static final String VERSAO_CONTRATO = "painel-resumo.v1";
    private static final Logger log = LoggerFactory.getLogger(ResumoPainelService.class);
    private static final List<String> ESTADOS_ATIVOS = List.of("disponivel", "reservada");
    private final VendasArtesaoRepository vendas;
    private final RelatorioVendasService relatorio;
    private final PecasDoPainel catalogo;
    private final boolean paralelo;

    public ResumoPainelService(VendasArtesaoRepository vendas, RelatorioVendasService relatorio, PecasDoPainel catalogo,
            @Value("${balaio.painel.paralelo:true}") boolean paralelo) {
        this.vendas = vendas;
        this.relatorio = relatorio;
        this.catalogo = catalogo;
        this.paralelo = paralelo;
    }

    @Override
    public Resumo resumo(UUID artesao, String tokenArtesao) {
        long inicio = System.nanoTime();
        // Modo paralelo (padrão): a chamada ao catálogo roda em thread virtual enquanto as consultas locais executam.
        // Modo sequencial (balaio.painel.paralelo=false): a chamada ao catálogo termina antes das consultas locais.
        // A falha do catálogo é capturada nos dois modos, e as consultas locais sempre rodam.
        var pecas = paralelo ? catalogoParalelo(artesao, tokenArtesao) : catalogoSequencial(artesao, tokenArtesao);
        var faturamento = relatorio.receitaDoMes(artesao);
        Map<String, Integer> contagem = vendas.pedidosAtivosPorEstado(artesao);
        var indisponiveis = new ArrayList<String>();
        Long ativas = null;
        List<Aviso> avisos = null;
        try {
            var lista = pecas.join();
            ativas = lista.stream().filter(peca -> ESTADOS_ATIVOS.contains(peca.estadoVenda())).count();
            avisos = lista.stream().flatMap(peca -> aviso(peca).stream()).toList();
        } catch (CompletionException falha) {
            log.warn("Catálogo indisponível para o resumo do painel: {}", falha.getCause().getClass().getSimpleName());
            indisponiveis.add("catalogo");
        }
        return new Resumo(VERSAO_CONTRATO, faturamento, contagem.getOrDefault("confirmado", 0),
                contagem.getOrDefault("producao", 0), contagem.getOrDefault("enviado", 0), ativas, avisos,
                !indisponiveis.isEmpty(), List.copyOf(indisponiveis), (System.nanoTime() - inicio) / 1_000_000);
    }

    private CompletableFuture<List<PecaPainel>> catalogoParalelo(UUID artesao, String tokenArtesao) {
        return CompletableFuture.supplyAsync(() -> catalogo.listar(artesao, tokenArtesao),
                runnable -> Thread.ofVirtual().start(runnable));
    }

    private CompletableFuture<List<PecaPainel>> catalogoSequencial(UUID artesao, String tokenArtesao) {
        try {
            return CompletableFuture.completedFuture(catalogo.listar(artesao, tokenArtesao));
        } catch (RuntimeException falha) {
            return CompletableFuture.failedFuture(falha);
        }
    }

    private List<Aviso> aviso(PecaPainel peca) {
        return switch (peca.estadoVenda()) {
            case "vendida" -> List.of(new Aviso(peca.slug(), peca.nome(), "Peça única vendida"));
            case "inativada" -> List.of(new Aviso(peca.slug(), peca.nome(), "Peça inativada"));
            default -> List.of();
        };
    }
}
