package br.com.balaio.commerce.promotion.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.balaio.commerce.promotion.application.port.in.AdministrarCuponsUseCase.CupomAdmin;
import br.com.balaio.commerce.promotion.domain.Cupom;

public record CupomResponse(UUID id, String codigo, boolean automatico, String descricao, String tipo, BigDecimal valor,
        BigDecimal descontoMaximo, BigDecimal pedidoMinimo, OffsetDateTime inicio, OffsetDateTime fim,
        Integer limiteUsos, Integer limitePorComprador, int usos, boolean ativo, String situacao,
        String custeadoPor, OffsetDateTime criadoEm, OffsetDateTime atualizadoEm) {

    public static CupomResponse de(CupomAdmin admin) {
        var cupom = admin.cupom();
        return new CupomResponse(cupom.id(), cupom.codigo(), cupom.automatico(), cupom.descricao(), cupom.tipo().codigo(),
                cupom.valor(), cupom.descontoMaximo(), cupom.pedidoMinimo(), cupom.inicio(), cupom.fim(),
                cupom.limiteUsos(), cupom.limitePorComprador(), cupom.usos(), cupom.ativo(), admin.situacao().codigo(),
                Cupom.CUSTEADO_PELA_PLATAFORMA, cupom.criadoEm(), cupom.atualizadoEm());
    }
}
