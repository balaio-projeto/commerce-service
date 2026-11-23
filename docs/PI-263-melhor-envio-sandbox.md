# PI-212 / PI-263 · Frete em homologação com Melhor Envio Sandbox

## Provedores

O commerce-service cota frete pelo caso de uso `CotarFreteUseCase` (`br.com.balaio.commerce.shipping.application`), que consulta a porta de saída `ProvedorFrete`. O pedido usa a mesma cotação pela porta `FretePedido`. A implementação do provedor é escolhida na inicialização por `ProvedorFreteConfig` (`shipping.integration`):

| Configuração | Provedor | `provedor` nas respostas | Opções aceitas no checkout |
|---|---|---|---|
| `MELHORENVIO_TOKEN` vazio | `FreteFixoDemonstrativo` (R$ 38,90, 10 a 14 dias úteis) | `fixo_demonstrativo` | `padrao` |
| `MELHORENVIO_TOKEN` preenchido | `MelhorEnvioSandboxClient` | `melhor_envio_sandbox` | `me-<id do serviço>` |

O cliente recusa iniciar se `MELHORENVIO_BASE_URL` não apontar para `sandbox.melhorenvio.com.br` (só `localhost`/`127.0.0.1` são aceitos, para testes). Nenhuma chamada de produção é feita. Com token configurado, falha do Melhor Envio (401, timeout, resposta inválida) responde `503 FRETE_INDISPONIVEL`; não há troca silenciosa para o valor fixo.

## Variáveis

| Variável | Padrão | Uso |
|---|---|---|
| `MELHORENVIO_TOKEN` | vazio | Token Bearer do app sandbox. Fica fora do Git (`platform/.env`). |
| `MELHORENVIO_BASE_URL` | `https://sandbox.melhorenvio.com.br` | Base da API v2. |
| `MELHORENVIO_CEP_ORIGEM` | `50030230` | CEP de origem. Não existe contrato de endereço do ateliê no profile-service; por isso a origem é única e configurável. |
| `MELHORENVIO_USER_AGENT` | `Balaio homologacao` | O Melhor Envio pede `User-Agent` com nome da aplicação e e-mail de contato técnico. Exemplo: `Balaio (equipe@exemplo.com)`. |
| `MELHORENVIO_SERVICOS` | vazio (todos) | Lista de ids de serviço, por exemplo `1,2` (PAC e SEDEX). |
| `FRETE_PACOTE_ALTURA_CM`, `FRETE_PACOTE_LARGURA_CM`, `FRETE_PACOTE_COMPRIMENTO_CM`, `FRETE_PACOTE_PESO_KG` | 15, 20, 25, 1.0 | Dimensões e peso por unidade. O catálogo ainda não guarda medidas das peças. |

## Como obter o token sandbox

1. Crie uma conta em `https://sandbox.melhorenvio.com.br` (cadastro separado do ambiente de produção; não use dados reais de cobrança).
2. No painel do sandbox, abra **Configurações → Permissões de acesso → Tokens** e gere um token com os escopos `shipping-calculate` (cotação) e, se for testar etiquetas depois, `cart-write`, `shipping-checkout`, `shipping-generate` e `shipping-tracking`.
3. Copie o token para `MELHORENVIO_TOKEN` no `platform/.env` (ignorado pelo Git) e recompile/reinicie o `commerce-service`.
4. Saldo para compra de etiquetas no sandbox é fictício e pode ser adicionado pelo próprio painel do sandbox.

Referências: <https://docs.melhorenvio.com.br/docs/sandbox> e <https://docs.melhorenvio.com.br/reference/introducao-api-melhor-envio>.

## Contrato com o Melhor Envio

`POST {MELHORENVIO_BASE_URL}/api/v2/me/shipment/calculate`

Headers: `Authorization: Bearer <token>`, `Accept: application/json`, `Content-Type: application/json`, `User-Agent: <aplicação (contato)>`.

```json
{
  "from": { "postal_code": "50030230" },
  "to": { "postal_code": "01310100" },
  "products": [
    { "id": "jarra-azul", "width": 20, "height": 15, "length": 25, "weight": 1.0, "insurance_value": 80.00, "quantity": 2 }
  ],
  "options": { "receipt": false, "own_hand": false },
  "services": "1,2"
}
```

A resposta é uma lista de serviços. Cada item válido traz `id`, `name`, `price` (texto), `custom_price`, `delivery_time`, `delivery_range {min,max}`, `custom_delivery_*` e `company {id,name}`. Serviços com `error` (trecho não atendido, dimensões fora do limite) são descartados. O commerce usa `custom_price` quando existe, senão `price`, arredondado em 2 casas; o prazo vem de `custom_delivery_range`/`delivery_range` e cai para `delivery_time`.

## Contrato do Balaio

`GET /api/v1/fretes?cep=<CEP>&itens=<slug>:<quantidade>,...` (público pelo gateway; o contrato antigo sem parâmetros continua respondendo a opção fixa de referência).

```json
{ "dados": [
  { "id": "me-1", "nome": "Correios PAC", "prazo": "5 a 7 dias úteis", "prazoDiasMinimo": 5, "prazoDiasMaximo": 7,
    "valor": 21.50, "transportadora": "Correios", "provedor": "melhor_envio_sandbox" }
], "erro": null }
```

| Situação | Resposta |
|---|---|
| CEP sem 8 dígitos ou repetido (`00000000`) | 400 `CEP_INVALIDO` |
| Itens fora do formato `slug:1..3`, repetidos ou mais de 20 | 400 `REQUISICAO_INVALIDA` |
| Peça inexistente ou indisponível no catálogo | 409 `PECA_INDISPONIVEL` |
| Melhor Envio fora do ar, token recusado ou resposta inválida | 503 `FRETE_INDISPONIVEL` |
| Nenhum serviço atende | 200 com `dados: []` |

O seguro (`insurance_value`) usa o preço atual da cotação interna do catálogo (`/internal/v1/pecas/{slug}/cotacao`).

### Checkout e snapshot

`POST /api/v1/checkout` continua recebendo `freteId`. O commerce recota no servidor com o CEP do endereço (digitado ou salvo) e os itens já cotados no catálogo; se o `freteId` não estiver mais entre as opções, responde 409 `FRETE_INDISPONIVEL` e o front recota. O valor entra em `pedidos.frete` e no total calculado no servidor; a opção escolhida fica em `pedidos.frete_cotacao` (JSONB) com `id`, `nome`, `prazo`, `prazoDiasMinimo/Maximo`, `valor`, `transportadora`, `provedor`, `cepDestino` e `cotadoEm`. O pedido expõe `freteOpcao`, `freteProvedor` e `previsaoEntrega` a partir desse snapshot; mudanças posteriores no Melhor Envio não alteram o pedido.

## Rastreio

Ao marcar `enviado`, o artesão informa o código de rastreio (5 a 40 letras, números ou hífens) e, opcionalmente, a transportadora. O pedido expõe `rastreio`, `transportadora` e `linkRastreio` (`https://www.melhorrastreio.com.br/rastreio/<código>`); a etapa "Pedido enviado" da linha do tempo registra o código.

## Limitações conhecidas

- A contratação da etiqueta (carrinho → compra → geração → impressão) **não** é feita automaticamente após o pagamento. No sandbox a etiqueta é fictícia e não gera rastreio real; por isso o código é informado manualmente pelo artesão. A contratação idempotente fica como evolução do PI-212.
- O CEP de origem é único por ambiente, e as dimensões são padrão por unidade, até existirem contratos de endereço do ateliê e de medidas das peças.
- O sandbox tem transportadoras limitadas e valores de teste; não representa custo de produção.
