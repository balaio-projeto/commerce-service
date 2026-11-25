package br.com.balaio.commerce.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import br.com.balaio.commerce.notification.application.port.in.EmailsPedidoUseCase.SituacaoEmail;
import br.com.balaio.commerce.notification.application.port.out.ContatoConta;
import br.com.balaio.commerce.notification.application.port.out.FilaEmailsPedido;
import br.com.balaio.commerce.notification.application.port.out.RemetenteEmail;
import br.com.balaio.commerce.notification.domain.EnvioEmail;

class EmailPedidoServiceTest {

    private static final UUID COMPRADOR = UUID.randomUUID();
    private static final UUID ARTESAO = UUID.randomUUID();
    private static final UUID CONTA_FALHA = UUID.randomUUID();

    /** Fila em memória: reserva marca o item como reservado até marcar sucesso ou falha. */
    static class FilaMemoria implements FilaEmailsPedido {
        final Map<String, String> estados = new ConcurrentHashMap<>();
        final List<EnvioEmail> itens = new ArrayList<>();
        final Map<String, EnvioEmail> porChave = new HashMap<>();
        volatile UUID pedidoQueFalha;

        synchronized void adicionar(EnvioEmail envio) {
            itens.add(envio);
            porChave.put(chave(envio), envio);
            estados.put(chave(envio), "pendente");
        }

        @Override
        public synchronized List<EnvioEmail> reservarLote(int limite) {
            var lote = new ArrayList<EnvioEmail>();
            for (var envio : itens) {
                if (lote.size() == limite) break;
                if ("pendente".equals(estados.get(chave(envio)))) {
                    estados.put(chave(envio), "reservado");
                    lote.add(envio);
                }
            }
            return lote;
        }

        @Override
        public synchronized void marcarEnviado(EnvioEmail envio) {
            estados.put(chave(envio), "enviado");
        }

        @Override
        public synchronized void registrarFalha(EnvioEmail envio, String erro, int maximoTentativas) {
            estados.put(chave(envio), "falhou_temporario");
        }

        @Override
        public List<SituacaoEmail> situacao(UUID pedido) {
            return List.of();
        }

        @Override
        public int reagendarFalhas(UUID pedido) {
            return 0;
        }

        @Override
        public DadosPedido dadosPedido(UUID pedido) {
            var comprador = pedido.equals(pedidoQueFalha) ? CONTA_FALHA : COMPRADOR;
            return new DadosPedido(comprador, ARTESAO, BigDecimal.TEN, "BR123", "Correios");
        }

        Map<String, String> estadosFinais() {
            return new HashMap<>(estados);
        }

        static String chave(EnvioEmail e) {
            return e.pedido() + "|" + e.evento() + "|" + e.destinatario();
        }
    }

    /** Remetente que falha para destinatários marcados e mede a concorrência máxima. */
    static class RemetenteFake implements RemetenteEmail {
        final Set<String> assuntosQueFalham;
        final AtomicInteger emVoo = new AtomicInteger();
        final AtomicInteger pico = new AtomicInteger();
        final AtomicInteger total = new AtomicInteger();

        RemetenteFake(Set<String> assuntosQueFalham) {
            this.assuntosQueFalham = assuntosQueFalham;
        }

        @Override
        public void enviar(String destinatario, String assunto, String texto) {
            int atual = emVoo.incrementAndGet();
            pico.accumulateAndGet(atual, Math::max);
            try {
                Thread.sleep(5);
                total.incrementAndGet();
                if (destinatario.startsWith("falha")) {
                    throw new IllegalStateException("SMTP indisponível");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                emVoo.decrementAndGet();
            }
        }
    }

    private static ContatoConta contatos() {
        return conta -> new ContatoConta.Contato("Nome",
                conta.equals(CONTA_FALHA) ? "falha@exemplo.com" : "destino@exemplo.com");
    }

    private static FilaMemoria filaComEmails(int quantidade) {
        var fila = new FilaMemoria();
        for (int i = 0; i < quantidade; i++) {
            var pedido = UUID.randomUUID();
            fila.adicionar(new EnvioEmail(pedido, "pagamento_confirmado", "comprador"));
        }
        return fila;
    }

    private static EmailPedidoService servico(FilaMemoria fila, RemetenteFake remetente, boolean paralelo, int concorrencia) {
        return new EmailPedidoService(fila, remetente, contatos(), "http://localhost:4321", paralelo, concorrencia);
    }

    @Test
    void modoSequencialEParaleloChegamAoMesmoEstadoFinal() {
        var filaSeq = filaComEmails(45);
        var filaPar = filaComEmails(0);
        // mesmos e-mails nas duas filas, com os mesmos pedidos
        for (var envio : filaSeq.itens) {
            filaPar.adicionar(envio);
        }

        int enviadosSeq = servico(filaSeq, new RemetenteFake(Set.of()), false, 1).drenarPendentes(10);
        int enviadosPar = servico(filaPar, new RemetenteFake(Set.of()), true, 8).drenarPendentes(10);

        assertThat(enviadosPar).isEqualTo(enviadosSeq).isEqualTo(45);
        assertThat(filaPar.estadosFinais()).isEqualTo(filaSeq.estadosFinais());
        assertThat(filaPar.estadosFinais().values()).containsOnly("enviado");
    }

    @Test
    void falhaDeUmItemNaoDerrubaOLoteNoModoParalelo() {
        var fila = new FilaMemoria();
        var pedidoFalha = UUID.randomUUID();
        fila.pedidoQueFalha = pedidoFalha;
        fila.adicionar(new EnvioEmail(pedidoFalha, "pagamento_confirmado", "comprador"));
        for (int i = 0; i < 19; i++) {
            fila.adicionar(new EnvioEmail(UUID.randomUUID(), "pagamento_confirmado", "comprador"));
        }

        int enviados = servico(fila, new RemetenteFake(Set.of()), true, 4).processarPendentes();

        assertThat(enviados).isEqualTo(19);
        var estados = fila.estadosFinais().values();
        assertThat(estados).filteredOn("enviado"::equals).hasSize(19);
        assertThat(estados).filteredOn("falhou_temporario"::equals).hasSize(1);
    }

    @Test
    void drenarParaQuandoOLoteVemIncompleto() {
        var fila = filaComEmails(25);
        var remetente = new RemetenteFake(Set.of());

        int enviados = servico(fila, remetente, false, 1).drenarPendentes(50);

        assertThat(enviados).isEqualTo(25);
        assertThat(remetente.total.get()).isEqualTo(25);
    }

    @Test
    void drenarRespeitaOLimiteDeLotesPorCiclo() {
        var fila = filaComEmails(100);

        int enviados = servico(fila, new RemetenteFake(Set.of()), false, 1).drenarPendentes(2);

        // dois lotes de 20 = 40 enviados; o restante fica para o próximo ciclo
        assertThat(enviados).isEqualTo(40);
        assertThat(fila.estadosFinais().values()).filteredOn("enviado"::equals).hasSize(40);
    }

    @Test
    void concorrenciaNaoPassaDoLimite() {
        var fila = filaComEmails(20);
        var remetente = new RemetenteFake(Set.of());

        servico(fila, remetente, true, 3).processarPendentes();

        assertThat(remetente.pico.get()).isLessThanOrEqualTo(3);
    }
}
