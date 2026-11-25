package br.com.balaio.commerce.promotion.application.port.out;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.balaio.commerce.promotion.domain.Cupom;
import br.com.balaio.commerce.promotion.domain.DadosCupom;

public interface CupomRepository {
    List<Cupom> listar();
    Optional<Cupom> buscar(UUID id);
    Optional<Cupom> buscarPorCodigo(String codigo);
    Optional<Cupom> travar(UUID id);
    List<Cupom> automaticosVigentes(OffsetDateTime agora);
    int usosDoComprador(UUID cupom, UUID comprador);
    boolean codigoEmUso(String codigo, UUID excetoCupom);
    void criar(UUID id, DadosCupom dados, UUID administrador);
    void atualizar(UUID id, DadosCupom dados);
    void definirAtivo(UUID id, boolean ativo);
    void registrarUso(UUID cupom, UUID pedido, UUID comprador, BigDecimal desconto);
}
