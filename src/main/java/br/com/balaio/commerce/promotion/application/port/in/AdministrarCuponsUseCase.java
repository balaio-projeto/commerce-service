package br.com.balaio.commerce.promotion.application.port.in;

import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.promotion.domain.Cupom;
import br.com.balaio.commerce.promotion.domain.DadosCupom;
import br.com.balaio.commerce.promotion.domain.SituacaoCupom;

public interface AdministrarCuponsUseCase {
    List<CupomAdmin> listar();
    CupomAdmin consultar(UUID id);
    CupomAdmin criar(DadosCupom dados, UUID administrador);
    CupomAdmin editar(UUID id, DadosCupom dados);
    CupomAdmin definirAtivo(UUID id, boolean ativo);

    record CupomAdmin(Cupom cupom, SituacaoCupom situacao) { }
}
