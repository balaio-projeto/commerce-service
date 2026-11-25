package br.com.balaio.commerce.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.shipping.application.FreteException;
import br.com.balaio.commerce.shipping.application.FreteService;
import br.com.balaio.commerce.shipping.application.port.in.CotarFreteUseCase.ItemCotacao;
import br.com.balaio.commerce.shipping.application.port.out.OrigemFreteAtelie;
import br.com.balaio.commerce.shipping.application.port.out.PrecoPecasFrete.PecaFrete;
import br.com.balaio.commerce.shipping.application.port.out.ProvedorFrete;
import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.OrigemAtelie;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;

class FreteOrigemAtelieTest {
    private static final UUID ATELIE = UUID.randomUUID();
    private static final UUID OUTRO_ATELIE = UUID.randomUUID();

    private final List<String> origensCotadas = new ArrayList<>();
    private final OrigemFreteAtelie origens = mock(OrigemFreteAtelie.class);
    private final Map<String, PecaFrete> catalogo = Map.of(
            "jarra", new PecaFrete(new BigDecimal("80.00"), ATELIE),
            "vaso", new PecaFrete(new BigDecimal("30.00"), ATELIE),
            "cesto", new PecaFrete(new BigDecimal("50.00"), OUTRO_ATELIE),
            "sem-dono", new PecaFrete(new BigDecimal("10.00"), null));
    private final FreteService service = new FreteService(provedor(), catalogo::get, origens);

    @Test
    void cotacaoPublicaUsaCepDeOrigemDoAtelieDasPecas() {
        when(origens.origem(ATELIE)).thenReturn(Optional.of(OrigemAtelie.somenteCep("56800000")));

        var opcoes = service.cotar("01310-100", List.of(new ItemCotacao("jarra", 2), new ItemCotacao("vaso", 1)));

        assertThat(opcoes).singleElement().satisfies(opcao -> assertThat(opcao.id()).isEqualTo("me-1"));
        assertThat(origensCotadas).containsExactly("56800000");
        verify(origens).origem(ATELIE);
    }

    @Test
    void atelieSemCepOuProfileIndisponivelCaiNoCepPadrao() {
        when(origens.origem(ATELIE)).thenReturn(Optional.empty());

        service.cotar("01310100", List.of(new ItemCotacao("jarra", 1)));

        assertThat(origensCotadas).containsExactly((String) null);
    }

    @Test
    void pecasDeAteliesDiferentesSaoRecusadasSemConsultarOrigem() {
        assertThatThrownBy(() -> service.cotar("01310100",
                List.of(new ItemCotacao("jarra", 1), new ItemCotacao("cesto", 1))))
                .isInstanceOfSatisfying(FreteException.class, erro -> {
                    assertThat(erro.tipo()).isEqualTo(FreteException.Tipo.ATELIES_DIFERENTES);
                    assertThat(erro.codigo()).isEqualTo("ATELIES_DIFERENTES");
                });
        verify(origens, never()).origem(any());
        assertThat(origensCotadas).isEmpty();
    }

    @Test
    void semItensOuSemDonoConhecidoNaoConsultaProfile() {
        service.cotar("01310100", List.of());
        service.cotar("01310100", List.of(new ItemCotacao("sem-dono", 1)));

        verify(origens, never()).origem(any());
        assertThat(origensCotadas).containsExactly(null, null);
    }

    @Test
    void escolhaDoCheckoutUsaOrigemDoArtesaoDoPedidoERegistraNoSnapshot() {
        when(origens.origem(ATELIE)).thenReturn(Optional.of(OrigemAtelie.somenteCep("56800000")));
        var volumes = List.of(new VolumeFrete("jarra", 1, new BigDecimal("80.00")));

        var doAtelie = service.escolher("me-1", "52021-030", ATELIE, volumes);
        var padrao = service.escolher("me-1", "52021-030", OUTRO_ATELIE, volumes);

        assertThat(origensCotadas).containsExactly("56800000", null);
        assertThat(doAtelie.snapshot()).containsEntry("origemFrete", "atelie").doesNotContainValue("56800000");
        assertThat(padrao.snapshot()).containsEntry("origemFrete", "padrao");
    }

    private ProvedorFrete provedor() {
        return new ProvedorFrete() {
            @Override
            public String provedor() {
                return "melhor_envio_sandbox";
            }

            @Override
            public boolean reconheceOpcao(String freteId) {
                return true;
            }

            @Override
            public List<OpcaoFrete> cotar(String cepOrigemAtelie, String cepDestino, List<VolumeFrete> volumes) {
                origensCotadas.add(cepOrigemAtelie);
                return List.of(new OpcaoFrete("me-1", "Correios PAC", "5 dias úteis", 5, 5,
                        new BigDecimal("20.00"), "Correios", "melhor_envio_sandbox"));
            }
        };
    }
}
