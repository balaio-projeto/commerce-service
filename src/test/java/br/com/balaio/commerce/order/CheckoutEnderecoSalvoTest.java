package br.com.balaio.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.balaio.commerce.payment.CheckoutProService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CheckoutEnderecoSalvoTest {
    @Test
    void checkoutConsultaEnderecoDoCompradorEUsaSnapshotResolvido() {
        var pedidos = mock(PedidoService.class);
        var pagamentos = mock(CheckoutProService.class);
        var enderecos = mock(EnderecoSalvoClient.class);
        var controller = new PedidosController(pedidos, pagamentos, enderecos, "");
        UUID comprador = UUID.randomUUID();
        UUID enderecoId = UUID.randomUUID();
        UUID pedidoId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("jwt-comprador").header("alg", "none")
                .subject(comprador.toString()).claim("papel", "comprador")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var informado = Map.of("cep", "99999-999", "endereco", "Não confiável",
                "cidade", "Outra", "estado", "SP");
        var salvo = Map.of("cep", "50000-000", "endereco", "Rua A, 1, Boa Vista",
                "cidade", "Recife", "estado", "PE");
        when(enderecos.consultar(enderecoId, "jwt-comprador")).thenReturn(salvo);
        when(pedidos.checkoutSalvo(any(), eq(comprador), eq("chave"), any())).thenAnswer(chamada -> {
            @SuppressWarnings("unchecked")
            Supplier<Map<String, String>> consulta = chamada.getArgument(3);
            assertThat(consulta.get()).isEqualTo(salvo);
            return pedidoId;
        });
        var comando = new PedidoService.Checkout(
                List.of(new PedidoService.Item("vaso", 1)), "padrao", "checkout_pro", informado, enderecoId);

        controller.checkout(comando, "chave", jwt);

        verify(pedidos).checkoutSalvo(eq(comando), eq(comprador), eq("chave"), any());
        verify(pedidos, never()).checkout(any(), any(), any());
        verify(enderecos).consultar(enderecoId, "jwt-comprador");
    }
}
