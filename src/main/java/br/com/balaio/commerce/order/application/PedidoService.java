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
import br.com.balaio.commerce.order.domain.TotaisPedido;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos.ErroCatalogo;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos.Falha;
import br.com.balaio.commerce.order.application.port.out.CuponsDoPedido;
import br.com.balaio.commerce.order.application.port.out.EnderecoSalvo;
import br.com.balaio.commerce.order.application.port.out.FretePedido;
import br.com.balaio.commerce.order.application.port.out.PedidoRepository.FreteDoCheckout;

@Service
public class PedidoService implements CheckoutPedidoUseCase, ConsultarPedidosUseCase,
        ComandosPedidoUseCase, ProcessarPedidosPendentesUseCase {
    private static final int MAXIMO_ITENS_DA_PREVIA = 20;
    private static final BigDecimal FRETE_MAXIMO_DA_PREVIA = new BigDecimal("10000");
    private static final String CUSTEADO_PELA_PLATAFORMA = "plataforma";
    private final PedidoRepository repositorio;
    private final CatalogoPedidos catalogo;
    private final EnderecoSalvo enderecos;
    private final TransactionTemplate transacoes;
    private final FingerprintCheckout fingerprints;
    private final FretePedido fretes;
    private final CuponsDoPedido cupons;

    public PedidoService(PedidoRepository repositorio, CatalogoPedidos catalogo, EnderecoSalvo enderecos,
            TransactionTemplate transacoes, FingerprintCheckout fingerprints, FretePedido fretes, CuponsDoPedido cupons) {
        this.repositorio = repositorio;
        this.catalogo = catalogo;
        this.enderecos = enderecos;
        this.transacoes = transacoes;
        this.fingerprints = fingerprints;
        this.fretes = fretes;
        this.cupons = cupons;
    }

    @Override
    public UUID checkout(Checkout comando, UUID comprador, String chave) {
        validarChave(comprador, chave);
        if (comando == null) throw comandoInvalido();
        String fingerprint = fingerprints.calcular(comando);
        UUID existente = transacoes.execute(status -> buscarCheckoutExistente(comprador, chave, fingerprint));
        if (existente != null) return existente;
        validarComando(comando);
        ItensCotados cotacao = cotarItens(comando.itens(), comprador);
        return persistir(comando, comprador, chave, fingerprint, cotacao, escolherFrete(comando, cotacao));
    }

    @Override
    public Optional<PreviaCupom> simularCupom(List<Item> itens, String cupom, UUID comprador, BigDecimal frete) {
        if (comprador == null || itens == null || itens.isEmpty() || itens.size() > MAXIMO_ITENS_DA_PREVIA
                || (frete != null && (frete.signum() < 0 || frete.compareTo(FRETE_MAXIMO_DA_PREVIA) > 0)))
            throw new PedidoException(Tipo.REQUISICAO_INVALIDA, "Confira os itens e o frete informados.");
        ItensCotados cotacao = cotarItens(itens, comprador);
        BigDecimal freteConsiderado = frete == null ? BigDecimal.ZERO : frete;
        return cupons.avaliar(cupom, comprador, cotacao.total(), frete).map(previsto -> {
            var descontos = TotaisPedido.limitar(cotacao.total(), freteConsiderado, previsto.descontoProdutos(),
                    previsto.descontoFrete());
            return new PreviaCupom(previsto.codigo(), previsto.descricao(), previsto.tipo(), previsto.automatico(),
                    cotacao.total(), descontos.produtos(), descontos.frete(), CUSTEADO_PELA_PLATAFORMA);
        });
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
                enderecos.consultar(comando.enderecoId(), tokenComprador), comando.enderecoId(), comando.cupom());
        validarComando(resolvido);
        ItensCotados cotacao = cotarItens(resolvido.itens(), comprador);
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

    private ItensCotados cotarItens(List<Item> itens, UUID comprador) {
        var slugs = new HashSet<String>();
        var cotados = new ArrayList<ItemPedido>();
        UUID artesao = null;
        BigDecimal total = BigDecimal.ZERO;
        for (var item : itens) {
            validarItem(item, slugs);
            var cotacao = catalogo.cotar(item.slug());
            if (RegrasCheckout.pecaDoProprioAtelie(comprador, cotacao.proprietarioId()))
                throw new PedidoException(Tipo.PECA_DO_PROPRIO_ATELIE, RegrasCheckout.MENSAGEM_PECA_DO_PROPRIO_ATELIE);
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
        var escolhido = fretes.escolher(comando.freteId(), comando.endereco().get("cep"), cotacao.artesao(), itens);
        return new FreteDoCheckout(escolhido.id(), escolhido.valor(), escolhido.snapshot());
    }

    private UUID persistir(Checkout comando, UUID comprador, String chave, String fingerprint, ItensCotados cotacao,
            FreteDoCheckout frete) {
        var cupomPrevisto = cupons.avaliar(comando.cupom(), comprador, cotacao.total(), frete.valor());
        return transacoes.execute(status -> {
            UUID jaCriado = buscarCheckoutExistente(comprador, chave, fingerprint);
            if (jaCriado != null) return jaCriado;
            UUID id = UUID.randomUUID();
            var cupom = cupomPrevisto.map(previsto -> cupons.reservar(previsto, comprador, cotacao.total(), frete.valor()));
            var descontos = cupom.map(aplicado -> TotaisPedido.limitar(cotacao.total(), frete.valor(),
                    aplicado.descontoProdutos(), aplicado.descontoFrete())).orElse(TotaisPedido.Descontos.NENHUM);
            repositorio.criarCheckout(id, comprador, cotacao.artesao(), chave, fingerprint, comando.endereco(),
                    frete, TotaisPedido.total(cotacao.total(), frete.valor(), descontos), comando.meio(), cotacao.itens());
            cupom.filter(aplicado -> !descontos.vazio()).ifPresent(aplicado -> {
                repositorio.registrarDesconto(id, aplicado.id(), aplicado.codigo(), aplicado.snapshot(),
                        descontos.produtos(), descontos.frete());
                cupons.registrarUso(aplicado, id, comprador, descontos.total());
            });
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
                        pendente.valor(), pendente.compradorId())).toList();
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
        var pedido = obter(id, true);
        boolean entregaCombinada = pedido.freteContratado() != null && pedido.freteContratado().entregaCombinada();
        String codigo = !envio ? null : entregaCombinada && vazio(rastreio) ? null : rastreioValido(rastreio);
        String empresa = envio ? transportadoraValida(transportadora) : null;
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
        BigDecimal subtotal = BigDecimal.ZERO;
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
            subtotal = subtotal.add(preco.multiply(BigDecimal.valueOf(item.quantidade())));
        }
        repositorio.concluirReserva(pedido.id(), totalAposReserva(pedido, subtotal));
    }

    private BigDecimal totalAposReserva(Pedido pedido, BigDecimal subtotal) {
        var registrado = repositorio.descontoDoPedido(pedido.id());
        if (registrado.isEmpty()) return TotaisPedido.total(subtotal, pedido.frete(), TotaisPedido.Descontos.NENHUM);
        var anterior = new TotaisPedido.Descontos(registrado.get().produtos(), registrado.get().frete());
        var limitado = TotaisPedido.limitar(subtotal, pedido.frete(), anterior.produtos(), anterior.frete());
        if (!limitado.mesmoValor(anterior)) repositorio.ajustarDesconto(pedido.id(), limitado.produtos(), limitado.frete());
        return TotaisPedido.total(subtotal, pedido.frete(), limitado);
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
                entrega(pedido), desconto(pedido));
    }

    private DescontoResumo desconto(Pedido pedido) {
        return repositorio.descontoDoPedido(pedido.id()).map(registrado -> new DescontoResumo(registrado.cupomCodigo(),
                registrado.descricao(), registrado.automatico(), registrado.produtos(), registrado.frete(),
                registrado.custeadoPor() == null ? CUSTEADO_PELA_PLATAFORMA : registrado.custeadoPor())).orElse(null);
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
