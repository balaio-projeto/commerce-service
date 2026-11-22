package br.com.balaio.commerce.cart.application;

import static br.com.balaio.commerce.cart.application.SacolaException.Tipo.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import br.com.balaio.commerce.cart.application.port.in.SacolaUseCase;
import br.com.balaio.commerce.cart.application.port.out.CotacaoSacola;
import br.com.balaio.commerce.cart.application.port.out.CotacaoSacola.Cotacao;
import br.com.balaio.commerce.cart.application.port.out.CotacaoSacola.ErroCotacao;
import br.com.balaio.commerce.cart.application.port.out.SacolaRepository;
import br.com.balaio.commerce.cart.domain.ItemSacola;
import org.springframework.stereotype.Service;

@Service
public class SacolaService implements SacolaUseCase {
    private final SacolaRepository sacolas;
    private final CotacaoSacola catalogo;

    public SacolaService(SacolaRepository sacolas, CotacaoSacola catalogo) {
        this.sacolas = sacolas;
        this.catalogo = catalogo;
    }

    @Override
    public List<ItemSacola> consultar(UUID comprador) {
        return sacolas.consultar(comprador);
    }

    @Override
    public Revalidacao revalidar(UUID comprador) {
        var resultado = new ArrayList<ItemRevalidado>();
        for (var item : sacolas.consultar(comprador)) {
            try {
                var cotacao = catalogo.cotarParaSacola(item.slug());
                if (!cotacaoValida(item, cotacao))
                    throw new SacolaException(CATALOGO_INDISPONIVEL, "Cotação inválida do catálogo.");
                resultado.add(item.quantidadeDisponivel(cotacao.disponibilidade())
                        ? new ItemRevalidado(item.slug(), item.quantidade(), cotacao.preco(),
                                cotacao.disponibilidade(), "disponivel")
                        : indisponivel(item));
            } catch (ErroCotacao erro) {
                switch (erro.falha()) {
                    case PECA_INDISPONIVEL -> resultado.add(indisponivel(item));
                    case RESPOSTA_INVALIDA ->
                        throw new SacolaException(CATALOGO_INDISPONIVEL, "Cotação inválida do catálogo.");
                    case CATALOGO_INDISPONIVEL -> throw catalogoIndisponivel();
                }
            }
        }
        boolean podeProsseguir = !resultado.isEmpty() && resultado.stream()
                .allMatch(item -> "disponivel".equals(item.situacao()));
        return new Revalidacao(List.copyOf(resultado), podeProsseguir, Instant.now());
    }

    @Override
    public List<ItemSacola> substituir(UUID comprador, List<ItemSacola> itens) {
        if (itens == null || itens.size() > 20)
            throw new SacolaException(REQUISICAO_INVALIDA, "Sacola inválida.");
        var slugs = new HashSet<String>();
        for (var item : itens) {
            if (item == null || !item.valido() || !slugs.add(item.slug()))
                throw new SacolaException(REQUISICAO_INVALIDA, "Item inválido ou duplicado.");
            try {
                var cotacao = catalogo.cotarParaSacola(item.slug());
                if (!cotacaoValida(item, cotacao) || !item.quantidadeDisponivel(cotacao.disponibilidade()))
                    throw new SacolaException(PECA_INDISPONIVEL, "Peça indisponível ou quantidade inválida.");
            } catch (ErroCotacao erro) {
                switch (erro.falha()) {
                    case PECA_INDISPONIVEL -> throw new SacolaException(PECA_INDISPONIVEL, "Peça indisponível.");
                    case RESPOSTA_INVALIDA -> throw erro;
                    case CATALOGO_INDISPONIVEL -> throw catalogoIndisponivel();
                }
            }
        }
        return sacolas.substituir(comprador, itens);
    }

    private boolean cotacaoValida(ItemSacola item, Cotacao cotacao) {
        return cotacao != null && item.slug().equals(cotacao.slug()) && cotacao.preco() != null
                && cotacao.preco().signum() >= 0 && ItemSacola.disponibilidadeValida(cotacao.disponibilidade());
    }

    private ItemRevalidado indisponivel(ItemSacola item) {
        return new ItemRevalidado(item.slug(), item.quantidade(), null, "indisponivel", "indisponivel");
    }

    private SacolaException catalogoIndisponivel() {
        return new SacolaException(CATALOGO_INDISPONIVEL, "Catálogo indisponível; tente novamente.");
    }
}
