package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

class PedidoServiceValidacaoCheckoutTest {
    private final CatalogoCliente catalogo = mock(CatalogoCliente.class);
    private final PedidoService service = new PedidoService(mock(JdbcTemplate.class), catalogo,
            mock(TransactionTemplate.class), JsonMapper.builder().build(), false);

    @Test
    void pendentesSaoFiltradosEProjetadosNaMesmaConsulta() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID artesao = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(artesao))).thenAnswer(chamada -> {
            String sql = chamada.getArgument(0);
            assertThat(sql).contains("p.artesao_id = ?", "p.estado = 'confirmado'", "p.criado_em",
                    "p.total", "pedido_itens i", "i.slug");
            return List.of();
        });
        var pedidos = new PedidoService(jdbc, catalogo, mock(TransactionTemplate.class),
                JsonMapper.builder().build(), false);
        assertThat(pedidos.listarPendentesArtesao(artesao)).isEmpty();
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(artesao));
        verifyNoMoreInteractions(jdbc);
    }

    private Map<String, String> enderecoValido() {
        var endereco = new HashMap<String, String>();
        endereco.put("cep", "01310-100");
        endereco.put("endereco", "Avenida Paulista, 1000");
        endereco.put("cidade", "São Paulo");
        endereco.put("estado", "SP");
        return endereco;
    }

    private PedidoService.Checkout checkout(String frete, String meio, Map<String, String> endereco) {
        return new PedidoService.Checkout(List.of(new PedidoService.Item("vaso", 1)), frete, meio, endereco);
    }

    private void assertRequisicaoInvalida(PedidoService.Checkout comando) {
        assertThatThrownBy(() -> service.checkout(comando, UUID.randomUUID(), "chave-1"))
                .isInstanceOfSatisfying(PedidoException.class, erro -> {
                    assertThat(erro.status()).isEqualTo(400);
                    assertThat(erro.codigo()).isEqualTo("REQUISICAO_INVALIDA");
                    assertThat(erro.getMessage())
                            .isEqualTo("Confira os itens, endereço, frete e pagamento.");
                });
    }

    @Test
    void recusaFreteDiferenteDoPadrao() {
        assertRequisicaoInvalida(checkout("expresso", "checkout_pro", enderecoValido()));
    }

    @Test
    void recusaMeioDePagamentoNaoAceito() {
        assertRequisicaoInvalida(checkout("padrao", "pix", enderecoValido()));
    }

    @Test
    void recusaUfInexistente() {
        var endereco = enderecoValido();
        endereco.put("estado", "XX");
        assertRequisicaoInvalida(checkout("padrao", "checkout_pro", endereco));
    }

    @Test
    void recusaCepForaDoFormato() {
        var endereco = enderecoValido();
        endereco.put("cep", "0131-100");
        assertRequisicaoInvalida(checkout("padrao", "checkout_pro", endereco));
    }

    @Test
    void recusaCepAusenteSemEstourarNullPointer() {
        var endereco = enderecoValido();
        endereco.remove("cep");
        assertRequisicaoInvalida(checkout("padrao", "checkout_pro", endereco));
    }

    @Test
    void recusaEnderecoECidadeLongosDemais() {
        var enderecoLongo = enderecoValido();
        enderecoLongo.put("endereco", "a".repeat(201));
        assertRequisicaoInvalida(checkout("padrao", "checkout_pro", enderecoLongo));

        var cidadeLonga = enderecoValido();
        cidadeLonga.put("cidade", "a".repeat(101));
        assertRequisicaoInvalida(checkout("padrao", "checkout_pro", cidadeLonga));
    }

    @Test
    void recusaMaisDeVinteItens() {
        var itens = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> new PedidoService.Item("peca-" + i, 1)).toList();
        assertRequisicaoInvalida(new PedidoService.Checkout(itens, "padrao", "checkout_pro", enderecoValido()));
    }

    @Test
    void pecaUnicaRepetidaConflitaAntesDeValidarOItemSeguinte() {
        when(catalogo.cotar("unica")).thenReturn(new CatalogoCliente.Cotacao("unica", new BigDecimal("50.00"),
                "unica", "Ateliê", UUID.randomUUID()));
        var itens = List.of(new PedidoService.Item("unica", 2), new PedidoService.Item("outra", 0));
        var comando = new PedidoService.Checkout(itens, "padrao", "checkout_pro", enderecoValido());

        assertThatThrownBy(() -> service.checkout(comando, UUID.randomUUID(), "chave-1"))
                .isInstanceOfSatisfying(PedidoException.class, erro ->
                        assertThat(erro.status()).isEqualTo(409));
    }
}
