package br.com.balaio.commerce.order;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PedidoService {
    private static final BigDecimal FRETE_PADRAO = new BigDecimal("38.90");
    private static final java.util.Set<String> UFS = java.util.Set.of("AC", "AL", "AP", "AM", "BA", "CE",
            "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI",
            "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private final JdbcTemplate jdbc;
    private final CatalogoCliente catalogo;
    private final TransactionTemplate transacoes;
    private final ObjectMapper json;
    private final boolean workerAtivo;

    public PedidoService(JdbcTemplate jdbc, CatalogoCliente catalogo, TransactionTemplate transacoes, ObjectMapper json,
            @Value("${balaio.worker.enabled:true}") boolean workerAtivo) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
        this.transacoes = transacoes;
        this.json = json;
        this.workerAtivo = workerAtivo;
    }

    public record Item(String slug, Integer quantidade) { }
    public record Checkout(List<Item> itens, String freteId, String meio, Map<String, String> endereco) { }

    public UUID checkout(Checkout comando, UUID comprador, String chave) {
        if (comprador == null || vazio(chave) || chave.length() > 160)
            throw erro(400, "REQUISICAO_INVALIDA", "Informe a chave idempotente do checkout.");
        String fingerprint = fingerprint(comando);
        UUID existente = transacoes.execute(status -> buscarCheckoutExistente(comprador, chave, fingerprint));
        if (existente != null) return existente;
        if (comando == null || comando.itens() == null || comando.itens().isEmpty() || comando.itens().size() > 20
                || !"padrao".equals(comando.freteId())
                || !"checkout_pro".equals(comando.meio())
                || comando.endereco() == null || List.of("cep", "endereco", "cidade", "estado").stream()
                        .anyMatch(campo -> vazio(comando.endereco().get(campo)))
                || !comando.endereco().get("cep").matches("[0-9]{5}-?[0-9]{3}")
                || !UFS.contains(comando.endereco().get("estado"))
                || comando.endereco().get("endereco").length() > 200
                || comando.endereco().get("cidade").length() > 100)
            throw erro(400, "REQUISICAO_INVALIDA", "Confira os itens, endereço, frete e pagamento.");
        var slugs = new HashSet<String>();
        var cotados = new ArrayList<ItemCotado>();
        UUID artesao = null;
        BigDecimal total = FRETE_PADRAO;
        for (var item : comando.itens()) {
            if (item == null || vazio(item.slug()) || item.quantidade() == null
                    || item.quantidade() < 1 || item.quantidade() > 3 || !slugs.add(item.slug()))
                throw erro(400, "REQUISICAO_INVALIDA", "Item inválido ou duplicado.");
            var cotacao = catalogo.cotar(item.slug());
            if (cotacao.proprietarioId() == null || (artesao != null && !artesao.equals(cotacao.proprietarioId()))
                    || ("unica".equals(cotacao.disponibilidade()) && item.quantidade() != 1))
                throw erro(409, "PEDIDO_CONFLITANTE", "Finalize separadamente as peças de cada ateliê.");
            artesao = cotacao.proprietarioId();
            cotados.add(new ItemCotado(item.slug(), item.quantidade(), cotacao.preco(), cotacao.disponibilidade()));
            total = total.add(cotacao.preco().multiply(BigDecimal.valueOf(item.quantidade())));
        }
        UUID artesaoFinal = artesao;
        BigDecimal totalFinal = total;
        return transacoes.execute(status -> {
            UUID jaCriado = buscarCheckoutExistente(comprador, chave, fingerprint);
            if (jaCriado != null) return jaCriado;
            UUID id = UUID.randomUUID();
            try {
                jdbc.update("INSERT INTO pedidos(id, comprador_id, artesao_id, estado, chave_checkout, fingerprint_checkout, endereco, frete_id, frete, total, meio) VALUES (?, ?, ?, 'aguardando_reserva', ?, ?, CAST(? AS jsonb), 'padrao', ?, ?, ?)",
                        id, comprador, artesaoFinal, chave, fingerprint, json.writeValueAsString(comando.endereco()), FRETE_PADRAO, totalFinal, comando.meio());
            } catch (JacksonException exception) {
                throw erro(400, "REQUISICAO_INVALIDA", "Endereço inválido.");
            }
            for (var item : cotados)
                jdbc.update("INSERT INTO pedido_itens(pedido_id, slug, quantidade, preco, disponibilidade) VALUES (?, ?, ?, ?, ?)",
                        id, item.slug(), item.quantidade(), item.preco(), item.disponibilidade());
            return id;
        });
    }

    private UUID buscarCheckoutExistente(UUID comprador, String chave, String fingerprint) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", comprador + ":" + chave);
        var existente = jdbc.query("SELECT id, fingerprint_checkout FROM pedidos WHERE comprador_id = ? AND chave_checkout = ?",
                (rs, row) -> Map.entry((UUID) rs.getObject(1), rs.getString(2)), comprador, chave).stream().findFirst();
        if (existente.isPresent()) {
            if (!existente.get().getValue().equals(fingerprint))
                throw erro(409, "CHECKOUT_CONFLITANTE", "Chave já usada para outra compra.");
            return existente.get().getKey();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> consultar(UUID id, UUID comprador) {
        var pedido = obter(id, false);
        if (!pedido.comprador().equals(comprador)) throw erro(404, "PEDIDO_NAO_ENCONTRADO", "Pedido não encontrado.");
        return resposta(pedido);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> consultarArtesao(UUID id, UUID artesao) {
        var pedido = obter(id, false);
        if (!pedido.artesao().equals(artesao)) throw erro(403, "SEM_PERMISSAO", "Este pedido pertence a outro ateliê.");
        return resposta(pedido);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> consultarInterno(UUID id) {
        return resposta(obter(id, false));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar(UUID comprador) {
        return jdbc.query("SELECT id FROM pedidos WHERE comprador_id = ? ORDER BY criado_em DESC",
                (rs, row) -> (UUID) rs.getObject(1), comprador).stream()
                .map(id -> resposta(obter(id, false))).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarVendasArtesao(UUID artesao) {
        return jdbc.query("SELECT id FROM pedidos WHERE artesao_id = ? AND estado IN ('confirmado', 'producao', 'enviado', 'entregue') ORDER BY criado_em DESC",
                (rs, row) -> (UUID) rs.getObject(1), artesao).stream()
                .map(id -> resposta(obter(id, false))).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPendentesArtesao(UUID artesao) {
        return jdbc.query("SELECT id FROM pedidos WHERE artesao_id = ? AND estado = 'confirmado' ORDER BY criado_em DESC",
                (rs, row) -> (UUID) rs.getObject(1), artesao).stream().map(id -> {
                    var pedido = obter(id, false);
                    return Map.<String, Object>of("id", id, "pecaSlug", itens(id).getFirst().slug(),
                            "comprador", "Comprador", "quando", pedido.criadoEm().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                            "valor", pedido.total());
                }).toList();
    }

    @Transactional
    public Map<String, Object> encerrar(UUID id, UUID ator, String tipo, String motivo) {
        var pedido = obter(id, true);
        if ("recusado".equals(tipo)) {
            if (!pedido.artesao().equals(ator)) throw erro(403, "SEM_PERMISSAO", "Este pedido pertence a outro ateliê.");
            if (vazio(motivo) || motivo.trim().length() < 5)
                throw erro(400, "REQUISICAO_INVALIDA", "Informe o motivo da recusa.");
            motivo = motivo.trim();
        } else if (!pedido.comprador().equals(ator)) {
            throw erro(404, "PEDIDO_NAO_ENCONTRADO", "Pedido não encontrado.");
        }
        if (pedido.encerramento() != null) {
            if (pedido.encerramento().equals(tipo) && ("cancelado".equals(tipo) || pedido.motivo().equals(motivo)))
                return resposta(pedido);
            throw erro(409, "PEDIDO_CONFLITANTE", "Outro encerramento já foi iniciado.");
        }
        if ("producao".equals(pedido.estado()) && "cancelado".equals(tipo))
            throw erro(409, "MEDIACAO_NECESSARIA", "A produção já começou. Solicite a mediação da plataforma.");
        if ("recusado".equals(tipo) && !"confirmado".equals(pedido.estado()))
            throw erro(409, "PEDIDO_CONFLITANTE", "Só é possível recusar antes da produção.");
        if ("cancelado".equals(tipo) && !List.of("aguardando_reserva", "aguardando_pagamento", "confirmado").contains(pedido.estado()))
            throw erro(409, "PEDIDO_CONFLITANTE", "Cancelamento comum não permitido neste estado.");
        String destino = "aguardando_pagamento".equals(pedido.estado()) ? "cancelamento_pendente"
                : "aguardando_reserva".equals(pedido.estado()) ? "encerramento_pendente" : "reembolso_pendente";
        jdbc.update("UPDATE pedidos SET estado = ?, encerramento = ?, motivo = ?, atualizado_em = now() WHERE id = ?",
                destino, tipo, motivo, id);
        return resposta(obter(id, false));
    }

    @Transactional
    public Map<String, Object> producao(UUID id, UUID artesao) {
        var pedido = obter(id, true);
        if (!pedido.artesao().equals(artesao)) throw erro(403, "SEM_PERMISSAO", "Este pedido pertence a outro ateliê.");
        if (!"confirmado".equals(pedido.estado()) && !"producao".equals(pedido.estado()))
            throw erro(409, "PEDIDO_CONFLITANTE", "Transição de pedido inválida.");
        if ("confirmado".equals(pedido.estado()))
            jdbc.update("UPDATE pedidos SET estado = 'producao', atualizado_em = now() WHERE id = ?", id);
        return resposta(obter(id, false));
    }

    @Transactional
    public Map<String, Object> mediacao(UUID id, UUID decisaoId, String decisao) {
        if (decisaoId == null || !List.of("cancelar_reembolsar", "negar", "manter_conversa").contains(decisao))
            throw erro(400, "REQUISICAO_INVALIDA", "Decisão de mediação inválida.");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "mediacao:" + decisaoId);
        var pedido = obter(id, true);
        var anterior = jdbc.query("SELECT pedido_id, decisao FROM decisoes_mediacao WHERE id = ?",
                (rs, row) -> Map.entry((UUID) rs.getObject(1), rs.getString(2)), decisaoId).stream().findFirst();
        if (anterior.isPresent()) {
            if (anterior.get().getKey().equals(id) && anterior.get().getValue().equals(decisao)) return resposta(pedido);
            throw erro(409, "MEDIACAO_CONFLITANTE", "Decisão já vinculada a outra operação.");
        }
        if (pedido.encerramento() != null || !List.of("confirmado", "producao", "enviado", "entregue")
                .contains(pedido.estado()))
            throw erro(409, "PEDIDO_CONFLITANTE", "Pedido já encerrado.");
        jdbc.update("INSERT INTO decisoes_mediacao(id, pedido_id, decisao) VALUES (?, ?, ?)", decisaoId, id, decisao);
        if ("cancelar_reembolsar".equals(decisao))
            jdbc.update("UPDATE pedidos SET estado = 'reembolso_pendente', encerramento = 'reembolsado', atualizado_em = now() WHERE id = ?", id);
        return resposta(obter(id, false));
    }

    @Scheduled(fixedDelayString = "${balaio.worker.intervalo-ms:5000}")
    public void processarPendentes() {
        if (!workerAtivo) return;
        var ids = jdbc.query("SELECT id FROM pedidos WHERE estado IN ('aguardando_reserva', 'confirmacao_pendente', 'encerramento_pendente') AND (tentativas < 10 OR estado = 'encerramento_pendente') AND (processamento_ate IS NULL OR processamento_ate < now()) AND atualizado_em < now() - make_interval(secs => least(60, power(2, least(tentativas, 6)))::int) ORDER BY atualizado_em LIMIT 50",
                (rs, row) -> (UUID) rs.getObject(1));
        ids.forEach(this::processar);
    }

    public void processar(UUID id) {
        OffsetDateTime lease = OffsetDateTime.now().plusMinutes(2);
        if (jdbc.update("UPDATE pedidos SET processamento_ate = ? WHERE id = ? "
                + "AND estado IN ('aguardando_reserva', 'confirmacao_pendente', 'encerramento_pendente') "
                + "AND (processamento_ate IS NULL OR processamento_ate < now())", lease, id) == 0) return;
        try {
            var pedido = obter(id, false);
            try {
                switch (pedido.estado()) {
                    case "aguardando_reserva" -> reservarItens(pedido);
                    case "confirmacao_pendente" -> confirmarItens(pedido);
                    case "encerramento_pendente" -> liberarItens(pedido);
                    default -> { return; }
                }
                jdbc.update("UPDATE pedidos SET tentativas = 0, ultimo_erro = NULL, atualizado_em = now() WHERE id = ?", id);
            } catch (HttpClientErrorException.Conflict conflito) {
                if ("aguardando_reserva".equals(pedido.estado()))
                    jdbc.update("UPDATE pedidos SET estado = 'encerramento_pendente', encerramento = ?, ultimo_erro = ?, atualizado_em = now() WHERE id = ?",
                            "falha_reserva",
                            "Conflito com reserva do catálogo.", id);
                else registrarFalha(id, conflito);
            } catch (RuntimeException falha) {
                registrarFalha(id, falha);
            }
        } finally {
            jdbc.update("UPDATE pedidos SET processamento_ate = NULL WHERE id = ? AND processamento_ate = ?", id, lease);
        }
    }

    private void reservarItens(Pedido pedido) {
        BigDecimal total = pedido.frete();
        for (var item : itens(pedido.id())) {
            BigDecimal preco = item.preco();
            if ("unica".equals(item.disponibilidade())) {
                var reserva = catalogo.reservar(pedido.id(), item.slug());
                if (!pedido.artesao().equals(reserva.proprietarioId()))
                    throw erro(409, "PEDIDO_CONFLITANTE", "Peça pertence a outro ateliê.");
                preco = reserva.preco();
                jdbc.update("UPDATE pedido_itens SET reserva_id = ?, preco = ? WHERE pedido_id = ? AND slug = ?",
                        reserva.id(), preco, pedido.id(), item.slug());
            }
            total = total.add(preco.multiply(BigDecimal.valueOf(item.quantidade())));
        }
        jdbc.update("UPDATE pedidos SET estado = 'aguardando_pagamento', total = ?, atualizado_em = now() "
                        + "WHERE id = ? AND estado = 'aguardando_reserva'",
                total, pedido.id());
    }

    private void confirmarItens(Pedido pedido) {
        for (var item : itens(pedido.id())) {
            if (item.reservaId() != null && !"CONSUMIDA".equals(item.reservaEstado())) {
                String estado = catalogo.confirmar(pedido.id(), item.reservaId());
                if (!"CONSUMIDA".equals(estado)) throw erro(409, "RESERVA_CONFLITANTE", "Reserva não confirmada.");
                jdbc.update("UPDATE pedido_itens SET reserva_estado = 'CONSUMIDA' WHERE pedido_id = ? AND slug = ?",
                        pedido.id(), item.slug());
            }
        }
        jdbc.update("UPDATE pedidos SET estado = 'confirmado', atualizado_em = now() "
                + "WHERE id = ? AND estado = 'confirmacao_pendente'", pedido.id());
    }

    private void liberarItens(Pedido pedido) {
        for (var item : itens(pedido.id())) {
            if (!"unica".equals(item.disponibilidade()) || "LIBERADA".equals(item.reservaEstado())
                    || "EXPIRADA".equals(item.reservaEstado())) continue;
            UUID reservaId = item.reservaId();
            if (reservaId == null) {
                try {
                    reservaId = catalogo.reservar(pedido.id(), item.slug()).id();
                    jdbc.update("UPDATE pedido_itens SET reserva_id = ? WHERE pedido_id = ? AND slug = ?",
                            reservaId, pedido.id(), item.slug());
                } catch (HttpClientErrorException.Conflict conflito) {
                    // Nunca houve reserva ativa para esta chave, ou ela já expirou.
                    jdbc.update("UPDATE pedido_itens SET reserva_estado = 'EXPIRADA' WHERE pedido_id = ? AND slug = ?",
                            pedido.id(), item.slug());
                    continue;
                }
            }
            String estado = catalogo.liberar(pedido.id(), reservaId);
            if (!List.of("LIBERADA", "EXPIRADA").contains(estado))
                throw erro(409, "RESERVA_CONFLITANTE", "Liberação não confirmada.");
            jdbc.update("UPDATE pedido_itens SET reserva_estado = ? WHERE pedido_id = ? AND slug = ?",
                    estado, pedido.id(), item.slug());
        }
        jdbc.update("UPDATE pedidos SET estado = ?, atualizado_em = now() "
                + "WHERE id = ? AND estado = 'encerramento_pendente'", pedido.encerramento(), pedido.id());
    }

    private void registrarFalha(UUID id, RuntimeException falha) {
        jdbc.update("UPDATE pedidos SET tentativas = tentativas + 1, ultimo_erro = ?, atualizado_em = now() WHERE id = ?",
                falha.getClass().getSimpleName(), id);
    }

    private Pedido obter(UUID id, boolean bloquear) {
        String sql = "SELECT id, comprador_id, artesao_id, estado, encerramento, motivo, frete, total, criado_em FROM pedidos WHERE id = ?"
                + (bloquear ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new Pedido((UUID) rs.getObject("id"),
                (UUID) rs.getObject("comprador_id"), (UUID) rs.getObject("artesao_id"),
                rs.getString("estado"), rs.getString("encerramento"), rs.getString("motivo"),
                rs.getBigDecimal("frete"), rs.getBigDecimal("total"),
                rs.getObject("criado_em", OffsetDateTime.class)), id).stream().findFirst()
                .orElseThrow(() -> erro(404, "PEDIDO_NAO_ENCONTRADO", "Pedido não encontrado."));
    }

    private List<ItemLinha> itens(UUID pedido) {
        return jdbc.query("SELECT slug, quantidade, preco, disponibilidade, reserva_id, reserva_estado FROM pedido_itens WHERE pedido_id = ? ORDER BY slug",
                (rs, row) -> new ItemLinha(rs.getString("slug"), rs.getInt("quantidade"),
                        rs.getBigDecimal("preco"), rs.getString("disponibilidade"),
                        (UUID) rs.getObject("reserva_id"), rs.getString("reserva_estado")), pedido);
    }

    private Map<String, Object> resposta(Pedido pedido) {
        var dados = new LinkedHashMap<String, Object>();
        var itens = itens(pedido.id());
        dados.put("id", pedido.id());
        dados.put("compradorId", pedido.comprador());
        dados.put("pecaSlug", itens.getFirst().slug());
        dados.put("compradorNome", "Comprador");
        dados.put("data", pedido.criadoEm().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        dados.put("estado", pedido.estado());
        dados.put("total", pedido.total());
        dados.put("avaliado", false);
        dados.put("simulado", false);
        dados.put("motivoEncerramento", pedido.motivo());
        dados.put("etapas", List.of(Map.of("estado", pedido.estado(), "titulo", "Situação do pedido",
                "detalhe", "Acompanhe o andamento desta operação.", "concluida",
                !pedido.estado().endsWith("pendente"), "atual", true)));
        dados.put("itens", itens.stream().map(item -> Map.of("slug", item.slug(),
                "quantidade", item.quantidade(), "preco", item.preco())).toList());
        return dados;
    }

    private boolean vazio(String texto) { return texto == null || texto.isBlank(); }
    private String fingerprint(Checkout comando) {
        try {
            byte[] dados = json.writeValueAsBytes(comando);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw erro(400, "REQUISICAO_INVALIDA", "Checkout inválido.");
        }
    }
    private PedidoException erro(int status, String codigo, String mensagem) {
        return new PedidoException(status, codigo, mensagem);
    }
    private record ItemCotado(String slug, int quantidade, BigDecimal preco, String disponibilidade) { }
    private record ItemLinha(String slug, int quantidade, BigDecimal preco, String disponibilidade,
                             UUID reservaId, String reservaEstado) { }
    private record Pedido(UUID id, UUID comprador, UUID artesao, String estado, String encerramento,
                          String motivo, BigDecimal frete, BigDecimal total, OffsetDateTime criadoEm) { }
}
