package br.com.balaio.commerce.order.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.balaio.commerce.order.application.PedidoException;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Checkout;
import br.com.balaio.commerce.order.application.port.in.CheckoutPedidoUseCase.Item;
import br.com.balaio.commerce.order.application.port.out.FingerprintCheckout;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class FingerprintCheckoutSha256 implements FingerprintCheckout {
    private final ObjectMapper json;

    public FingerprintCheckoutSha256(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String calcular(Checkout comando) {
        try {
            Object identidade = comando.enderecoId() == null
                    ? new FingerprintEnderecoManual(comando.itens(), comando.freteId(), comando.meio(), comando.endereco())
                    : new FingerprintEnderecoSalvo(comando.itens(), comando.freteId(), comando.meio(), comando.enderecoId());
            byte[] dados = json.writeValueAsBytes(identidade);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new PedidoException(PedidoException.Tipo.REQUISICAO_INVALIDA, "Checkout inválido.");
        }
    }

    // A ordem e os nomes dos componentes fazem parte do fingerprint dos pedidos já gravados.
    private record FingerprintEnderecoManual(List<Item> itens, String freteId, String meio,
            Map<String, String> endereco) { }
    private record FingerprintEnderecoSalvo(List<Item> itens, String freteId, String meio, UUID enderecoId) { }
}
