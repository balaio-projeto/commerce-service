package br.com.balaio.commerce.notification.application;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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
    private final boolean paralelo;
    private final int concorrencia;

    public EmailPedidoService(FilaEmailsPedido fila, RemetenteEmail remetente, ContatoConta contatos,
            @Value("${balaio.mail.frontend-url:http://localhost:4321}") String frontend,
            @Value("${balaio.mail.paralelo:false}") boolean paralelo,
            @Value("${balaio.mail.concorrencia:8}") int concorrencia) {
        this.fila = fila;
        this.remetente = remetente;
        this.contatos = contatos;
        this.frontend = frontend.endsWith("/") ? frontend.substring(0, frontend.length() - 1) : frontend;
        this.paralelo = paralelo;
        this.concorrencia = Math.max(1, concorrencia);
    }

    @Override
    public int processarPendentes() {
        return processarLote().enviados();
    }

    @Override
    public int drenarPendentes(int maximoLotes) {
        int enviados = 0;
        for (int i = 0; i < maximoLotes; i++) {
            var resultado = processarLote();
            enviados += resultado.enviados();
            if (resultado.reservados() < TAMANHO_LOTE) {
                break;
            }
        }
        return enviados;
    }

    private record ResultadoLote(int reservados, int enviados) { }

    private ResultadoLote processarLote() {
        var lote = fila.reservarLote(TAMANHO_LOTE);
        int enviados = paralelo ? enviarEmParalelo(lote) : enviarEmSequencia(lote);
        return new ResultadoLote(lote.size(), enviados);
    }

    private int enviarEmSequencia(List<EnvioEmail> lote) {
        int enviados = 0;
        for (var envio : lote) {
            if (enviarItem(envio)) {
                enviados++;
            }
        }
        return enviados;
    }

    private int enviarEmParalelo(List<EnvioEmail> lote) {
        var permissoes = new Semaphore(concorrencia);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tarefas = lote.stream()
                    .map(envio -> CompletableFuture.supplyAsync(() -> {
                        permissoes.acquireUninterruptibly();
                        try {
                            return enviarItem(envio) ? 1 : 0;
                        } finally {
                            permissoes.release();
                        }
                    }, executor))
                    .toList();
            return tarefas.stream().mapToInt(CompletableFuture::join).sum();
        }
    }

    /** Envia um item e atualiza a fila. Falhas são registradas aqui para não derrubar o lote. */
    private boolean enviarItem(EnvioEmail envio) {
        try {
            enviar(envio);
            fila.marcarEnviado(envio);
            return true;
        } catch (RuntimeException falha) {
            log.warn("Falha ao enviar e-mail {} do pedido {} para {}: {}", envio.evento(), envio.pedido(),
                    envio.destinatario(), falha.getClass().getSimpleName());
            fila.registrarFalha(envio, falha.getClass().getSimpleName(), MAXIMO_TENTATIVAS);
            return false;
        }
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
