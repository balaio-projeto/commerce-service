package br.com.balaio.commerce.promotion.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.commerce.promotion.application.port.in.AdministrarCuponsUseCase;
import br.com.balaio.commerce.promotion.application.port.in.AplicarCupomUseCase;
import br.com.balaio.commerce.promotion.application.port.out.CupomRepository;
import br.com.balaio.commerce.promotion.domain.Cupom;
import br.com.balaio.commerce.promotion.domain.DadosCupom;
import br.com.balaio.commerce.promotion.domain.DescontoCalculado;
import br.com.balaio.commerce.promotion.domain.RecusaCupom;

@Service
public class CupomService implements AdministrarCuponsUseCase, AplicarCupomUseCase {
    private final CupomRepository repositorio;
    private final Clock relogio;

    @Autowired
    public CupomService(CupomRepository repositorio) {
        this(repositorio, Clock.systemDefaultZone());
    }

    public CupomService(CupomRepository repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CupomAdmin> listar() {
        OffsetDateTime agora = agora();
        return repositorio.listar().stream().map(cupom -> new CupomAdmin(cupom, cupom.situacao(agora))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CupomAdmin consultar(UUID id) {
        return paraAdmin(repositorio.buscar(id).orElseThrow(CupomNaoEncontrado::new));
    }

    @Override
    @Transactional
    public CupomAdmin criar(DadosCupom dados, UUID administrador) {
        var normalizado = validar(dados, null, 0);
        UUID id = UUID.randomUUID();
        repositorio.criar(id, normalizado, administrador);
        return consultar(id);
    }

    @Override
    @Transactional
    public CupomAdmin editar(UUID id, DadosCupom dados) {
        var atual = repositorio.travar(id).orElseThrow(CupomNaoEncontrado::new);
        repositorio.atualizar(id, validar(dados, id, atual.usos()));
        return consultar(id);
    }

    @Override
    @Transactional
    public CupomAdmin definirAtivo(UUID id, boolean ativo) {
        repositorio.travar(id).orElseThrow(CupomNaoEncontrado::new);
        repositorio.definirAtivo(id, ativo);
        return consultar(id);
    }

    private DadosCupom validar(DadosCupom dados, UUID cupomAtual, int usosRegistrados) {
        if (dados == null) throw new CupomInvalido(Map.of("descricao", "Preencha os dados do cupom."));
        var normalizado = dados.normalizado();
        var problemas = new LinkedHashMap<>(normalizado.problemas(usosRegistrados));
        if (normalizado.codigo() != null && !problemas.containsKey("codigo")
                && repositorio.codigoEmUso(normalizado.codigo(), cupomAtual))
            problemas.put("codigo", "Já existe um cupom com este código.");
        if (!problemas.isEmpty()) throw new CupomInvalido(problemas);
        return normalizado;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Aplicacao> avaliar(String codigo, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete) {
        Optional<String> informado = DadosCupom.codigoNormalizado(codigo);
        if (informado.isEmpty()) return melhorAutomatica(comprador, subtotalProdutos, frete);
        if (!DadosCupom.FORMATO_CODIGO.matcher(informado.get()).matches()) throw new CupomRecusado(RecusaCupom.INVALIDO);
        Cupom cupom = repositorio.buscarPorCodigo(informado.get())
                .orElseThrow(() -> new CupomRecusado(RecusaCupom.INVALIDO));
        cupom.recusa(agora(), subtotalProdutos, repositorio.usosDoComprador(cupom.id(), comprador))
                .ifPresent(recusa -> { throw new CupomRecusado(recusa); });
        var desconto = cupom.calcular(subtotalProdutos, freteConhecido(frete));
        if (desconto.vazio() && frete != null) throw new CupomRecusado(RecusaCupom.SEM_DESCONTO);
        return Optional.of(aplicacao(cupom, desconto));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Aplicacao reservar(Aplicacao prevista, UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete) {
        Cupom cupom = repositorio.travar(prevista.cupomId()).orElseThrow(() -> recusaNaReserva(prevista, null));
        cupom.recusa(agora(), subtotalProdutos, repositorio.usosDoComprador(cupom.id(), comprador))
                .ifPresent(recusa -> { throw recusaNaReserva(prevista, recusa); });
        var desconto = cupom.calcular(subtotalProdutos, freteConhecido(frete));
        if (desconto.vazio())
            throw new CupomRecusado(prevista.automatico() ? RecusaCupom.PROMOCAO_ENCERRADA : RecusaCupom.SEM_DESCONTO);
        return aplicacao(cupom, desconto);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarUso(Aplicacao aplicacao, UUID pedido, UUID comprador, BigDecimal descontoAplicado) {
        repositorio.registrarUso(aplicacao.cupomId(), pedido, comprador, descontoAplicado);
    }

    private Optional<Aplicacao> melhorAutomatica(UUID comprador, BigDecimal subtotalProdutos, BigDecimal frete) {
        OffsetDateTime agora = agora();
        BigDecimal freteConhecido = freteConhecido(frete);
        return repositorio.automaticosVigentes(agora).stream()
                .filter(cupom -> cupom.recusa(agora, subtotalProdutos,
                        repositorio.usosDoComprador(cupom.id(), comprador)).isEmpty())
                .map(cupom -> aplicacao(cupom, cupom.calcular(subtotalProdutos, freteConhecido)))
                .filter(aplicacao -> !aplicacao.desconto().vazio())
                .min(Comparator.comparing((Aplicacao aplicacao) -> aplicacao.desconto().total()).reversed()
                        .thenComparing(aplicacao -> aplicacao.cupomId().toString()));
    }

    private CupomRecusado recusaNaReserva(Aplicacao prevista, Cupom.Recusa recusa) {
        if (prevista.automatico() || recusa == null) return new CupomRecusado(prevista.automatico()
                ? RecusaCupom.PROMOCAO_ENCERRADA : RecusaCupom.INVALIDO);
        return new CupomRecusado(recusa);
    }

    private Aplicacao aplicacao(Cupom cupom, DescontoCalculado desconto) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", cupom.id().toString());
        snapshot.put("codigo", cupom.codigo());
        snapshot.put("descricao", cupom.descricao());
        snapshot.put("tipo", cupom.tipo().codigo());
        snapshot.put("valor", cupom.valor());
        snapshot.put("descontoMaximo", cupom.descontoMaximo());
        snapshot.put("pedidoMinimo", cupom.pedidoMinimo());
        snapshot.put("automatico", cupom.automatico());
        snapshot.put("custeadoPor", Cupom.CUSTEADO_PELA_PLATAFORMA);
        return new Aplicacao(cupom.id(), cupom.codigo(), cupom.descricao(), cupom.tipo(), cupom.automatico(),
                desconto, snapshot);
    }

    private CupomAdmin paraAdmin(Cupom cupom) {
        return new CupomAdmin(cupom, cupom.situacao(agora()));
    }

    private BigDecimal freteConhecido(BigDecimal frete) {
        return frete == null ? BigDecimal.ZERO : frete;
    }

    private OffsetDateTime agora() {
        return OffsetDateTime.now(relogio);
    }
}
