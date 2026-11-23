package br.com.balaio.commerce.order.application;

import br.com.balaio.commerce.order.application.PedidoException.Tipo;
import br.com.balaio.commerce.order.application.port.in.ConsultarPedidosUseCase;
import br.com.balaio.commerce.order.application.port.in.ComandosPedidoUseCase;
import br.com.balaio.commerce.order.application.port.in.ProcessarPedidosPendentesUseCase;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase;
import br.com.balaio.commerce.order.application.port.out.FingerprintCheckout;
import br.com.balaio.commerce.order.application.port.out.PedidoRepository;
import br.com.balaio.commerce.order.domain.EstadoPedido;
import br.com.balaio.commerce.order.domain.ItemPedido;
import br.com.balaio.commerce.order.domain.Pedido;
import br.com.balaio.commerce.order.domain.RegrasCheckout;
import br.com.balaio.commerce.order.domain.RegrasEnvio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos.ErroCatalogo;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos.Falha;
import br.com.balaio.commerce.order.application.port.out.EnderecoSalvo;
import br.com.balaio.commerce.order.application.port.out.FretePedido;
import br.com.balaio.commerce.order.application.port.out.PedidoRepository.FreteDoCheckout;

@Service
public class PedidoService implements CheckoutPedidoUseCase, ConsultarPedidosUseCase,
        ComandosPedidoUseCase, ProcessarPedidosPendentesUseCase {
    private final PedidoRepository repositorio;
    private final CatalogoPedidos catalogo;
    private final EnderecoSalvo enderecos;
    private final TransactionTemplate transacoes;
    private final FingerprintCheckout fingerprints;
    private final FretePedido fretes;

    public PedidoService(PedidoRepository repositorio, CatalogoPedidos catalogo, EnderecoSalvo enderecos,
            TransactionTemplate transacoes, FingerprintCheckout fingerprints, FretePedido fretes) {
        this.repositorio = repositorio;
        this.catalogo = catalogo;
        this.enderecos = enderecos;
        this.transacoes = transacoes;
        this.fingerprints = fingerprints;
        this.fretes = fretes;
    }

    @Override
    public UUID checkout(Checkout comando, UUID comprador, String chave) {
        validarChave(comprador, chave);
        if (comando == null) throw comandoInvalido();
        String fingerprint = fingerprints.calcular(comando);
        UUID existente = transacoes.execute(status -> buscarCheckoutExistente(comprador, chave, fingerprint));
        if (existente != null) return existente;
        validarComando(comando);
        ItensCotados cotacao = cotarItens(comando.itens());
        return persistir(comando, comprador, chave, fingerprint, cotacao, escolherFrete(comando, cotacao));
    }

    @Override
    public UUID checkoutSalvo(Checkout comando, UUID comprador, String chave,
            String tokenComprador) {
        validarChave(comprador, chave);
        if (comando == null || comando.enderecoId() == null) throw comandoInvalido();
        String fingerprint = fingerprints.calcular(comando);
        UUID existente = transacoes.execute(status -> buscarCheckoutExistente(comprador, chave, fingerprint));
        if (existente != null) return existente;
        Checkout resolvido = new Checkout(comando.itens(), comando.freteId(), comando.meio(),
                enderecos.consultar(comando.enderecoId(), tokenComprador), comando.enderecoId());
        validarComando(resolvido);
        ItensCotados cotacao = cotarItens(resolvido.itens());
        return persistir(resolvido, comprador, chave, fingerprint, cotacao, escolherFrete(resolvido, cotacao));
    }

    private void validarChave(UUID comprador, String chave) {
        if (!RegrasCheckout.chaveValida(comprador, chave))
            throw new PedidoException(Tipo.REQUISICAO_INVALIDA, "Informe a chave idempotente do checkout.");
    }

    private void validarComando(Checkout comando) {
        if (!RegrasCheckout.comandoValido(comando.itens(), fretes.reconhece(comando.freteId()), comando.meio(),
                comando.endereco()))
            throw comandoInvalido();
    }

    private PedidoException comandoInvalido() {
        return new PedidoException(Tipo.REQUISICAO_INVALIDA, "Confira os itens, endereço, frete e pagamento.");
    }

    private ItensCotados cotarItens(List<Item> itens) {
        var slugs = new HashSet<String>();
        var cotados = new ArrayList<ItemPedido>();
        UUID artesao = null;
        BigDecimal total = BigDecimal.ZERO;
        for (var item : itens) {
            validarItem(item, slugs);
            var cotacao = catalogo.cotar(item.slug());
            validarCotacao(cotacao, artesao, item.quantidade());
            artesao = cotacao.proprietarioId();
            cotados.add(new ItemPedido(item.slug(), item.quantidade(), cotacao.preco(), cotacao.disponibilidade(), null, null));
            total = total.add(cotacao.preco().multiply(BigDecimal.valueOf(item.quantidade())));
        }
        return new ItensCotados(artesao, total, cotados);
    }

    private void validarItem(Item item, Set<String> slugsJaVistos) {
        if (item == null || !RegrasCheckout.itemValido(item.slug(), item.quantidade()) || !slugsJaVistos.add(item.slug()))
            throw new PedidoException(Tipo.REQUISICAO_INVALIDA, "Item inválido ou duplicado.");
    }

    private void validarCotacao(CatalogoPedidos.Cotacao cotacao, UUID artesaoDoPedido, int quantidade) {
        if (!RegrasCheckout.cotacaoCompativel(cotacao.proprietarioId(), artesaoDoPedido, cotacao.disponibilidade(), quantidade))
            throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Finalize separadamente as peças de cada ateliê.");
    }

    private FreteDoCheckout escolherFrete(Checkout comando, ItensCotados cotacao) {
        var itens = cotacao.itens().stream()
                .map(item -> new FretePedido.ItemFrete(item.slug(), item.quantidade(), item.preco())).toList();
        var escolhido = fretes.escolher(comando.freteId(), comando.endereco().get("cep"), itens);
        return new FreteDoCheckout(escolhido.id(), escolhido.valor(), escolhido.snapshot());
    }

    private UUID persistir(Checkout comando, UUID comprador, String chave, String fingerprint, ItensCotados cotacao,
            FreteDoCheckout frete) {
        return transacoes.execute(status -> {
            UUID jaCriado = buscarCheckoutExistente(comprador, chave, fingerprint);
            if (jaCriado != null) return jaCriado;
            UUID id = UUID.randomUUID();
            repositorio.criarCheckout(id, comprador, cotacao.artesao(), chave, fingerprint, comando.endereco(),
                    frete, cotacao.total().add(frete.valor()), comando.meio(), cotacao.itens());
            return id;
        });
    }

    private UUID buscarCheckoutExistente(UUID comprador, String chave, String fingerprint) {
        return repositorio.buscarCheckoutSobLock(comprador, chave).map(existente -> {
            if (!existente.fingerprint().equals(fingerprint))
                throw new PedidoException(Tipo.CHECKOUT_CONFLITANTE, "Chave já usada para outra compra.");
            return existente.id();
        }).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Detalhe consultar(UUID id, UUID comprador) {
        var pedido = obter(id, false);
        if (!pedido.comprador().equals(comprador)) throw new PedidoException(Tipo.PEDIDO_NAO_ENCONTRADO, "Pedido não encontrado.");
        return new Detalhe(resumo(pedido), repositorio.endereco(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Resumo consultarArtesao(UUID id, UUID artesao) {
        var pedido = obter(id, false);
        if (!pedido.artesao().equals(artesao)) throw new PedidoException(Tipo.SEM_PERMISSAO, "Este pedido pertence a outro ateliê.");
        return resumo(pedido);
    }

    @Override
    @Transactional(readOnly = true)
    public Resumo consultarInterno(UUID id) {
        return resumo(obter(id, false));
    }

    @Override
    @Transactional(readOnly = true)
    public Participantes participantes(UUID id) {
        var pedido = obter(id, false);
        return new Participantes(pedido.comprador(), pedido.artesao());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Resumo> listar(UUID comprador) {
        return repositorio.pedidosDoComprador(comprador).stream().map(id -> resumo(obter(id, false))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Resumo> listarVendasArtesao(UUID artesao) {
        return repositorio.vendasDoArtesao(artesao).stream().map(id -> resumo(obter(id, false))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pendente> listarPendentesArtesao(UUID artesao) {
        return repositorio.pendentesDoArtesao(artesao).stream()
                .map(pendente -> new Pendente(pendente.id(), pendente.pecaSlug(), pendente.quando(),
                        pendente.valor())).toList();
    }

    @Override
    @Transactional
    public Resumo encerrar(UUID id, UUID ator, String tipo, String motivo) {
        var pedido = obter(id, true);
        if ("recusado".equals(tipo)) {
            if (!pedido.artesao().equals(ator)) throw new PedidoException(Tipo.SEM_PERMISSAO, "Este pedido pertence a outro ateliê.");
            if (vazio(motivo) || motivo.trim().length() < 5)
                throw new PedidoException(Tipo.REQUISICAO_INVALIDA, "Informe o motivo da recusa.");
            motivo = motivo.trim();
        } else if (!pedido.comprador().equals(ator)) {
            throw new PedidoException(Tipo.PEDIDO_NAO_ENCONTRADO, "Pedido não encontrado.");
        }
        if (pedido.encerramento() != null) {
            if (pedido.encerramento().equals(tipo) && ("cancelado".equals(tipo) || pedido.motivo().equals(motivo)))
                return resumo(pedido);
            throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Outro encerramento já foi iniciado.");
        }
        if (EstadoPedido.producaoIniciada(pedido.estado()) && "cancelado".equals(tipo))
            throw new PedidoException(Tipo.MEDIACAO_NECESSARIA, "A produção já começou. Solicite a mediação da plataforma.");
        if ("recusado".equals(tipo) && !EstadoPedido.permiteProducao(pedido.estado()))
            throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Só é possível recusar antes da produção.");
        if ("cancelado".equals(tipo) && !EstadoPedido.permiteCancelamento(pedido.estado()))
            throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Cancelamento comum não permitido neste estado.");
        repositorio.iniciarEncerramento(id, EstadoPedido.iniciarEncerramento(pedido.estado()), tipo, motivo);
        return resumo(obter(id, false));
    }

    @Override
    @Transactional
    public Resumo producao(UUID id, UUID artesao) {
        var pedido = obter(id, true);
        if (!pedido.artesao().equals(artesao)) throw new PedidoException(Tipo.SEM_PERMISSAO, "Este pedido pertence a outro ateliê.");
        if (!EstadoPedido.permiteProducao(pedido.estado()))
            throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Transição de pedido inválida.");
        repositorio.iniciarProducao(id);
        return resumo(obter(id, false));
    }

    @Override
    @Transactional
    public Resumo avancar(UUID id, UUID artesao, String destino, String rastreio, String transportadora) {
        String anteriorExigido = EstadoPedido.anteriorExigidoPara(destino);
        if (anteriorExigido == null) throw new PedidoException(Tipo.REQUISICAO_INVALIDA, "Estado inválido.");
        boolean envio = "enviado".equals(destino);
        String codigo = envio ? rastreioValido(rastreio) : null;
        String empresa = envio ? transportadoraValida(transportadora) : null;
        var pedido = obter(id, true);
        if (!pedido.artesao().equals(artesao)) throw new PedidoException(Tipo.SEM_PERMISSAO, "Este pedido pertence a outro ateliê.");
        if (pedido.encerramento() != null || !anteriorExigido.equals(pedido.estado()))
            throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Transição de pedido inválida.");
        if (envio) repositorio.registrarEnvio(id, codigo, empresa);
        else repositorio.avancarEstado(id, destino);
        return resumo(obter(id, false));
    }

    private String rastreioValido(String rastreio) {
        return RegrasEnvio.rastreioNormalizado(rastreio).orElseThrow(() -> new PedidoException(Tipo.RASTREIO_INVALIDO,
                "Informe o código de rastreio com 5 a 40 letras, números ou hífens."));
    }

    private String transportadoraValida(String transportadora) {
        String nome = RegrasEnvio.transportadoraNormalizada(transportadora);
        if (!RegrasEnvio.transportadoraValida(nome))
            throw new PedidoException(Tipo.REQUISICAO_INVALIDA, "Nome da transportadora muito longo.");
        return nome;
    }

    @Override
    @Transactional
    public Resumo mediacao(UUID id, UUID decisaoId, String decisao) {
        if (decisaoId == null || !List.of("cancelar_reembolsar", "negar", "manter_conversa").contains(decisao))
            throw new PedidoException(Tipo.REQUISICAO_INVALIDA, "Decisão de mediação inválida.");
        repositorio.travarDecisao(decisaoId);
        var pedido = obter(id, true);
        var anterior = repositorio.buscarDecisao(decisaoId);
        if (anterior.isPresent()) {
            if (anterior.get().pedidoId().equals(id) && anterior.get().decisao().equals(decisao)) return resumo(pedido);
            throw new PedidoException(Tipo.MEDIACAO_CONFLITANTE, "Decisão já vinculada a outra operação.");
        }
        if (repositorio.recebeuDecisao(id))
            throw new PedidoException(Tipo.MEDIACAO_CONFLITANTE, "Este pedido já recebeu uma decisão de mediação.");
        if (pedido.encerramento() != null || !EstadoPedido.permiteMediacao(pedido.estado()))
            throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Pedido já encerrado.");
        repositorio.registrarDecisao(decisaoId, id, decisao);
        if ("cancelar_reembolsar".equals(decisao)) repositorio.iniciarReembolsoPorMediacao(id);
        return resumo(obter(id, false));
    }

    @Override
    public void processarPendentes() {
        repositorio.pedidosPendentes().forEach(this::processar);
    }

    @Override
    public void processar(UUID id) {
        OffsetDateTime lease = OffsetDateTime.now().plusMinutes(2);
        if (!repositorio.adquirirLease(id, lease)) return;
        try {
            var pedido = obter(id, false);
            try {
                switch (pedido.estado()) {
                    case "aguardando_reserva" -> reservarItens(pedido);
                    case "confirmacao_pendente" -> confirmarItens(pedido);
                    case "encerramento_pendente" -> liberarItens(pedido);
                    default -> { return; }
                }
                repositorio.concluirTentativa(id);
            } catch (ErroCatalogo falha) {
                if (falha.conflito() && "aguardando_reserva".equals(pedido.estado()))
                    repositorio.iniciarCompensacaoPorConflito(id);
                else repositorio.registrarFalha(id, falha.diagnostico());
            } catch (RuntimeException falha) {
                repositorio.registrarFalha(id, falha.getClass().getSimpleName());
            }
        } finally {
            repositorio.liberarLease(id, lease);
        }
    }

    private void reservarItens(Pedido pedido) {
        BigDecimal total = pedido.frete();
        for (var item : repositorio.itens(pedido.id())) {
            BigDecimal preco = item.preco();
            if ("unica".equals(item.disponibilidade())) {
                var reserva = catalogo.reservar(pedido.id(), item.slug());
                if (!"RESERVADA".equals(reserva.estado()) && !"CONSUMIDA".equals(reserva.estado()))
                    throw new PedidoException(Tipo.RESERVA_CONFLITANTE, "Reserva não está ativa.");
                if (!pedido.artesao().equals(reserva.proprietarioId()))
                    throw new PedidoException(Tipo.PEDIDO_CONFLITANTE, "Peça pertence a outro ateliê.");
                preco = reserva.preco();
                repositorio.registrarReserva(pedido.id(), item.slug(), reserva.id(), preco);
            }
            total = total.add(preco.multiply(BigDecimal.valueOf(item.quantidade())));
        }
        repositorio.concluirReserva(pedido.id(), total);
    }

    private void confirmarItens(Pedido pedido) {
        for (var item : repositorio.itens(pedido.id())) {
            if (item.reservaId() != null && !"CONSUMIDA".equals(item.reservaEstado())) {
                String estado = catalogo.confirmar(pedido.id(), item.reservaId());
                if (!"CONSUMIDA".equals(estado)) throw new PedidoException(Tipo.RESERVA_CONFLITANTE, "Reserva não confirmada.");
                repositorio.registrarEstadoReserva(pedido.id(), item.slug(), "CONSUMIDA");
            }
        }
        repositorio.concluirConfirmacao(pedido.id());
    }

    private void liberarItens(Pedido pedido) {
        for (var item : repositorio.itens(pedido.id())) {
            if (!"unica".equals(item.disponibilidade()) || "LIBERADA".equals(item.reservaEstado())
                    || "EXPIRADA".equals(item.reservaEstado()) || "SEM_RESERVA".equals(item.reservaEstado())) continue;
            UUID reservaId = item.reservaId();
            if (reservaId == null) {
                try {
                    var recuperada = catalogo.reservar(pedido.id(), item.slug());
                    reservaId = recuperada.id();
                    repositorio.registrarReservaRecuperada(pedido.id(), item.slug(), reservaId, recuperada.estado());
                    if (List.of("LIBERADA", "EXPIRADA").contains(recuperada.estado())) continue;
                    if (!List.of("RESERVADA", "CONSUMIDA").contains(recuperada.estado()))
                        throw new PedidoException(Tipo.RESERVA_CONFLITANTE, "Estado da reserva desconhecido.");
                } catch (ErroCatalogo conflito) {
                    if (conflito.falha() != Falha.RESERVA_AUSENTE) throw conflito;
                    repositorio.registrarEstadoReserva(pedido.id(), item.slug(), "SEM_RESERVA");
                    continue; // A chave estável não possui reserva anterior e a peça está indisponível.
                }
            }
            String estado = catalogo.liberar(pedido.id(), reservaId);
            if (!List.of("LIBERADA", "EXPIRADA").contains(estado))
                throw new PedidoException(Tipo.RESERVA_CONFLITANTE, "Liberação não confirmada.");
            repositorio.registrarEstadoReserva(pedido.id(), item.slug(), estado);
        }
        repositorio.concluirEncerramento(pedido.id(), EstadoPedido.concluirEncerramento(pedido.encerramento()));
    }

    private Pedido obter(UUID id, boolean bloquear) {
        return (bloquear ? repositorio.buscarParaAtualizacao(id) : repositorio.buscar(id))
                .orElseThrow(() -> new PedidoException(Tipo.PEDIDO_NAO_ENCONTRADO, "Pedido não encontrado."));
    }

    private Resumo resumo(Pedido pedido) {
        var itens = repositorio.itens(pedido.id());
        boolean historicoInferido = repositorio.historicoIncompleto(pedido.id());
        var registradas = repositorio.etapas(pedido.id());
        var etapas = new ArrayList<Etapa>();
        if (registradas.isEmpty()) {
            historicoInferido = true;
            etapas.add(new Etapa(pedido.estado(), "Situação atual", "O histórico anterior não está disponível.",
                    null, null, false, true, true));
        } else {
            for (int i = 0; i < registradas.size(); i++) {
                var etapa = registradas.get(i);
                boolean atual = i == registradas.size() - 1;
                etapas.add(new Etapa(etapa.estado(), etapa.titulo(), etapa.detalhe(), etapa.nota(), etapa.criadaEm(),
                        !atual || EstadoPedido.etapaConcluida(pedido.estado()), atual, false));
            }
        }
        return new Resumo(pedido.id(), pedido.comprador(), pedido.artesao(), itens.getFirst().slug(),
                pedido.criadoEm(), pedido.estado(), pedido.total(), pedido.motivo(), historicoInferido, List.copyOf(etapas),
                itens.stream().map(item -> new ItemResumo(item.slug(), item.quantidade(), item.preco())).toList(),
                entrega(pedido));
    }

    private Entrega entrega(Pedido pedido) {
        var frete = pedido.freteContratado();
        String transportadora = pedido.transportadora() != null || frete == null
                ? pedido.transportadora() : frete.transportadora();
        return new Entrega(pedido.frete(), pedido.rastreio(), transportadora,
                frete == null ? null : frete.nome(), frete == null ? null : frete.provedor(),
                frete == null ? null : frete.previsaoEntrega());
    }

    private boolean vazio(String texto) { return texto == null || texto.isBlank(); }

    private record ItensCotados(UUID artesao, BigDecimal total, List<ItemPedido> itens) { }
}
