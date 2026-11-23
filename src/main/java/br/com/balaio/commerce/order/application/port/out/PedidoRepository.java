package br.com.balaio.commerce.order.application.port.out;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.balaio.commerce.order.domain.ItemPedido;
import br.com.balaio.commerce.order.domain.Pedido;

public interface PedidoRepository {
    Optional<Pedido> buscar(UUID id);
    Optional<Pedido> buscarParaAtualizacao(UUID id);
    List<ItemPedido> itens(UUID pedido);
    Object endereco(UUID id);
    boolean historicoIncompleto(UUID id);
    List<EtapaRegistrada> etapas(UUID id);
    List<UUID> pedidosDoComprador(UUID comprador);
    List<UUID> vendasDoArtesao(UUID artesao);
    List<PendenteArtesao> pendentesDoArtesao(UUID artesao);

    Optional<CheckoutExistente> buscarCheckoutSobLock(UUID comprador, String chave);
    void criarCheckout(UUID id, UUID comprador, UUID artesao, String chave, String fingerprint,
            Map<String, String> endereco, FreteDoCheckout frete, BigDecimal total, String meio, List<ItemPedido> itens);

    void iniciarEncerramento(UUID id, String destino, String tipo, String motivo);
    void iniciarProducao(UUID id);
    void avancarEstado(UUID id, String destino);
    void registrarEnvio(UUID id, String rastreio, String transportadora);
    void travarDecisao(UUID decisaoId);
    Optional<DecisaoRegistrada> buscarDecisao(UUID decisaoId);
    boolean recebeuDecisao(UUID id);
    void registrarDecisao(UUID decisaoId, UUID id, String decisao);
    void iniciarReembolsoPorMediacao(UUID id);

    List<UUID> pedidosPendentes();
    boolean adquirirLease(UUID id, OffsetDateTime lease);
    void liberarLease(UUID id, OffsetDateTime lease);
    void concluirTentativa(UUID id);
    void registrarFalha(UUID id, String diagnostico);
    void iniciarCompensacaoPorConflito(UUID id);
    void registrarReserva(UUID id, String slug, UUID reservaId, BigDecimal preco);
    void registrarReservaRecuperada(UUID id, String slug, UUID reservaId, String estado);
    void registrarEstadoReserva(UUID id, String slug, String estado);
    void concluirReserva(UUID id, BigDecimal total);
    void concluirConfirmacao(UUID id);
    void concluirEncerramento(UUID id, String destino);

    record EtapaRegistrada(String estado, String titulo, String detalhe, String nota, OffsetDateTime criadaEm) { }
    record PendenteArtesao(UUID id, String pecaSlug, OffsetDateTime quando, BigDecimal valor) { }
    record CheckoutExistente(UUID id, String fingerprint) { }
    record FreteDoCheckout(String id, BigDecimal valor, Map<String, Object> cotacao) { }
    record DecisaoRegistrada(UUID pedidoId, String decisao) { }
}
