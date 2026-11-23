package br.com.balaio.commerce.shipping.application.port.in;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;

public interface CotarFreteUseCase {
    String provedor();
    boolean reconheceOpcao(String freteId);
    List<OpcaoFrete> cotar(String cep, List<ItemCotacao> itens);
    Escolha escolher(String freteId, String cep, UUID artesao, List<VolumeFrete> volumes);

    record ItemCotacao(String slug, int quantidade) { }
    record Escolha(OpcaoFrete opcao, Map<String, Object> snapshot) { }
}
