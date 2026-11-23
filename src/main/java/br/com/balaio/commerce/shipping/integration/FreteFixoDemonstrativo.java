package br.com.balaio.commerce.shipping.integration;

import java.util.List;

import br.com.balaio.commerce.shipping.application.port.out.ProvedorFrete;
import br.com.balaio.commerce.shipping.domain.FreteDemonstrativo;
import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;

public class FreteFixoDemonstrativo implements ProvedorFrete {
    @Override
    public String provedor() {
        return FreteDemonstrativo.PROVEDOR;
    }

    @Override
    public boolean reconheceOpcao(String freteId) {
        return FreteDemonstrativo.ID.equals(freteId);
    }

    @Override
    public List<OpcaoFrete> cotar(String cepDestino, List<VolumeFrete> volumes) {
        return List.of(FreteDemonstrativo.opcao());
    }
}
