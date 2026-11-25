package br.com.balaio.commerce.order.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface PerfisCompradores {
    Map<UUID, PerfilComprador> consultar(Collection<UUID> compradores);

    record PerfilComprador(String nome, String imagem) {
        public static final PerfilComprador DESCONHECIDO = new PerfilComprador(null, null);
    }
}
