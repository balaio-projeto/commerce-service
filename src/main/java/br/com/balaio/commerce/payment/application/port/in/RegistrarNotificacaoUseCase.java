package br.com.balaio.commerce.payment.application.port.in;

public interface RegistrarNotificacaoUseCase {
    void exigirDisponivel();
    void registrarNotificacao(String requestId, String pagamentoId);
}
