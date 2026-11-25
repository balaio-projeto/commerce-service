# PI-226 — Descontos da plataforma, retirada e entrega local

## Quem paga o desconto

- Todo cupom ou promoção é **custeado pela plataforma** (`custeadoPor = "plataforma"` no snapshot do pedido e nas respostas).
- O repasse do artesão é calculado sobre o **valor cheio**: `pedido_itens.preco × quantidade` e `pedidos.frete` não mudam com o cupom. O painel de vendas do artesão já soma esses campos; a lista de pedidos pendentes mostra `total + desconto_produtos + desconto_frete`.
- O comprador paga ao Mercado Pago `pedidos.total`, que já tem o desconto. Quando há desconto nas peças, a preferência leva uma linha única `pecas-com-desconto-balaio` (soma das peças menos o desconto); a linha de entrega leva `frete - desconto_frete` e é omitida quando zera. A soma das linhas é igual a `pedidos.total`, e o webhook continua recusando pagamento com valor diferente do total.

## Modelo (`V8__cupons_da_plataforma.sql`)

- `cupons`: `codigo` (opcional; sem código = promoção automática), `tipo` (`percentual`, `valor_fixo`, `frete_gratis`), `valor`, `desconto_maximo` (teto opcional), `pedido_minimo` (sobre o subtotal das peças), `inicio`/`fim`, `limite_usos`, `limite_por_comprador`, `usos`, `ativo`, `descricao`.
- `pedidos`: `cupom_id`, `cupom_codigo`, `cupom_snapshot` (regras do cupom no momento da compra), `desconto_produtos`, `desconto_frete`.
- `cupom_usos`: um uso por pedido (`pedido_id` é a chave), com o comprador e o desconto aplicado.

## Regras

- Percentual: `subtotal × valor / 100`, arredondado para centavos com meio para cima, limitado ao teto e ao subtotal.
- Valor fixo: `min(valor, subtotal)`. Frete grátis: `min(frete, teto)`; nas peças não muda nada.
- O pedido nunca fica abaixo de `R$ 1,00` (`TotaisPedido.MINIMO_COBRADO`).
- Com código, só aquele cupom vale; sem código, entra a promoção automática de maior desconto (empate: menor id). Não acumulam.
- Validação sempre no servidor: na prévia (`POST /api/v1/checkout/cupom`) e de novo no checkout, dentro da transação, com `SELECT ... FOR UPDATE` no cupom, conferindo situação, limite total e limite por comprador antes de gravar o uso e somar `usos`. A constraint `usos <= limite_usos` é a última barreira.
- A repetição do checkout com a mesma `Idempotency-Key` devolve o pedido existente sem consumir outro uso.
- Pedido que termina em `cancelado`, `recusado`, `reembolsado`, `falha_reserva` ou `falha_pagamento` devolve o uso (gatilho `trg_liberar_cupom_do_pedido`).

## Endpoints

| Método | Caminho | Acesso |
| --- | --- | --- |
| GET/POST | `/api/v1/admin/cupons` | ADMIN |
| GET/PUT | `/api/v1/admin/cupons/{id}` | ADMIN |
| PATCH | `/api/v1/admin/cupons/{id}/situacao` (`{"ativo": false}`) | ADMIN |
| POST | `/api/v1/checkout/cupom` (`{"itens": [...], "cupom": "CODIGO", "frete": 38.90}`) | comprador |
| POST | `/api/v1/checkout` aceita `"cupom": "CODIGO"` | comprador |

Recusas voltam com HTTP 422 e códigos `CUPOM_INVALIDO`, `CUPOM_INATIVO`, `CUPOM_AINDA_NAO_VALIDO`, `CUPOM_EXPIRADO`, `CUPOM_ESGOTADO`, `CUPOM_LIMITE_COMPRADOR`, `CUPOM_PEDIDO_MINIMO`, `CUPOM_SEM_DESCONTO`, `PROMOCAO_ENCERRADA`. Erros de cadastro voltam com HTTP 400, `CUPOM_INVALIDO` e `erro.campos`.

## Retirada e entrega local

- O profile guarda `aceita_retirada`, `aceita_entrega_local`, `retirada_bairro` e `retirada_cidade` (`V17` do profile) e os devolve em `GET /internal/v1/contas/{id}/origem-frete`.
- `GET /api/v1/fretes` mantém as transportadoras e acrescenta, ao final:
  - `entrega-local` quando o artesão aceita e os `frete.local.prefixo-cep-digitos` primeiros dígitos do CEP (padrão 5) batem com o CEP de origem; valor `frete.local.valor-entrega` (padrão R$ 0,00).
  - `retirada-atelie` (R$ 0,00) quando o artesão aceita retirada; o texto mostra só bairro e cidade.
- As duas opções usam o provedor `balaio_local` e a modalidade (`retirada` ou `entrega_local`). Nesses pedidos o artesão pode marcar "enviado" sem código de rastreio.
- Propriedades: `FRETE_RETIRADA_ATIVA`, `FRETE_ENTREGA_LOCAL_ATIVA`, `FRETE_ENTREGA_LOCAL_PREFIXO_CEP`, `FRETE_ENTREGA_LOCAL_VALOR`.
