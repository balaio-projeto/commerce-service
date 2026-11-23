package br.com.balaio.commerce.notification.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.balaio.commerce.notification.application.port.in.EmailsPedidoUseCase.SituacaoEmail;
import br.com.balaio.commerce.notification.application.port.out.FilaEmailsPedido;
import br.com.balaio.commerce.notification.domain.EnvioEmail;

@Repository
public class FilaEmailsPedidoJdbcRepository implements FilaEmailsPedido {
    private final JdbcTemplate jdbc;

    public FilaEmailsPedidoJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<EnvioEmail> reservarLote(int limite) {
        return jdbc.query("UPDATE pedido_emails SET proxima_tentativa_em = now() + interval '2 minutes' "
                + "WHERE (pedido_id, evento, destinatario) IN (SELECT pedido_id, evento, destinatario FROM pedido_emails "
                + "WHERE estado = 'pendente' AND proxima_tentativa_em <= now() ORDER BY proxima_tentativa_em "
                + "LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING pedido_id, evento, destinatario",
                (rs, row) -> new EnvioEmail((UUID) rs.getObject("pedido_id"), rs.getString("evento"),
                        rs.getString("destinatario")), limite);
    }

    @Override
    public void marcarEnviado(EnvioEmail envio) {
        jdbc.update("UPDATE pedido_emails SET estado = 'enviado', enviado_em = now(), tentativas = tentativas + 1, "
                + "ultimo_erro = NULL WHERE pedido_id = ? AND evento = ? AND destinatario = ? AND estado = 'pendente'",
                envio.pedido(), envio.evento(), envio.destinatario());
    }

    @Override
    public void registrarFalha(EnvioEmail envio, String erro, int maximoTentativas) {
        jdbc.update("UPDATE pedido_emails SET tentativas = tentativas + 1, ultimo_erro = ?, "
                + "estado = CASE WHEN tentativas + 1 >= ? THEN 'falhou' ELSE 'pendente' END, "
                + "proxima_tentativa_em = now() + make_interval(secs => least(3600, 30 * power(2, least(tentativas, 7)))::int) "
                + "WHERE pedido_id = ? AND evento = ? AND destinatario = ? AND estado = 'pendente'",
                erro, maximoTentativas, envio.pedido(), envio.evento(), envio.destinatario());
    }

    @Override
    public List<SituacaoEmail> situacao(UUID pedido) {
        return jdbc.query("SELECT evento, destinatario, estado, tentativas, ultimo_erro, proxima_tentativa_em, enviado_em "
                + "FROM pedido_emails WHERE pedido_id = ? ORDER BY criado_em, evento, destinatario",
                (rs, row) -> new SituacaoEmail(rs.getString("evento"), rs.getString("destinatario"),
                        rs.getString("estado"), rs.getInt("tentativas"), rs.getString("ultimo_erro"),
                        rs.getObject("proxima_tentativa_em", OffsetDateTime.class),
                        rs.getObject("enviado_em", OffsetDateTime.class)), pedido);
    }

    @Override
    public int reagendarFalhas(UUID pedido) {
        return jdbc.update("UPDATE pedido_emails SET estado = 'pendente', tentativas = 0, proxima_tentativa_em = now() "
                + "WHERE pedido_id = ? AND estado = 'falhou'", pedido);
    }

    @Override
    public DadosPedido dadosPedido(UUID pedido) {
        return jdbc.queryForObject("SELECT comprador_id, artesao_id, total, rastreio, transportadora FROM pedidos WHERE id = ?",
                (rs, row) -> new DadosPedido((UUID) rs.getObject("comprador_id"), (UUID) rs.getObject("artesao_id"),
                        rs.getBigDecimal("total"), rs.getString("rastreio"), rs.getString("transportadora")), pedido);
    }
}
