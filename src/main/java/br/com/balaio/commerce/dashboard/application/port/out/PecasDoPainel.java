package br.com.balaio.commerce.dashboard.application.port.out;

import java.util.List;
import java.util.UUID;

public interface PecasDoPainel {
    List<PecaPainel> listar(UUID artesao, String tokenArtesao);

    record PecaPainel(String slug, String nome, String estadoVenda) { }
}
