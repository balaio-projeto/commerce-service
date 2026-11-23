package br.com.balaio.commerce.shipping.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import br.com.balaio.commerce.shipping.application.port.in.CotarFreteUseCase;
import br.com.balaio.commerce.shipping.application.port.out.PrecoPecasFrete;
import br.com.balaio.commerce.shipping.application.port.out.ProvedorFrete;
import br.com.balaio.commerce.shipping.domain.Cep;
import br.com.balaio.commerce.shipping.domain.FreteDemonstrativo;
import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;

@Service
public class FreteService implements CotarFreteUseCase {
    private final ProvedorFrete provedor;
    private final PrecoPecasFrete pecas;

    public FreteService(ProvedorFrete provedor, PrecoPecasFrete pecas) {
        this.provedor = provedor;
        this.pecas = pecas;
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
        return provedor.cotar(destino, volumes(itens));
    }

    @Override
    public Escolha escolher(String freteId, String cep, List<VolumeFrete> volumes) {
        String destino = cepNormalizado(cep);
        var opcao = provedor.cotar(destino, volumes).stream().filter(item -> item.id().equals(freteId))
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
        snapshot.put("cotadoEm", OffsetDateTime.now().toString());
        return new Escolha(opcao, snapshot);
    }

    private List<VolumeFrete> volumes(List<ItemCotacao> itens) {
        if (itens == null || itens.isEmpty()) return List.of(new VolumeFrete("pacote", 1, null));
        return itens.stream().map(item -> new VolumeFrete(item.slug(), item.quantidade(),
                pecas.precoAtual(item.slug()))).toList();
    }

    private String cepNormalizado(String cep) {
        return Cep.normalizado(cep).orElseThrow(() -> new FreteException(FreteException.Tipo.CEP_INVALIDO,
                "Informe um CEP válido com 8 dígitos."));
    }
}
