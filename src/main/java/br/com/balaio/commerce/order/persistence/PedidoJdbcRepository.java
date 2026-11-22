package br.com.balaio.commerce.order.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.balaio.commerce.order.application.PedidoException;
import br.com.balaio.commerce.order.application.port.in.PedidoParaPagamento.ItemCobranca;
import br.com.balaio.commerce.order.application.port.out.PedidoFinanceiroRepository;
import br.com.balaio.commerce.order.application.port.out.PedidoRepository;
import br.com.balaio.commerce.order.domain.ItemPedido;
import br.com.balaio.commerce.order.domain.Pedido;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PedidoJdbcRepository implements PedidoRepository, PedidoFinanceiroRepository {
    private static final String PRAZO_CANCELAMENTO_EXPIRADO = "atualizado_em < now() - interval '14 minutes'";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PedidoJdbcRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Pedido> buscar(UUID id) {
        return buscar(id, false);
    }

    @Override
    public Optional<Pedido> buscarParaAtualizacao(UUID id) {
        return buscar(id, true);
    }

    private Optional<Pedido> buscar(UUID id, boolean bloquear) {
        String sql = "SELECT id, comprador_id, artesao_id, estado, encerramento, motivo, frete, total, criado_em FROM pedidos WHERE id = ?"
                + (bloquear ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new Pedido((UUID) rs.getObject("id"),
                (UUID) rs.getObject("comprador_id"), (UUID) rs.getObject("artesao_id"),
                rs.getString("estado"), rs.getString("encerramento"), rs.getString("motivo"),
                rs.getBigDecimal("frete"), rs.getBigDecimal("total"),
                rs.getObject("criado_em", OffsetDateTime.class)), id).stream().findFirst();
    }

    @Override
    public List<ItemPedido> itens(UUID pedido) {
        return jdbc.query("SELECT slug, quantidade, preco, disponibilidade, reserva_id, reserva_estado FROM pedido_itens WHERE pedido_id = ? ORDER BY slug",
                (rs, row) -> new ItemPedido(rs.getString("slug"), rs.getInt("quantidade"),
                        rs.getBigDecimal("preco"), rs.getString("disponibilidade"),
                        (UUID) rs.getObject("reserva_id"), rs.getString("reserva_estado")), pedido);
    }

    @Override
    public Object endereco(UUID id) {
        try {
            return json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(jdbc.queryForObject("SELECT endereco::text FROM pedidos WHERE id = ?", String.class, id));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Endereço armazenado inválido.", exception);
        }
    }

    @Override
    public boolean historicoIncompleto(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT historico_incompleto FROM pedidos WHERE id = ?", Boolean.class, id));
    }

    @Override
    public List<EtapaRegistrada> etapas(UUID id) {
        return jdbc.query("SELECT estado, titulo, detalhe, nota, criada_em FROM pedido_etapas "
                + "WHERE pedido_id = ? ORDER BY id", (rs, row) -> new EtapaRegistrada(rs.getString("estado"),
                        rs.getString("titulo"), rs.getString("detalhe"), rs.getString("nota"),
                        rs.getObject("criada_em", OffsetDateTime.class)), id);
    }

    @Override
    public List<UUID> pedidosDoComprador(UUID comprador) {
        return jdbc.query("SELECT id FROM pedidos WHERE comprador_id = ? ORDER BY criado_em DESC",
                (rs, row) -> (UUID) rs.getObject(1), comprador);
    }

    @Override
    public List<UUID> vendasDoArtesao(UUID artesao) {
        return jdbc.query("SELECT id FROM pedidos WHERE artesao_id = ? AND estado IN ('confirmado', 'producao', 'enviado', 'entregue') ORDER BY criado_em DESC",
                (rs, row) -> (UUID) rs.getObject(1), artesao);
    }

    @Override
    public List<PendenteArtesao> pendentesDoArtesao(UUID artesao) {
        return jdbc.query("SELECT p.id, p.criado_em, p.total, "
                        + "(SELECT i.slug FROM pedido_itens i WHERE i.pedido_id = p.id ORDER BY i.slug LIMIT 1) AS peca_slug "
                        + "FROM pedidos p WHERE p.artesao_id = ? AND p.estado = 'confirmado' ORDER BY p.criado_em DESC",
                (rs, row) -> new PendenteArtesao((UUID) rs.getObject("id"), rs.getString("peca_slug"),
                        rs.getObject("criado_em", OffsetDateTime.class), rs.getBigDecimal("total")), artesao);
    }

    @Override
    public Optional<CheckoutExistente> buscarCheckoutSobLock(UUID comprador, String chave) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", comprador + ":" + chave);
        return jdbc.query("SELECT id, fingerprint_checkout FROM pedidos WHERE comprador_id = ? AND chave_checkout = ?",
                (rs, row) -> new CheckoutExistente((UUID) rs.getObject(1), rs.getString(2)),
                comprador, chave).stream().findFirst();
    }

    @Override
    public void criarCheckout(UUID id, UUID comprador, UUID artesao, String chave, String fingerprint,
            Map<String, String> endereco, BigDecimal frete, BigDecimal total, String meio, List<ItemPedido> itens) {
        try {
            jdbc.update("INSERT INTO pedidos(id, comprador_id, artesao_id, estado, chave_checkout, fingerprint_checkout, endereco, frete_id, frete, total, meio) VALUES (?, ?, ?, 'aguardando_reserva', ?, ?, CAST(? AS jsonb), 'padrao', ?, ?, ?)",
                    id, comprador, artesao, chave, fingerprint, json.writeValueAsString(endereco), frete, total, meio);
        } catch (JacksonException exception) {
            throw new PedidoException(PedidoException.Tipo.REQUISICAO_INVALIDA, "Endereço inválido.");
        }
        for (var item : itens)
            jdbc.update("INSERT INTO pedido_itens(pedido_id, slug, quantidade, preco, disponibilidade) VALUES (?, ?, ?, ?, ?)",
                    id, item.slug(), item.quantidade(), item.preco(), item.disponibilidade());
    }

    @Override
    public void iniciarEncerramento(UUID id, String destino, String tipo, String motivo) {
        jdbc.update("UPDATE pedidos SET estado = ?, encerramento = ?, motivo = ?, atualizado_em = now() WHERE id = ?",
                destino, tipo, motivo, id);
    }

    @Override
    public void iniciarProducao(UUID id) {
        jdbc.update("UPDATE pedidos SET estado = 'producao', atualizado_em = now() WHERE id = ?", id);
    }

    @Override
    public void travarDecisao(UUID decisaoId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "mediacao:" + decisaoId);
    }

    @Override
    public Optional<DecisaoRegistrada> buscarDecisao(UUID decisaoId) {
        return jdbc.query("SELECT pedido_id, decisao FROM decisoes_mediacao WHERE id = ?",
                (rs, row) -> new DecisaoRegistrada((UUID) rs.getObject(1), rs.getString(2)), decisaoId)
                .stream().findFirst();
    }

    @Override
    public boolean recebeuDecisao(UUID id) {
        return !jdbc.queryForList("SELECT id FROM decisoes_mediacao WHERE pedido_id = ? LIMIT 1", UUID.class, id).isEmpty();
    }

    @Override
    public void registrarDecisao(UUID decisaoId, UUID id, String decisao) {
        jdbc.update("INSERT INTO decisoes_mediacao(id, pedido_id, decisao) VALUES (?, ?, ?)", decisaoId, id, decisao);
    }

    @Override
    public void iniciarReembolsoPorMediacao(UUID id) {
        jdbc.update("UPDATE pedidos SET estado = 'reembolso_pendente', encerramento = 'mediacao_cancelar_reembolsar', atualizado_em = now() WHERE id = ?", id);
    }

    @Override
    public List<UUID> pedidosPendentes() {
        return jdbc.query("SELECT id FROM pedidos WHERE estado IN ('aguardando_reserva', 'confirmacao_pendente', 'encerramento_pendente') AND (tentativas < 10 OR estado = 'encerramento_pendente') AND (processamento_ate IS NULL OR processamento_ate < now()) AND atualizado_em < now() - make_interval(secs => least(60, power(2, least(tentativas, 6)))::int) ORDER BY atualizado_em LIMIT 50",
                (rs, row) -> (UUID) rs.getObject(1));
    }

    @Override
    public boolean adquirirLease(UUID id, OffsetDateTime lease) {
        return jdbc.update("UPDATE pedidos SET processamento_ate = ? WHERE id = ? "
                + "AND estado IN ('aguardando_reserva', 'confirmacao_pendente', 'encerramento_pendente') "
                + "AND (processamento_ate IS NULL OR processamento_ate < now())", lease, id) != 0;
    }

    @Override
    public void liberarLease(UUID id, OffsetDateTime lease) {
        jdbc.update("UPDATE pedidos SET processamento_ate = NULL WHERE id = ? AND processamento_ate = ?", id, lease);
    }

    @Override
    public void concluirTentativa(UUID id) {
        jdbc.update("UPDATE pedidos SET tentativas = 0, ultimo_erro = NULL, atualizado_em = now() WHERE id = ?", id);
    }

    @Override
    public void registrarFalha(UUID id, String diagnostico) {
        jdbc.update("UPDATE pedidos SET tentativas = tentativas + 1, ultimo_erro = ?, atualizado_em = now() WHERE id = ?",
                diagnostico, id);
    }

    @Override
    public void iniciarCompensacaoPorConflito(UUID id) {
        jdbc.update("UPDATE pedidos SET estado = 'encerramento_pendente', encerramento = ?, ultimo_erro = ?, atualizado_em = now() WHERE id = ?",
                "falha_reserva", "Conflito com reserva do catálogo.", id);
    }

    @Override
    public void registrarReserva(UUID id, String slug, UUID reservaId, BigDecimal preco) {
        jdbc.update("UPDATE pedido_itens SET reserva_id = ?, preco = ? WHERE pedido_id = ? AND slug = ?",
                reservaId, preco, id, slug);
    }

    @Override
    public void registrarReservaRecuperada(UUID id, String slug, UUID reservaId, String estado) {
        jdbc.update("UPDATE pedido_itens SET reserva_id = ?, reserva_estado = ? WHERE pedido_id = ? AND slug = ?",
                reservaId, estado, id, slug);
    }

    @Override
    public void registrarEstadoReserva(UUID id, String slug, String estado) {
        jdbc.update("UPDATE pedido_itens SET reserva_estado = ? WHERE pedido_id = ? AND slug = ?", estado, id, slug);
    }

    @Override
    public void concluirReserva(UUID id, BigDecimal total) {
        jdbc.update("UPDATE pedidos SET estado = 'aguardando_pagamento', total = ?, atualizado_em = now() "
                + "WHERE id = ? AND estado = 'aguardando_reserva'", total, id);
    }

    @Override
    public void concluirConfirmacao(UUID id) {
        jdbc.update("UPDATE pedidos SET estado = 'confirmado', atualizado_em = now() "
                + "WHERE id = ? AND estado = 'confirmacao_pendente'", id);
    }

    @Override
    public void concluirEncerramento(UUID id, String destino) {
        jdbc.update("UPDATE pedidos SET estado = ?, atualizado_em = now() "
                + "WHERE id = ? AND estado = 'encerramento_pendente'", destino, id);
    }

    @Override
    public List<UUID> reservasComTentativasEsgotadas() {
        return jdbc.query("SELECT id FROM pedidos WHERE estado = 'aguardando_reserva' "
                + "AND tentativas >= 10 LIMIT 30", (rs, row) -> (UUID) rs.getObject(1));
    }

    @Override
    public List<UUID> confirmacoesComTentativasEsgotadas() {
        return jdbc.query("SELECT id FROM pedidos WHERE estado = 'confirmacao_pendente' "
                + "AND tentativas >= 10 LIMIT 30", (rs, row) -> (UUID) rs.getObject(1));
    }

    @Override
    public List<UUID> operacoesFinanceirasPendentes() {
        return jdbc.query("SELECT id FROM pedidos WHERE estado IN ('cancelamento_pendente', 'reembolso_pendente') "
                + "LIMIT 30", (rs, row) -> (UUID) rs.getObject(1));
    }

    @Override
    public List<ItemCobranca> itensParaCobranca(UUID id) {
        return jdbc.query("SELECT slug, quantidade, preco FROM pedido_itens WHERE pedido_id = ? ORDER BY slug",
                (rs, row) -> new ItemCobranca(rs.getString(1), rs.getInt(2), rs.getBigDecimal(3)), id);
    }

    @Override
    public BigDecimal freteParaCobranca(UUID id) {
        return jdbc.queryForObject("SELECT frete FROM pedidos WHERE id = ?", BigDecimal.class, id);
    }

    @Override
    public BigDecimal totalParaCobranca(UUID id) {
        return jdbc.queryForObject("SELECT total FROM pedidos WHERE id = ?", BigDecimal.class, id);
    }

    @Override
    public String estadoParaPagamento(UUID id) {
        return jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ?", String.class, id);
    }

    @Override
    public String travarPedidoParaPagamento(UUID id) {
        return jdbc.queryForObject("SELECT estado FROM pedidos WHERE id = ? FOR UPDATE", String.class, id);
    }

    @Override
    public boolean cancelamentoSemPagamentoExpirou(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT " + PRAZO_CANCELAMENTO_EXPIRADO + " FROM pedidos WHERE id = ?", Boolean.class, id));
    }

    @Override
    public boolean encerrarCancelamentoSemPagamento(UUID id, boolean exigirPrazo) {
        return jdbc.update("UPDATE pedidos SET estado = 'encerramento_pendente', "
                + "encerramento = COALESCE(encerramento, 'cancelado'), atualizado_em = now() "
                + "WHERE id = ? AND estado = 'cancelamento_pendente'"
                + (exigirPrazo ? " AND " + PRAZO_CANCELAMENTO_EXPIRADO : ""), id) == 1;
    }

    @Override
    public void mudarEstadoFinanceiro(UUID id, String estado, String causa) {
        jdbc.update("UPDATE pedidos SET estado = ?, encerramento = COALESCE(encerramento, ?), atualizado_em = now() "
                + "WHERE id = ?", estado, causa, id);
    }

    @Override
    public boolean mudarEstadoFinanceiroSe(UUID id, String anterior, String destino, String causa) {
        return jdbc.update("UPDATE pedidos SET estado = ?, encerramento = COALESCE(encerramento, ?), "
                + "atualizado_em = now() WHERE id = ? AND estado = ?", destino, causa, id, anterior) == 1;
    }

    @Override
    public void encerrarPagamentoExpirado(UUID id) {
        jdbc.update("UPDATE pedidos SET estado = 'encerramento_pendente', encerramento = 'falha_pagamento', "
                + "atualizado_em = now() WHERE id = ? AND estado = 'aguardando_pagamento'", id);
    }
}
