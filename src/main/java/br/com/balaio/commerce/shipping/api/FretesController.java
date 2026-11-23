package br.com.balaio.commerce.shipping.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shipping.application.FreteException;
import br.com.balaio.commerce.shipping.application.port.in.CotarFreteUseCase;
import br.com.balaio.commerce.shipping.application.port.in.CotarFreteUseCase.ItemCotacao;

@RestController
public class FretesController {
    private static final Pattern ITEM = Pattern.compile("([a-z0-9][a-z0-9-]{0,159}):([1-3])");
    private static final int MAXIMO_ITENS = 20;
    private final CotarFreteUseCase fretes;

    public FretesController(CotarFreteUseCase fretes) {
        this.fretes = fretes;
    }

    @GetMapping("/api/v1/fretes")
    public Envelope cotar(@RequestParam(required = false) String cep,
            @RequestParam(required = false) String itens) {
        boolean semCep = cep == null || cep.isBlank();
        return new Envelope(fretes.cotar(cep, semCep ? List.of() : itens(itens)), null);
    }

    private List<ItemCotacao> itens(String itens) {
        if (itens == null || itens.isBlank()) return List.of();
        var partes = itens.split(",");
        if (partes.length > MAXIMO_ITENS) throw itensInvalidos();
        var vistos = new HashSet<String>();
        var lista = new ArrayList<ItemCotacao>();
        for (String parte : partes) {
            var item = ITEM.matcher(parte.trim());
            if (!item.matches() || !vistos.add(item.group(1))) throw itensInvalidos();
            lista.add(new ItemCotacao(item.group(1), Integer.parseInt(item.group(2))));
        }
        return lista;
    }

    private FreteException itensInvalidos() {
        return new FreteException(FreteException.Tipo.ITENS_INVALIDOS, "Informe os itens como slug:quantidade.");
    }
}
