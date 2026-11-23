package br.com.balaio.commerce.shipping.application.port.out;

import java.util.List;

import br.com.balaio.commerce.shipping.domain.OpcaoFrete;
import br.com.balaio.commerce.shipping.domain.VolumeFrete;

public interface ProvedorFrete {
    String provedor();

    boolean reconheceOpcao(String freteId);

    List<OpcaoFrete> cotar(String cepDestino, List<VolumeFrete> volumes);
}
