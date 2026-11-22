package br.com.balaio.commerce.cart.persistence;

import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.cart.application.port.out.SacolaRepository;
import br.com.balaio.commerce.cart.domain.ItemSacola;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SacolaJdbcRepository implements SacolaRepository {
    private final JdbcTemplate jdbc;

    public SacolaJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemSacola> consultar(UUID comprador) {
        return jdbc.query("SELECT slug, quantidade FROM sacola_itens WHERE comprador_id = ? ORDER BY slug",
                (rs, row) -> new ItemSacola(rs.getString("slug"), rs.getInt("quantidade")), comprador);
    }

    @Override
    @Transactional
    public List<ItemSacola> substituir(UUID comprador, List<ItemSacola> itens) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "sacola:" + comprador);
        jdbc.update("DELETE FROM sacola_itens WHERE comprador_id = ?", comprador);
        for (var item : itens)
            jdbc.update("INSERT INTO sacola_itens(comprador_id, slug, quantidade) VALUES (?, ?, ?)",
                    comprador, item.slug(), item.quantidade());
        return consultar(comprador);
    }
}
