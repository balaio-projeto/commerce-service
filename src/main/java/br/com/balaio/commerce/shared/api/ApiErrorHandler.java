package br.com.balaio.commerce.shared.api;

import br.com.balaio.commerce.order.application.PedidoException;

import java.util.Map;
import java.util.LinkedHashMap;

import br.com.balaio.commerce.cart.application.SacolaException;
import br.com.balaio.commerce.order.application.port.out.CatalogoPedidos.ErroCatalogo;
import br.com.balaio.commerce.payment.api.WebhookInvalido;
import br.com.balaio.commerce.payment.application.PagamentoIndisponivel;
import br.com.balaio.commerce.shared.security.AcessoNegado;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(SacolaException.class)
    public ResponseEntity<Map<String, Object>> sacola(SacolaException erro) {
        int status = switch (erro.tipo()) {
            case REQUISICAO_INVALIDA -> 400;
            case PECA_INDISPONIVEL -> 409;
            case CATALOGO_INDISPONIVEL -> 503;
        };
        return resposta(status, erro.tipo().name(), erro.getMessage());
    }

    @ExceptionHandler(PedidoException.class)
    public ResponseEntity<Map<String, Object>> regra(PedidoException erro) {
        int status = switch (erro.tipo()) {
            case REQUISICAO_INVALIDA -> 400;
            case SEM_PERMISSAO -> 403;
            case PEDIDO_NAO_ENCONTRADO, ENDERECO_NAO_ENCONTRADO -> 404;
            case PEDIDO_CONFLITANTE, CHECKOUT_CONFLITANTE, MEDIACAO_NECESSARIA,
                    MEDIACAO_CONFLITANTE, RESERVA_CONFLITANTE -> 409;
            case ENDERECO_INCOMPLETO -> 422;
            case PERFIL_INDISPONIVEL -> 503;
        };
        return resposta(status, erro.codigo(), erro.getMessage());
    }

    @ExceptionHandler(AcessoNegado.class)
    public ResponseEntity<Map<String, Object>> acesso(AcessoNegado erro) {
        return resposta(403, "SEM_PERMISSAO", erro.getMessage());
    }

    @ExceptionHandler(PagamentoIndisponivel.class)
    public ResponseEntity<Map<String, Object>> pagamento(PagamentoIndisponivel erro) {
        return resposta(503, "PAGAMENTO_INDISPONIVEL", erro.getMessage());
    }

    @ExceptionHandler(WebhookInvalido.class)
    public ResponseEntity<Map<String, Object>> webhook(WebhookInvalido erro) {
        return resposta(erro.assinaturaInvalida() ? 401 : 400, "WEBHOOK_INVALIDO", erro.getMessage());
    }

    @ExceptionHandler(ErroCatalogo.class)
    public ResponseEntity<Map<String, Object>> catalogo(ErroCatalogo erro) {
        return switch (erro.falha()) {
            case CONFLITO, RESERVA_AUSENTE -> indisponivel();
            case INDISPONIVEL -> integracao();
            case RESPOSTA_INVALIDA -> throw erro;
        };
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, Object>> entrada() {
        return resposta(400, "REQUISICAO_INVALIDA", "Requisição inválida.");
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> integracao() {
        return resposta(503, "CATALOGO_INDISPONIVEL", "Catálogo indisponível; tente novamente.");
    }

    @ExceptionHandler(HttpClientErrorException.Conflict.class)
    public ResponseEntity<Map<String, Object>> indisponivel() {
        return resposta(409, "PECA_INDISPONIVEL", "Peça indisponível.");
    }

    private ResponseEntity<Map<String, Object>> resposta(int status, String codigo, String mensagem) {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("dados", null);
        corpo.put("erro", Map.of("codigo", codigo, "mensagem", mensagem));
        return ResponseEntity.status(status).body(corpo);
    }
}
