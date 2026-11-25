package br.com.balaio.commerce.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
import br.com.balaio.commerce.shipping.domain.PoliticaFreteLocal;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;

class FreteLocalTest {
    private static final UUID ATELIE = UUID.randomUUID();
    private static final OrigemAtelie COMPLETO = new OrigemAtelie("56800000", true, true, "Centro", "Arcoverde");

    private final List<String> cotacoesNoProvedor = new ArrayList<>();
    private final OrigemFreteAtelie origens = mock(OrigemFreteAtelie.class);
    private final Map<String, PecaFrete> catalogo = Map.of("jarra", new PecaFrete(new BigDecimal("80.00"), ATELIE));

    @Test
    void proximidadeUsaPrefixoConfiguravelDoCep() {
        var cincoDigitos = PoliticaFreteLocal.padrao();
        var tresDigitos = new PoliticaFreteLocal(true, true, 3, new BigDecimal("7.5"));

        assertThat(cincoDigitos.proximos("56800-000", "56800-970")).isTrue();
        assertThat(cincoDigitos.proximos("56800000", "56810000")).isFalse();
        assertThat(tresDigitos.proximos("56800000", "56810000")).isTrue();
        assertThat(cincoDigitos.proximos(null, "56800000")).isFalse();
        assertThat(tresDigitos.valorEntregaLocal()).isEqualByComparingTo("7.50");
        assertThatThrownBy(() -> new PoliticaFreteLocal(true, true, 0, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cotacaoMantemTransportadorasEAcrescentaEntregaLocalERetirada() {
        when(origens.origem(ATELIE)).thenReturn(Optional.of(COMPLETO));

        var opcoes = servico(PoliticaFreteLocal.padrao()).cotar("56800-970", List.of(new ItemCotacao("jarra", 1)));

        assertThat(opcoes).extracting(OpcaoFrete::id).containsExactly("me-1", "entrega-local", "retirada-atelie");
        assertThat(opcoes.get(1).valor()).isEqualByComparingTo("0.00");
        assertThat(opcoes.get(1).modalidade()).isEqualTo("entrega_local");
        assertThat(opcoes.get(2).valor()).isEqualByComparingTo("0.00");
        assertThat(opcoes.get(2).detalhe()).contains("Centro, Arcoverde", "combinado pela conversa do pedido")
                .doesNotContain("56800000");
        assertThat(cotacoesNoProvedor).containsExactly("56800000");
    }

    @Test
    void longeDoAtelieSoHaRetiradaESemAceiteNaoHaOpcaoLocal() {
        when(origens.origem(ATELIE)).thenReturn(Optional.of(COMPLETO));
        assertThat(servico(PoliticaFreteLocal.padrao()).cotar("01310100", List.of(new ItemCotacao("jarra", 1))))
                .extracting(OpcaoFrete::id).containsExactly("me-1", "retirada-atelie");

        when(origens.origem(ATELIE)).thenReturn(Optional.of(new OrigemAtelie("56800000", false, false, "Centro", "Arcoverde")));
        assertThat(servico(PoliticaFreteLocal.padrao()).cotar("56800970", List.of(new ItemCotacao("jarra", 1))))
                .extracting(OpcaoFrete::id).containsExactly("me-1");

        when(origens.origem(ATELIE)).thenReturn(Optional.of(new OrigemAtelie("56800000", true, true, " ", null)));
        assertThat(servico(new PoliticaFreteLocal(false, true, 5, BigDecimal.ONE))
                .cotar("56800970", List.of(new ItemCotacao("jarra", 1))))
                .extracting(OpcaoFrete::id).containsExactly("me-1", "entrega-local");
    }

    @Test
    void escolhaDeRetiradaNaoConsultaTransportadoraERegistraModalidade() {
        when(origens.origem(ATELIE)).thenReturn(Optional.of(COMPLETO));
        var servico = servico(PoliticaFreteLocal.padrao());
        var volumes = List.of(new VolumeFrete("jarra", 1, new BigDecimal("80.00")));

        var escolha = servico.escolher("retirada-atelie", "01310-100", ATELIE, volumes);

        assertThat(servico.reconheceOpcao("retirada-atelie")).isTrue();
        assertThat(servico.reconheceOpcao("entrega-local")).isTrue();
        assertThat(escolha.opcao().valor()).isEqualByComparingTo("0");
        assertThat(escolha.snapshot()).containsEntry("modalidade", "retirada").containsEntry("provedor", "balaio_local");
        assertThat(cotacoesNoProvedor).isEmpty();
        assertThatThrownBy(() -> servico.escolher("entrega-local", "01310-100", ATELIE, volumes))
                .isInstanceOfSatisfying(FreteException.class,
                        erro -> assertThat(erro.tipo()).isEqualTo(FreteException.Tipo.OPCAO_INDISPONIVEL));
    }

    private FreteService servico(PoliticaFreteLocal politica) {
        return new FreteService(provedor(), catalogo::get, origens, politica);
    }

    private ProvedorFrete provedor() {
        return new ProvedorFrete() {
            @Override
            public String provedor() {
                return "melhor_envio_sandbox";
            }

            @Override
            public boolean reconheceOpcao(String freteId) {
                return "me-1".equals(freteId);
            }

            @Override
            public List<OpcaoFrete> cotar(String cepOrigemAtelie, String cepDestino, List<VolumeFrete> volumes) {
                cotacoesNoProvedor.add(cepOrigemAtelie);
                return List.of(new OpcaoFrete("me-1", "Correios PAC", "5 dias úteis", 5, 5,
                        new BigDecimal("20.00"), "Correios", "melhor_envio_sandbox"));
            }
        };
    }
}
