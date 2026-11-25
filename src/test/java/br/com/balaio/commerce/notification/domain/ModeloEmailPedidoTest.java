package br.com.balaio.commerce.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class ModeloEmailPedidoTest {
    private static final UUID PEDIDO = UUID.fromString("abcdef12-0000-0000-0000-000000000000");
    private static final ModeloEmailPedido.Pedido DADOS =
            new ModeloEmailPedido.Pedido(new BigDecimal("150.50"), "ME123BR", "Correios", null);

    @ParameterizedTest
    @CsvSource({
            "pagamento_confirmado, comprador, Pagamento confirmado, /orders/",
            "pagamento_confirmado, artesao, Novo pedido pago, /dashboard",
            "falha_pagamento, comprador, Pagamento não aprovado, /orders/",
            "producao, comprador, Pedido em produção, /orders/",
            "enviado, comprador, Pedido enviado, /orders/",
            "entregue, comprador, Pedido entregue, /orders/",
            "recusado, comprador, Pedido recusado, /orders/",
            "cancelado, comprador, Pedido cancelado, /orders/",
            "cancelado, artesao, Pedido cancelado, /dashboard",
            "reembolsado, comprador, Reembolso concluído, /orders/"
    })
    void cadaEventoTemAssuntoAvisoELinkProprios(String evento, String destinatario, String titulo, String link) {
        var conteudo = ModeloEmailPedido.compor(new EnvioEmail(PEDIDO, evento, destinatario), "Ana Maria", DADOS);
        assertThat(conteudo.assunto()).startsWith("Balaio · ").contains("ABCDEF12");
        assertThat(conteudo.aviso().titulo()).isEqualTo(titulo);
        assertThat(conteudo.aviso().link()).startsWith(link);
        String html = conteudo.email().html("https://balaio.example");
        assertThat(html).contains("Olá, Ana.", "ABCDEF12", "https://balaio.example" + link);
    }

    @Test
    void recusaMostraMotivoEscapadoEEnvioTrazRastreio() {
        var recusa = ModeloEmailPedido.compor(new EnvioEmail(PEDIDO, "recusado", "comprador"), "Ana",
                new ModeloEmailPedido.Pedido(BigDecimal.TEN, null, null, "Sem <argila> no estoque"));
        assertThat(recusa.email().html("https://balaio.example")).contains("Motivo informado: Sem &lt;argila&gt; no estoque");
        assertThat(recusa.aviso().mensagem()).contains("Motivo: Sem <argila> no estoque");

        var envio = ModeloEmailPedido.compor(new EnvioEmail(PEDIDO, "enviado", "comprador"), "Ana", DADOS);
        assertThat(envio.email().texto("https://balaio.example")).contains("Código de rastreio: ME123BR", "Transportadora: Correios");
        assertThat(envio.aviso().mensagem()).contains("ME123BR");
    }

    @Test
    void entregaLevaParaAvaliacaoEEventoDesconhecidoFalha() {
        var entrega = ModeloEmailPedido.compor(new EnvioEmail(PEDIDO, "entregue", "comprador"), "", DADOS);
        assertThat(entrega.aviso().link()).isEqualTo("/orders/" + PEDIDO + "/review");
        assertThat(entrega.email().html("https://balaio.example")).contains("Olá.", "Avaliar compra");
        assertThatThrownBy(() -> ModeloEmailPedido.compor(new EnvioEmail(PEDIDO, "enviado", "artesao"), "Ana", DADOS))
                .isInstanceOf(IllegalStateException.class);
    }
}
