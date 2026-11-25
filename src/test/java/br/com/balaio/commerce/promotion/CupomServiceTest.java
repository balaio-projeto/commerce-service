package br.com.balaio.commerce.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.promotion.application.CupomInvalido;
import br.com.balaio.commerce.promotion.application.CupomRecusado;
import br.com.balaio.commerce.promotion.application.CupomService;
import br.com.balaio.commerce.promotion.application.port.out.CupomRepository;
import br.com.balaio.commerce.promotion.domain.Cupom;
import br.com.balaio.commerce.promotion.domain.DadosCupom;
import br.com.balaio.commerce.promotion.domain.RecusaCupom;
import br.com.balaio.commerce.promotion.domain.SituacaoCupom;
import br.com.balaio.commerce.promotion.domain.TipoCupom;

class CupomServiceTest {
    private static final Instant AGORA = Instant.parse("2026-11-25T15:00:00Z");
    private final CuponsEmMemoria repositorio = new CuponsEmMemoria();
    private final CupomService servico = new CupomService(repositorio, Clock.fixed(AGORA, ZoneOffset.UTC));
    private final UUID comprador = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @Test
    void codigoDigitadoEhNormalizadoEValidadoNoServidor() {
        servico.criar(dados("BALAIO10", "percentual", "10", null), admin);

        var aplicado = servico.avaliar(" balaio10 ", comprador, new BigDecimal("120.00"), new BigDecimal("20.00"));

        assertThat(aplicado).hasValueSatisfying(cupom -> {
            assertThat(cupom.codigo()).isEqualTo("BALAIO10");
            assertThat(cupom.desconto().produtos()).isEqualByComparingTo("12.00");
            assertThat(cupom.snapshot()).containsEntry("custeadoPor", "plataforma").containsEntry("tipo", "percentual");
        });
        assertRecusa("NAO-EXISTE", RecusaCupom.INVALIDO);
        assertRecusa("x!", RecusaCupom.INVALIDO);
    }

    @Test
    void recusaExpiradoEsgotadoPedidoMinimoEFreteJaGratis() {
        servico.criar(new DadosCupom("VENCIDO", "Vencido", "valor_fixo", BigDecimal.TEN, null, null,
                null, OffsetDateTime.ofInstant(AGORA.minusSeconds(60), ZoneOffset.UTC), null, null, true), admin);
        servico.criar(new DadosCupom("MINIMO", "Mínimo", "valor_fixo", BigDecimal.TEN, null, new BigDecimal("200"),
                null, null, null, null, true), admin);
        servico.criar(dados("FRETEZERO", "frete_gratis", null, null), admin);
        var esgotado = servico.criar(new DadosCupom("ULTIMO", "Último", "valor_fixo", BigDecimal.TEN, null, null,
                null, null, 1, null, true), admin);
        repositorio.registrarUso(esgotado.cupom().id(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN);

        assertRecusa("VENCIDO", RecusaCupom.EXPIRADO);
        assertRecusa("MINIMO", RecusaCupom.PEDIDO_MINIMO);
        assertRecusa("ULTIMO", RecusaCupom.ESGOTADO);
        assertThatThrownBy(() -> servico.avaliar("FRETEZERO", comprador, new BigDecimal("100"), BigDecimal.ZERO))
                .isInstanceOfSatisfying(CupomRecusado.class, erro -> assertThat(erro.motivo()).isEqualTo(RecusaCupom.SEM_DESCONTO));
        assertThat(servico.avaliar("FRETEZERO", comprador, new BigDecimal("100"), null)).isPresent();
    }

    @Test
    void semCodigoAplicaAMelhorPromocaoAutomaticaElegivel() {
        servico.criar(dados(null, "percentual", "5", null), admin);
        servico.criar(dados(null, "frete_gratis", null, null), admin);
        servico.criar(new DadosCupom(null, "Só acima de 500", "valor_fixo", new BigDecimal("100"), null,
                new BigDecimal("500"), null, null, null, null, true), admin);
        servico.criar(new DadosCupom(null, "Desligada", "valor_fixo", new BigDecimal("90"), null, null,
                null, null, null, null, false), admin);

        var comFreteCaro = servico.avaliar(null, comprador, new BigDecimal("100.00"), new BigDecimal("30.00"));
        var comRetirada = servico.avaliar("", comprador, new BigDecimal("100.00"), BigDecimal.ZERO);

        assertThat(comFreteCaro).hasValueSatisfying(cupom -> {
            assertThat(cupom.automatico()).isTrue();
            assertThat(cupom.tipo()).isEqualTo(TipoCupom.FRETE_GRATIS);
            assertThat(cupom.desconto().frete()).isEqualByComparingTo("30.00");
        });
        assertThat(comRetirada).hasValueSatisfying(cupom ->
                assertThat(cupom.desconto().produtos()).isEqualByComparingTo("5.00"));
    }

    @Test
    void reservaRevalidaComTravaELimitePorComprador() {
        var criado = servico.criar(new DadosCupom("UMAVEZ", "Uma vez", "valor_fixo", BigDecimal.TEN, null, null,
                null, null, null, 1, true), admin);
        var previsto = servico.avaliar("UMAVEZ", comprador, new BigDecimal("50"), BigDecimal.ZERO).orElseThrow();
        repositorio.registrarUso(criado.cupom().id(), UUID.randomUUID(), comprador, BigDecimal.TEN);

        assertThatThrownBy(() -> servico.reservar(previsto, comprador, new BigDecimal("50"), BigDecimal.ZERO))
                .isInstanceOfSatisfying(CupomRecusado.class,
                        erro -> assertThat(erro.motivo()).isEqualTo(RecusaCupom.LIMITE_POR_COMPRADOR));
        assertThat(repositorio.travados).containsExactly(criado.cupom().id());
        assertThat(servico.reservar(previsto, UUID.randomUUID(), new BigDecimal("50"), BigDecimal.ZERO).desconto().produtos())
                .isEqualByComparingTo("10.00");
    }

    @Test
    void administracaoValidaCodigoDuplicadoLimiteESituacao() {
        var criado = servico.criar(dados("DUPLICADO", "valor_fixo", "10", null), admin);
        assertThatThrownBy(() -> servico.criar(dados("duplicado", "valor_fixo", "5", null), admin))
                .isInstanceOfSatisfying(CupomInvalido.class,
                        erro -> assertThat(erro.campos()).containsEntry("codigo", "Já existe um cupom com este código."));
        assertThat(servico.editar(criado.cupom().id(), dados("DUPLICADO", "valor_fixo", "12", null)).cupom().valor())
                .isEqualByComparingTo("12");
        assertThat(servico.definirAtivo(criado.cupom().id(), false).situacao()).isEqualTo(SituacaoCupom.INATIVO);
        assertRecusa("DUPLICADO", RecusaCupom.INATIVO);
    }

    private void assertRecusa(String codigo, RecusaCupom motivo) {
        assertThatThrownBy(() -> servico.avaliar(codigo, comprador, new BigDecimal("100.00"), new BigDecimal("10.00")))
                .isInstanceOfSatisfying(CupomRecusado.class, erro -> assertThat(erro.motivo()).isEqualTo(motivo));
    }

    private static DadosCupom dados(String codigo, String tipo, String valor, String teto) {
        return new DadosCupom(codigo, "Promoção " + tipo, tipo, valor == null ? null : new BigDecimal(valor),
                teto == null ? null : new BigDecimal(teto), null, null, null, null, null, true);
    }

    private static final class CuponsEmMemoria implements CupomRepository {
        private final Map<UUID, Cupom> cupons = new HashMap<>();
        private final List<Uso> usos = new ArrayList<>();
        private final List<UUID> travados = new ArrayList<>();
        private int sequencia;

        @Override
        public List<Cupom> listar() {
            return cupons.values().stream().sorted(Comparator.comparing(Cupom::criadoEm)).toList();
        }

        @Override
        public Optional<Cupom> buscar(UUID id) {
            return Optional.ofNullable(cupons.get(id));
        }

        @Override
        public Optional<Cupom> buscarPorCodigo(String codigo) {
            return cupons.values().stream().filter(cupom -> codigo.equals(cupom.codigo())).findFirst();
        }

        @Override
        public Optional<Cupom> travar(UUID id) {
            travados.add(id);
            return buscar(id);
        }

        @Override
        public List<Cupom> automaticosVigentes(OffsetDateTime agora) {
            return listar().stream().filter(cupom -> cupom.automatico() && cupom.ativo()).toList();
        }

        @Override
        public int usosDoComprador(UUID cupom, UUID comprador) {
            return (int) usos.stream().filter(uso -> uso.cupom().equals(cupom) && uso.comprador().equals(comprador)).count();
        }

        @Override
        public boolean codigoEmUso(String codigo, UUID excetoCupom) {
            return cupons.values().stream().anyMatch(cupom -> codigo.equals(cupom.codigo()) && !cupom.id().equals(excetoCupom));
        }

        @Override
        public void criar(UUID id, DadosCupom dados, UUID administrador) {
            var criadoEm = OffsetDateTime.ofInstant(AGORA, ZoneOffset.UTC).plusSeconds(sequencia++);
            cupons.put(id, cupom(id, dados, 0, criadoEm));
        }

        @Override
        public void atualizar(UUID id, DadosCupom dados) {
            var atual = cupons.get(id);
            cupons.put(id, cupom(id, dados, atual.usos(), atual.criadoEm()));
        }

        @Override
        public void definirAtivo(UUID id, boolean ativo) {
            var c = cupons.get(id);
            cupons.put(id, new Cupom(c.id(), c.codigo(), c.descricao(), c.tipo(), c.valor(), c.descontoMaximo(),
                    c.pedidoMinimo(), c.inicio(), c.fim(), c.limiteUsos(), c.limitePorComprador(), c.usos(), ativo,
                    c.criadoEm(), c.atualizadoEm()));
        }

        @Override
        public void registrarUso(UUID cupom, UUID pedido, UUID comprador, BigDecimal desconto) {
            usos.add(new Uso(cupom, comprador));
            var c = cupons.get(cupom);
            cupons.put(cupom, new Cupom(c.id(), c.codigo(), c.descricao(), c.tipo(), c.valor(), c.descontoMaximo(),
                    c.pedidoMinimo(), c.inicio(), c.fim(), c.limiteUsos(), c.limitePorComprador(), c.usos() + 1, c.ativo(),
                    c.criadoEm(), c.atualizadoEm()));
        }

        private static Cupom cupom(UUID id, DadosCupom dados, int usos, OffsetDateTime criadoEm) {
            return new Cupom(id, dados.codigo(), dados.descricao(), dados.tipoCupom(), dados.valor(), dados.descontoMaximo(),
                    dados.pedidoMinimo(), dados.inicio(), dados.fim(), dados.limiteUsos(), dados.limitePorComprador(), usos,
                    dados.ativo(), criadoEm, criadoEm);
        }

        private record Uso(UUID cupom, UUID comprador) { }
    }
}
