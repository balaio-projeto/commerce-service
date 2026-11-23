package br.com.balaio.commerce.order.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class RegrasCheckout {
    private static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE",
            "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI",
            "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private static final List<String> CAMPOS_ENDERECO = List.of("cep", "endereco", "cidade", "estado");
    private static final Pattern CEP = Pattern.compile("[0-9]{5}-?[0-9]{3}");

    private RegrasCheckout() { }

    public static boolean chaveValida(UUID comprador, String chave) {
        return comprador != null && !vazio(chave) && chave.length() <= 160;
    }

    public static boolean comandoValido(List<?> itens, boolean freteReconhecido, String meio, Map<String, String> endereco) {
        return itens != null && !itens.isEmpty() && itens.size() <= 20 && freteReconhecido
                && "checkout_pro".equals(meio) && enderecoValido(endereco);
    }

    public static boolean itemValido(String slug, Integer quantidade) {
        return !vazio(slug) && quantidade != null && quantidade >= 1 && quantidade <= 3;
    }

    public static boolean cotacaoCompativel(UUID proprietario, UUID artesao, String disponibilidade, int quantidade) {
        return proprietario != null && (artesao == null || artesao.equals(proprietario))
                && (!"unica".equals(disponibilidade) || quantidade == 1);
    }

    private static boolean enderecoValido(Map<String, String> endereco) {
        return endereco != null && CAMPOS_ENDERECO.stream().noneMatch(campo -> vazio(endereco.get(campo)))
                && CEP.matcher(endereco.get("cep")).matches() && UFS.contains(endereco.get("estado"))
                && endereco.get("endereco").length() <= 200 && endereco.get("cidade").length() <= 100;
    }

    private static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }
}
