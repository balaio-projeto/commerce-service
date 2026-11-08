package br.com.balaio.commerce.order;

import java.util.Map;
import java.util.LinkedHashMap;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;

@RestControllerAdvice
public class PedidoErrorHandler {
    @ExceptionHandler(PedidoException.class)
    public ResponseEntity<Map<String, Object>> regra(PedidoException erro) {
        return resposta(erro.status(), erro.codigo(), erro.getMessage());
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
