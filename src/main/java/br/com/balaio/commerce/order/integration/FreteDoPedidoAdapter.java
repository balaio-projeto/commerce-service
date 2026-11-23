package br.com.balaio.commerce.order.integration;

import java.util.List;

import br.com.balaio.commerce.order.application.port.out.FretePedido;
import br.com.balaio.commerce.shipping.application.port.in.CotarFreteUseCase;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;
import org.springframework.stereotype.Component;

@Component
public class FreteDoPedidoAdapter implements FretePedido {
    private final CotarFreteUseCase fretes;

    public FreteDoPedidoAdapter(CotarFreteUseCase fretes) {
        this.fretes = fretes;
    }

    @Override
    public boolean reconhece(String freteId) {
        return fretes.reconheceOpcao(freteId);
    }

    @Override
    public FreteEscolhido escolher(String freteId, String cep, List<ItemFrete> itens) {
        var volumes = itens.stream()
                .map(item -> new VolumeFrete(item.slug(), item.quantidade(), item.valorUnitario())).toList();
        var escolha = fretes.escolher(freteId, cep, volumes);
        return new FreteEscolhido(escolha.opcao().id(), escolha.opcao().valor(), escolha.snapshot());
    }
}
