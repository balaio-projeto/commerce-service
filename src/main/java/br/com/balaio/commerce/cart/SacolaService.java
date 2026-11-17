package br.com.balaio.commerce.cart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import br.com.balaio.commerce.order.CatalogoCliente;
import br.com.balaio.commerce.order.PedidoException;

@Service
public class SacolaService {
    public record Item(String slug, Integer quantidade) { }
    public record ItemRevalidado(String slug, int quantidade, BigDecimal precoAtual,
            String disponibilidade, String situacao) { }
    public record Revalidacao(List<ItemRevalidado> itens, boolean podeProsseguir, Instant revalidadoEm) { }

    private final JdbcTemplate jdbc;
    private final CatalogoCliente catalogo;

    public SacolaService(JdbcTemplate jdbc, CatalogoCliente catalogo) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
    }

    @Transactional(readOnly = true)
    public List<Item> consultar(UUID comprador) {
        return jdbc.query("SELECT slug, quantidade FROM sacola_itens WHERE comprador_id = ? ORDER BY slug",
                (rs, row) -> new Item(rs.getString("slug"), rs.getInt("quantidade")), comprador);
    }

    @Transactional(readOnly = true)
    public Revalidacao revalidar(UUID comprador) {
        var resultado = new ArrayList<ItemRevalidado>();
        boolean podeProsseguir = true;
        for (var item : consultar(comprador)) {
            try {
                var cotacao = catalogo.cotar(item.slug());
                if (cotacao == null || !item.slug().equals(cotacao.slug()) || cotacao.preco() == null
                        || cotacao.preco().signum() < 0
                        || !List.of("unica", "encomenda", "disponivel").contains(cotacao.disponibilidade()))
                    throw new PedidoException(503, "CATALOGO_INDISPONIVEL", "Cotação inválida do catálogo.");
                if ("unica".equals(cotacao.disponibilidade()) && item.quantidade() != 1) {
                    resultado.add(new ItemRevalidado(item.slug(), item.quantidade(), null,
                            "indisponivel", "indisponivel"));
                    podeProsseguir = false;
                } else {
                    resultado.add(new ItemRevalidado(item.slug(), item.quantidade(), cotacao.preco(),
                            cotacao.disponibilidade(), "disponivel"));
                }
            } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Conflict erro) {
                resultado.add(new ItemRevalidado(item.slug(), item.quantidade(), null,
                        "indisponivel", "indisponivel"));
                podeProsseguir = false;
            } catch (IllegalArgumentException | IllegalStateException erro) {
                throw new PedidoException(503, "CATALOGO_INDISPONIVEL", "Cotação inválida do catálogo.");
            }
        }
        return new Revalidacao(List.copyOf(resultado), podeProsseguir && !resultado.isEmpty(), Instant.now());
    }

    @Transactional
    public List<Item> substituir(UUID comprador, List<Item> itens) {
        if (itens == null || itens.size() > 20)
            throw new PedidoException(400, "REQUISICAO_INVALIDA", "Sacola inválida.");
        var slugs = new HashSet<String>();
        var validados = new ArrayList<Item>();
        for (var item : itens) {
            if (item == null || item.slug() == null || !item.slug().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || item.slug().length() > 160 || item.quantidade() == null || item.quantidade() < 1
                    || item.quantidade() > 3 || !slugs.add(item.slug()))
                throw new PedidoException(400, "REQUISICAO_INVALIDA", "Item inválido ou duplicado.");
            try {
                var cotacao = catalogo.cotar(item.slug());
                if (!item.slug().equals(cotacao.slug()) || cotacao.preco() == null
                        || cotacao.preco().signum() < 0 || !List.of("unica", "encomenda", "disponivel").contains(cotacao.disponibilidade())
                        || ("unica".equals(cotacao.disponibilidade()) && item.quantidade() != 1))
                    throw new PedidoException(409, "PECA_INDISPONIVEL", "Peça indisponível ou quantidade inválida.");
            } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Conflict erro) {
                throw new PedidoException(409, "PECA_INDISPONIVEL", "Peça indisponível.");
            }
            validados.add(item);
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "sacola:" + comprador);
        jdbc.update("DELETE FROM sacola_itens WHERE comprador_id = ?", comprador);
        for (var item : validados)
            jdbc.update("INSERT INTO sacola_itens(comprador_id, slug, quantidade) VALUES (?, ?, ?)",
                    comprador, item.slug(), item.quantidade());
        return consultar(comprador);
    }
}
