package br.com.balaio.commerce.shipping.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.balaio.commerce.shipping.application.port.in.CotarFreteUseCase;
import br.com.balaio.commerce.shipping.application.port.out.OrigemFreteAtelie;
import br.com.balaio.commerce.shipping.application.port.out.PrecoPecasFrete;
import br.com.balaio.commerce.shipping.application.port.out.PrecoPecasFrete.PecaFrete;
import br.com.balaio.commerce.shipping.application.port.out.ProvedorFrete;
import br.com.balaio.commerce.shipping.domain.Cep;
import br.com.balaio.commerce.shipping.domain.FreteDemonstrativo;
import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;

@Service
public class FreteService implements CotarFreteUseCase {
    private static final String ORIGEM_ATELIE = "atelie";
    private static final String ORIGEM_PADRAO = "padrao";

    private final ProvedorFrete provedor;
    private final PrecoPecasFrete pecas;
    private final OrigemFreteAtelie origens;

    public FreteService(ProvedorFrete provedor, PrecoPecasFrete pecas, OrigemFreteAtelie origens) {
        this.provedor = provedor;
        this.pecas = pecas;
        this.origens = origens;
    }

    @Override
    public String provedor() {
        return provedor.provedor();
    }

    @Override
    public boolean reconheceOpcao(String freteId) {
        return provedor.reconheceOpcao(freteId);
    }

    @Override
    public List<OpcaoFrete> cotar(String cep, List<ItemCotacao> itens) {
        if (cep == null || cep.isBlank()) return List.of(FreteDemonstrativo.opcao());
        String destino = cepNormalizado(cep);
        if (itens == null || itens.isEmpty())
            return provedor.cotar(null, destino, List.of(new VolumeFrete("pacote", 1, null)));
        var consultadas = new ArrayList<PecaFrete>();
        var volumes = new ArrayList<VolumeFrete>();
        for (var item : itens) {
            var peca = pecas.consultar(item.slug());
            consultadas.add(peca);
            volumes.add(new VolumeFrete(item.slug(), item.quantidade(), peca.preco()));
        }
        return provedor.cotar(cepOrigem(atelieUnico(consultadas)).orElse(null), destino, volumes);
    }

    @Override
    public Escolha escolher(String freteId, String cep, UUID artesao, List<VolumeFrete> volumes) {
        String destino = cepNormalizado(cep);
        Optional<String> origem = cepOrigem(artesao);
        var opcao = provedor.cotar(origem.orElse(null), destino, volumes).stream().filter(item -> item.id().equals(freteId))
                .findFirst().orElseThrow(() -> new FreteException(FreteException.Tipo.OPCAO_INDISPONIVEL,
                        "A opção de frete escolhida não está mais disponível; cote novamente."));
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", opcao.id());
        snapshot.put("nome", opcao.nome());
        snapshot.put("prazo", opcao.prazo());
        snapshot.put("prazoDiasMinimo", opcao.prazoDiasMinimo());
        snapshot.put("prazoDiasMaximo", opcao.prazoDiasMaximo());
        snapshot.put("valor", opcao.valor());
        snapshot.put("transportadora", opcao.transportadora());
        snapshot.put("provedor", opcao.provedor());
        snapshot.put("cepDestino", destino);
        snapshot.put("origemFrete", origem.isPresent() ? ORIGEM_ATELIE : ORIGEM_PADRAO);
        snapshot.put("cotadoEm", OffsetDateTime.now().toString());
        return new Escolha(opcao, snapshot);
    }

    private UUID atelieUnico(List<PecaFrete> consultadas) {
        var ateliesDistintos = consultadas.stream().map(PecaFrete::artesao).filter(Objects::nonNull).distinct().toList();
        if (ateliesDistintos.size() > 1)
            throw new FreteException(FreteException.Tipo.ATELIES_DIFERENTES,
                    "Cote o frete separadamente para as peças de cada ateliê.");
        return ateliesDistintos.isEmpty() ? null : ateliesDistintos.getFirst();
    }

    private Optional<String> cepOrigem(UUID artesao) {
        return artesao == null ? Optional.empty() : origens.cepOrigem(artesao);
    }

    private String cepNormalizado(String cep) {
        return Cep.normalizado(cep).orElseThrow(() -> new FreteException(FreteException.Tipo.CEP_INVALIDO,
                "Informe um CEP válido com 8 dígitos."));
    }
}
