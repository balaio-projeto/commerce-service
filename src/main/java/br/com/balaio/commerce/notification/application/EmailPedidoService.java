package br.com.balaio.commerce.notification.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.com.balaio.commerce.notification.application.port.in.EmailsPedidoUseCase;
import br.com.balaio.commerce.notification.application.port.out.ContatoConta;
import br.com.balaio.commerce.notification.application.port.out.FilaEmailsPedido;
import br.com.balaio.commerce.notification.application.port.out.RemetenteEmail;
import br.com.balaio.commerce.notification.domain.EnvioEmail;
import br.com.balaio.commerce.notification.domain.ModeloEmailPedido;

@Service
public class EmailPedidoService implements EmailsPedidoUseCase {
    public static final int MAXIMO_TENTATIVAS = 8;
    private static final int TAMANHO_LOTE = 20;
    private static final Logger log = LoggerFactory.getLogger(EmailPedidoService.class);
    private final FilaEmailsPedido fila;
    private final RemetenteEmail remetente;
    private final ContatoConta contatos;
    private final String frontend;

    public EmailPedidoService(FilaEmailsPedido fila, RemetenteEmail remetente, ContatoConta contatos,
            @Value("${balaio.mail.frontend-url:http://localhost:4321}") String frontend) {
        this.fila = fila;
        this.remetente = remetente;
        this.contatos = contatos;
        this.frontend = frontend.endsWith("/") ? frontend.substring(0, frontend.length() - 1) : frontend;
    }

    @Override
    public int processarPendentes() {
        int enviados = 0;
        for (var envio : fila.reservarLote(TAMANHO_LOTE)) {
            try {
                enviar(envio);
                fila.marcarEnviado(envio);
                enviados++;
            } catch (RuntimeException falha) {
                log.warn("Falha ao enviar e-mail {} do pedido {} para {}: {}", envio.evento(), envio.pedido(),
                        envio.destinatario(), falha.getClass().getSimpleName());
                fila.registrarFalha(envio, falha.getClass().getSimpleName(), MAXIMO_TENTATIVAS);
            }
        }
        return enviados;
    }

    @Override
    public List<SituacaoEmail> situacao(UUID pedido) {
        return fila.situacao(pedido);
    }

    @Override
    public int reenviarFalhas(UUID pedido) {
        return fila.reagendarFalhas(pedido);
    }

    private void enviar(EnvioEmail envio) {
        var pedido = fila.dadosPedido(envio.pedido());
        var contato = contatos.consultar(envio.paraComprador() ? pedido.comprador() : pedido.artesao());
        var conteudo = ModeloEmailPedido.compor(envio, contato.nome(), pedido.total(), pedido.rastreio(),
                pedido.transportadora(), frontend);
        remetente.enviar(contato.email(), conteudo.assunto(), conteudo.texto());
    }
}
