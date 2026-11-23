package br.com.balaio.commerce.dashboard.persistence;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.balaio.commerce.dashboard.application.port.out.VendasArtesaoRepository;

@Repository
public class VendasArtesaoJdbcRepository implements VendasArtesaoRepository {
    private static final String CONSULTA = "SELECT p.id, p.frete, i.slug, i.quantidade, i.preco, "
            + "COALESCE((SELECT min(e.criada_em) FROM pedido_etapas e WHERE e.pedido_id = p.id "
            + "AND e.estado = 'confirmado'), p.criado_em) AS vendido_em "
            + "FROM pedidos p JOIN pedido_itens i ON i.pedido_id = p.id "
            + "WHERE p.artesao_id = ? AND p.encerramento IS NULL "
            + "AND p.estado IN ('confirmado', 'producao', 'enviado', 'entregue')";
    private final JdbcTemplate jdbc;

    public VendasArtesaoJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LinhaVenda> vendas(UUID artesao, OffsetDateTime inicio, OffsetDateTime fimExclusivo) {
        return jdbc.query("SELECT * FROM (" + CONSULTA + ") vendas WHERE vendido_em >= ? AND vendido_em < ? "
                        + "ORDER BY vendido_em, id, slug",
                (rs, row) -> new LinhaVenda((UUID) rs.getObject("id"), rs.getBigDecimal("frete"), rs.getString("slug"),
                        rs.getInt("quantidade"), rs.getBigDecimal("preco"),
                        rs.getObject("vendido_em", OffsetDateTime.class)),
                artesao, inicio, fimExclusivo);
    }

    @Override
    public Map<String, Integer> pedidosAtivosPorEstado(UUID artesao) {
        var contagem = new LinkedHashMap<String, Integer>();
        jdbc.query("SELECT estado, count(*) AS total FROM pedidos WHERE artesao_id = ? AND encerramento IS NULL "
                + "AND estado IN ('confirmado', 'producao', 'enviado') GROUP BY estado",
                rs -> { contagem.put(rs.getString("estado"), rs.getInt("total")); }, artesao);
        return contagem;
    }
}
