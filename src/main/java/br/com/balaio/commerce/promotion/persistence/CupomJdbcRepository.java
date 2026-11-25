package br.com.balaio.commerce.promotion.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.balaio.commerce.promotion.application.port.out.CupomRepository;
import br.com.balaio.commerce.promotion.domain.Cupom;
import br.com.balaio.commerce.promotion.domain.DadosCupom;
import br.com.balaio.commerce.promotion.domain.TipoCupom;

@Repository
public class CupomJdbcRepository implements CupomRepository {
    private static final String COLUNAS = "id, codigo, descricao, tipo, valor, desconto_maximo, pedido_minimo, inicio, fim, "
            + "limite_usos, limite_por_comprador, usos, ativo, criado_em, atualizado_em";
    private final JdbcTemplate jdbc;

    public CupomJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Cupom> listar() {
        return jdbc.query("SELECT " + COLUNAS + " FROM cupons ORDER BY criado_em DESC, id", this::cupom);
    }

    @Override
    public Optional<Cupom> buscar(UUID id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cupons WHERE id = ?", this::cupom, id).stream().findFirst();
    }

    @Override
    public Optional<Cupom> buscarPorCodigo(String codigo) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cupons WHERE codigo = ?", this::cupom, codigo).stream().findFirst();
    }

    @Override
    public Optional<Cupom> travar(UUID id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cupons WHERE id = ? FOR UPDATE", this::cupom, id).stream().findFirst();
    }

    @Override
    public List<Cupom> automaticosVigentes(OffsetDateTime agora) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cupons WHERE codigo IS NULL AND ativo "
                + "AND (inicio IS NULL OR inicio <= ?) AND (fim IS NULL OR fim >= ?) "
                + "AND (limite_usos IS NULL OR usos < limite_usos) ORDER BY criado_em, id", this::cupom, agora, agora);
    }

    @Override
    public int usosDoComprador(UUID cupom, UUID comprador) {
        Integer usos = jdbc.queryForObject("SELECT count(*) FROM cupom_usos WHERE cupom_id = ? AND comprador_id = ? "
                + "AND estado = 'consumido'", Integer.class, cupom, comprador);
        return usos == null ? 0 : usos;
    }

    @Override
    public boolean codigoEmUso(String codigo, UUID excetoCupom) {
        Boolean existe = excetoCupom == null
                ? jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cupons WHERE codigo = ?)", Boolean.class, codigo)
                : jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cupons WHERE codigo = ? AND id <> ?)",
                        Boolean.class, codigo, excetoCupom);
        return Boolean.TRUE.equals(existe);
    }

    @Override
    public void criar(UUID id, DadosCupom dados, UUID administrador) {
        jdbc.update("INSERT INTO cupons(id, codigo, descricao, tipo, valor, desconto_maximo, pedido_minimo, inicio, fim, "
                + "limite_usos, limite_por_comprador, ativo, criado_por) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, dados.codigo(), dados.descricao(), dados.tipoCupom().codigo(), dados.valor(), dados.descontoMaximo(),
                dados.pedidoMinimo(), dados.inicio(), dados.fim(), dados.limiteUsos(), dados.limitePorComprador(),
                dados.ativo(), administrador);
    }

    @Override
    public void atualizar(UUID id, DadosCupom dados) {
        jdbc.update("UPDATE cupons SET codigo = ?, descricao = ?, tipo = ?, valor = ?, desconto_maximo = ?, "
                + "pedido_minimo = ?, inicio = ?, fim = ?, limite_usos = ?, limite_por_comprador = ?, ativo = ?, "
                + "atualizado_em = now() WHERE id = ?",
                dados.codigo(), dados.descricao(), dados.tipoCupom().codigo(), dados.valor(), dados.descontoMaximo(),
                dados.pedidoMinimo(), dados.inicio(), dados.fim(), dados.limiteUsos(), dados.limitePorComprador(),
                dados.ativo(), id);
    }

    @Override
    public void definirAtivo(UUID id, boolean ativo) {
        jdbc.update("UPDATE cupons SET ativo = ?, atualizado_em = now() WHERE id = ?", ativo, id);
    }

    @Override
    public void registrarUso(UUID cupom, UUID pedido, UUID comprador, BigDecimal desconto) {
        jdbc.update("INSERT INTO cupom_usos(pedido_id, cupom_id, comprador_id, desconto) VALUES (?, ?, ?, ?)",
                pedido, cupom, comprador, desconto);
        jdbc.update("UPDATE cupons SET usos = usos + 1, atualizado_em = now() WHERE id = ?", cupom);
    }

    private Cupom cupom(ResultSet rs, int linha) throws SQLException {
        return new Cupom((UUID) rs.getObject("id"), rs.getString("codigo"), rs.getString("descricao"),
                TipoCupom.de(rs.getString("tipo")).orElseThrow(), rs.getBigDecimal("valor"),
                rs.getBigDecimal("desconto_maximo"), rs.getBigDecimal("pedido_minimo"),
                rs.getObject("inicio", OffsetDateTime.class), rs.getObject("fim", OffsetDateTime.class),
                rs.getObject("limite_usos", Integer.class), rs.getObject("limite_por_comprador", Integer.class),
                rs.getInt("usos"), rs.getBoolean("ativo"), rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
