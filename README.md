# commerce-service

Sacola, pedidos, pagamento Checkout Pro em sandbox e frete demonstrativo. O serviço é independente, usa Java 25 e Spring Boot 4.1.1 e recebe requisições externas por meio do api-gateway.

## Stack

- Spring MVC, JDBC e PostgreSQL
- Flyway para migrações do banco próprio
- Bean Validation
- Spring Boot Actuator

## Arquitetura

- `cart` cuida da sacola, `order` cuida do pedido e da reserva, e `payment` cuida das preferências, notificações e conciliação. `checkout/application` compõe as portas de pedido e pagamento nos fluxos que precisam dos dois contextos.
- `shipping` cota o frete (fixo demonstrativo ou Melhor Envio Sandbox) e o pedido consome essa cotação pela porta `FretePedido`. `dashboard` monta o relatório de vendas e o resumo do painel do artesão. `notification` mantém a fila de e-mails de pedido e o worker de envio por SMTP.
- Controllers e workers chamam portas de entrada. A aplicação usa portas de saída para persistência e serviços remotos. Regras puras ficam no domínio, SQL fica nos adapters JDBC e os clientes HTTP traduzem os contratos de catálogo, perfil, Mercado Pago e Melhor Envio.
- A sacola consulta o catálogo antes da transação de substituição. A conciliação valida o pagamento no provedor e depois abre uma transação local para travar pedido e intenção, nesta ordem, e gravar o resultado financeiro e a transição. O trigger do PostgreSQL grava o histórico nessa mesma transação.

## Execução

Porta HTTP padrão: 8083. Para executar pela IDE, defina `COMMERCE_SERVICE_PORT` (opcional), `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. O banco e suas credenciais são configurados no repositório `platform`.

No Windows, inicie com `.\mvnw.cmd spring-boot:run` e rode `.\mvnw.cmd test` para a suíte sem Docker.

Rode a suíte completa com Java 25, Docker e PostgreSQL 18 via Testcontainers.

```powershell
$env:COMMERCE_POSTGRES_TEST='true'
.\mvnw.cmd -B clean verify
```

No Linux, use `COMMERCE_POSTGRES_TEST=true ./mvnw -B clean verify`.

O `Dockerfile` constrói uma imagem própria. Em `platform`, use `docker compose up --build -d commerce-service` para iniciar este serviço e seu banco ou `docker compose --profile extended up --build -d` para iniciar todos. Health: `http://localhost:8083/actuator/health`.

A especificação OpenAPI do serviço fica em `http://localhost:8083/v3/api-docs`.

## PI-189: endereço salvo no checkout

`POST /api/v1/checkout` aceita `enderecoId` como alternativa ao endereço digitado. Quando o campo vem preenchido, o commerce consulta `GET /api/v1/usuario/enderecos/{id}` no profile-service com o JWT do comprador e grava um snapshot dos dados retornados no pedido. Os campos `endereco` enviados junto com o id são ignorados.

A consulta acontece depois da checagem de idempotência. Repetir um checkout já criado devolve o mesmo pedido sem consultar o profile, então o pedido não depende de o endereço salvo continuar existindo. Editar ou remover o endereço depois da compra não altera o snapshot.

| Situação | Resposta |
|---|---|
| Endereço de outro comprador ou inexistente | 404 `ENDERECO_NAO_ENCONTRADO` |
| Endereço sem cidade e estado no campo `bairro` | 422 `ENDERECO_INCOMPLETO` |
| Profile-service indisponível ou com erro | 503 `PERFIL_INDISPONIVEL` |

Variável: `PROFILE_SERVICE_URL` (padrão `http://localhost:8082`). O commerce não lê o banco do profile.

## PI-187: sacola do comprador

`GET /api/v1/estado/sacola` devolve `{dados:[{slug,quantidade}],erro:null}` para o comprador autenticado; sem itens, `dados` é `[]`. `PUT /api/v1/estado/sacola` recebe `{sacola:[{slug,quantidade}]}` e substitui atomicamente as linhas da conta do `sub` no JWT. Não aceita identidade nem preço no corpo. Quantidades válidas são 1 a 3, e peça única aceita somente 1. O commerce valida slug, preço atual e disponibilidade pela cotação HTTP interna do catálogo. A sacola não cria reserva nem congela preço; o checkout faz nova cotação antes de reservar.

## PI-188: revalidação antes do checkout

`POST /api/v1/sacola/revalidar` exige JWT de comprador e lê apenas os itens persistidos do `sub`; o corpo é ignorado. A resposta 200 segue o envelope `{dados,erro}`. Exemplo: `{ "dados": { "itens": [{ "slug": "peca-exemplo", "quantidade": 1, "precoAtual": 42.50, "disponibilidade": "disponivel", "situacao": "disponivel" }], "podeProsseguir": true, "revalidadoEm": "2026-11-17T00:00:00Z" }, "erro": null }`. `precoAtual` é o preço final por unidade já descontado na cotação do catálogo. Item ausente ou em conflito é retornado como `situacao:"indisponivel"`, `disponibilidade:"indisponivel"`, `precoAtual:null` e impede prosseguir; os demais itens continuam visíveis. Sacola vazia devolve `itens:[]` e `podeProsseguir:false`.

Falha de rede, timeout ou resposta malformada em qualquer cotação devolve 503 com `{dados:null,erro:{codigo:"CATALOGO_INDISPONIVEL",...}}`; não há sucesso parcial. Uma peça ausente (404) ou em conflito (409) continua como indisponibilidade do item. A operação não grava preço, não altera a sacola e não cria reserva. A resposta é uma fotografia; o checkout consulta novamente e aplica a reserva atômica da PI-190. Para reproduzir localmente, iniciar o Compose com `docker compose --profile extended up --build -d --wait` em `platform`, autenticar um comprador, adicionar peças à sacola e chamar o POST com `Authorization: Bearer <JWT de teste>`. Repetir após alterar preço ou inativar uma peça no catálogo; interromper o catálogo para verificar o 503. Não usar credenciais de produção.

Medição local em 17/11/2026: Compose `extended` com PostgreSQL 18 e catálogo local, sacola com duas peças publicadas, 30 chamadas sequenciais HTTP diretas a `localhost:8083` após aquecimento. Tempo observado do cliente ao serviço: média 28,87 ms, p95 49,63 ms, mínimo 19,67 ms e máximo 59,90 ms. Como meta de regressão **nesse ambiente** para duas peças, usar p95 até 150 ms; a medição inclui loopback/cliente e não é um SLA de produção. Com o catálogo parado, a resposta foi 503, `dados:null`, em 2.071,54 ms, distinguindo falha da latência normal. Os timeouts globais não foram alterados.

## PI-54: Checkout Pro em sandbox

`POST /api/v1/checkout` exige JWT de comprador e `Idempotency-Key`. Aceita `itens: [{slug, quantidade}]`, `freteId` (`"padrao"` no frete demonstrativo ou `me-<id>` com o Melhor Envio Sandbox, ver PI-263), `meio: "checkout_pro"` e `endereco` com `cep`, `endereco`, `cidade` e `estado`. A resposta 202 mantém `{dados,erro}`. O campo `checkoutUrl` fica nulo até a preferência estar pronta e só tem valor enquanto o pedido aguarda pagamento. O commerce calcula o preço dos itens, o frete (R$ 38,90 no demonstrativo ou a cotação refeita no provedor) e o total. O catálogo fornece a cotação e reserva peças únicas por API interna. Encomendas não usam reserva exclusiva.

O commerce persiste o pedido antes de reservar e cria uma intenção local antes de chamar `POST /checkout/preferences`. A mesma chave e payload retornam o mesmo pedido e URL; payload divergente recebe 409. A cotação e os comandos remotos de reserva ocorrem fora da transação local; um lease no pedido impede workers simultâneos e expira após falha do processo. Depois de um timeout ambíguo, a intenção fica pendente e o worker consulta `GET /checkout/preferences/search` pela `external_reference` antes de vincular a preferência encontrada. Se a busca não encontrar resultado, ele **não repete o POST** automaticamente, para impedir segunda cobrança. Esse caso exige conciliação operacional. A preferência expira após 13 minutos; depois de 16 minutos sem pagamento na busca autenticada, o commerce inicia a liberação da reserva. A reserva do catálogo expira em 15 minutos.

`POST /api/v1/pagamentos/mercado-pago/notificacoes` recebe somente notificações `payment` com HMAC válida. A notificação é gravada antes do HTTP 200; o worker consulta `GET /v1/payments/{id}` e `GET /merchant_orders/{id}` com o token de teste e verifica referência, preferência, valor, moeda e `live_mode=false`. O antigo endpoint interno de pagamento simulado foi removido. Redirect, query string e retorno do navegador não mudam o estado financeiro. Status `approved` inicia confirmação da reserva; `pending`, `in_process`, `authorized`, `in_mediation` e estados desconhecidos não encerram o pedido; `rejected`, `cancelled` e `expired` iniciam liberação. Cobrança aprovada após encerramento inicia reembolso pendente, sem marcar venda concluída.

`PATCH /api/v1/pedidos/{id}/cancelar` exige o comprador e `/recusar` exige o artesão titular. Cancelamento após início da produção retorna 409 `MEDIACAO_NECESSARIA`. Os endpoints retornam 202 enquanto aguardam cancelamento, reembolso ou liberação. Cancelamento de pagamento pendente usa `PUT /v1/payments/{id}` com `status=cancelled` quando há payment ID. O commerce pode iniciar a liberação assim que confirma a ausência de intenção sob lock. Quando a intenção está pronta e ainda não tem cobrança vinculada, o cancelamento exige uma busca autenticada vazia e 14 minutos desde a atualização do pedido. O commerce confere novamente o estado, o prazo e a cobrança vinculada sob lock antes de avançar. Intenções `CRIANDO` passam pela recuperação por `external_reference` antes da liberação.

Pedido aprovado passa por `reembolso_pendente` e usa `POST /v1/payments/{id}/refunds` com chave idempotente. Somente `refunded` ou valor integral confirmado pelo provedor permite avançar para `encerramento_pendente`. A liberação da reserva pelo catálogo precisa retornar `LIBERADA` ou `EXPIRADA` antes do estado terminal. A causa permanece `cancelado` ou `recusado` após o estorno, e a decisão `cancelar_reembolsar` da mediação mantém sua própria causa persistida. A migration V5 conserva o valor reembolsado informado pelo provedor para que `estadoPagamento` indique `refunded` também quando o status bruto ainda é `approved` após estorno integral. Leituras posteriores antigas (`approved` com valor zero ou `pending`) não reduzem o valor reembolsado nem reabrem pedidos já estornados. Aprovação tardia sem estorno anterior segue para `reembolso_pendente`. Repetir um encerramento igual devolve o estado atual, e conflito de causa retorna 409. Falha de rede mantém estado pendente e é reprocessada, inclusive por item único já parcialmente liberado. O sandbox pode recusar cancelamento ou reembolso conforme o método e estado do pagamento; o serviço não promete conclusão antecipada. Não há retenção nem repasse ao artesão nesta integração.

`GET /api/v1/pedidos` lista somente os pedidos do comprador do JWT. Para o ateliê, `GET /api/v1/artesao/pedidos-pendentes` lista apenas pedidos `confirmado` com `id`, `pecaSlug`, `comprador`, `quando` e `valor`; `GET /api/v1/artesao/vendas` retorna pedidos pagos ainda ativos para as métricas e vendas. Ambas exigem JWT de artesão e filtram pelo `sub` validado, ignorando qualquer `artesao` informado na query. O nome do comprador aparece como rótulo genérico até existir contrato de consulta de perfil para este dado.

`PATCH /api/v1/pedidos/{id}/estado` exige o artesão titular no JWT e aceita a sequência `confirmado → producao → enviado → entregue` (PI-43). Para `enviado`, o corpo é `{ "estado": "enviado", "rastreio": "BR123456789BR", "transportadora": "Correios" }`; o rastreio é obrigatório (5 a 40 letras, números ou hífens, normalizado em maiúsculas) e a transportadora é opcional. Estado desconhecido ou rastreio ausente/inválido retornam 400; transição fora da ordem, repetida ou em pedido com encerramento em andamento retorna 409 `PEDIDO_CONFLITANTE`; outro artesão recebe 403. Cada mudança cria uma etapa na linha do tempo (a de `enviado` traz o código na nota) e o pedido passa a expor `rastreio`, `transportadora` e `linkRastreio`. A resposta é `{dados:<pedido atualizado>,erro:null}`; o comprador vê o mesmo estado persistido em `GET /api/v1/pedidos/{id}`. O vínculo com o artesão é obtido de `proprietarioId` na cotação HTTP do catálogo durante o checkout e armazenado em `pedidos.artesao_id`.

O detalhe do comprador inclui os itens (slug, quantidade e preço cotado), endereço do checkout e `etapas` ordenadas. A migration V4 registra a etapa inicial e cada mudança de `estado` na mesma transação da escrita, inclusive as realizadas pelo pagamento; operações que não mudam o estado não criam etapa. Ela marca os pedidos anteriores à V4 com histórico incompleto de forma persistente: `historicoInferido: true` continua presente após novas transições, e nenhuma data é inventada para etapas anteriores. A listagem e os contratos do artesão não expõem o endereço. `GET /internal/v1/pedidos/{id}/participantes` usa o token interno existente e retorna somente `compradorId` e `artesaoId` para autorização de conversa pelo community; não é rota pública do Gateway.

Para ativar o pagamento sandbox, defina `MERCADOPAGO_ENVIRONMENT=sandbox`, `MERCADOPAGO_ACCESS_TOKEN` de teste, `MERCADOPAGO_WEBHOOK_SECRET`, `MERCADOPAGO_NOTIFICATION_URL` HTTPS pública e `FRONTEND_RETURN_URL` HTTPS pública. A API do provedor permanece `https://api.mercadopago.com` com credenciais de teste. Sem essas variáveis, o serviço inicia sem conexão externa e `POST /api/v1/checkout` retorna 503 antes de criar pedido. Configuração parcial ou de produção impede a inicialização. A URL devolvida ao navegador deve pertencer a `sandbox.mercadopago.com`. Não se armazenam cartão, CVV, token de provedor ou segredo no pedido ou frontend. O roteiro de validação manual está em `docs/PI-54-checkout-pro-sandbox.md`.

Contratos conferidos na [Preferences API](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/create-preference/post), [webhooks](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-content/notifications/webhooks), [consulta de pagamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/get-payment/get), [pedido comercial](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/merchant-orders/get-merchant-order/get) e [reembolsos/cancelamentos](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/refunds-and-cancellations).

## PI-197 / PI-246: relatório de vendas

`GET /api/v1/artesao/vendas/relatorio` exige JWT de artesão e filtra pelo `sub`. Período: `periodo=7d|30d|90d|mes` (padrão `30d`; `mes` vai do dia 1 até hoje) ou `de=AAAA-MM-DD&ate=AAAA-MM-DD` (inclusivos, até 366 dias). Os dias são contados no fuso `America/Recife`; período inválido responde 400 `PERIODO_INVALIDO`.

**Fórmula da receita:** soma de `preço cotado × quantidade` dos itens de pedidos do artesão nos estados `confirmado`, `producao`, `enviado` ou `entregue` e sem encerramento em andamento. O frete fica fora da receita e é devolvido em `frete`. A data da venda é a da etapa `confirmado` (confirmação do pagamento); pedidos antigos sem essa etapa usam a data de criação. Pedidos aguardando pagamento ou confirmação, cancelados, recusados, reembolsados ou com cancelamento/estorno pendente não entram. `ticketMedio = receita / quantidadePedidos` (2 casas, meio para cima).

Resposta: `periodo`, `receita`, `frete`, `quantidadePedidos`, `unidadesVendidas`, `ticketMedio`, `estadosConsiderados`, `formulaReceita`, `ranking` (por peça: `slug`, `unidades`, `pedidos`, `receita`, `participacao` em %; ordenado por receita, depois unidades, depois slug) e `serie` (pontos diários até 31 dias, mensais acima disso, incluindo zeros). Os nomes das peças são resolvidos no front pela listagem do painel do catálogo. `GET /api/v1/artesao/vendas` continua existindo para a lista de pedidos do painel.

## PI-198 / PI-248: resumo do painel

`GET /api/v1/artesao/painel/resumo` (contrato `versaoContrato: "painel-resumo.v1"`) compõe no servidor:

| Campo | Fonte |
|---|---|
| `faturamentoMes` | commerce, mesma fórmula do relatório com `periodo=mes` |
| `pedidosAAceitar`, `emProducao`, `enviados` | commerce, pedidos `confirmado`, `producao` e `enviado` do `sub` |
| `pecasAtivas`, `avisos` | catalog, pelo contrato HTTP existente `GET /api/v1/artesaos/artesao-{sub}/pecas/painel` com o JWT do artesão (peças `disponivel` ou `reservada` contam como ativas; `vendida` e `inativada` geram aviso) |

A chamada ao catálogo corre em paralelo às consultas locais e tem timeout de `COMMERCE_PAINEL_CATALOGO_TIMEOUT_MS` (padrão 800 ms). Se o catálogo falhar ou estourar o tempo, a resposta continua 200 com `parcial: true`, `fontesIndisponiveis: ["catalogo"]` e `pecasAtivas`/`avisos` como `null` — nunca zero. `duracaoMs` informa o tempo de composição no servidor.

Medição (meta de 500 ms): no teste `PosVendaHttpPostgresTest` (PostgreSQL 18 via Testcontainers e catálogo HTTP falso), 10 chamadas HTTP após aquecimento ficaram entre 10 e 18 ms; com o catálogo lento (2 s) a resposta parcial saiu em 608 ms com timeout de teste de 600 ms. Na pilha local (Compose `balaio-local`, PostgreSQL 18, catálogo real, artesão com uma peça e um pedido), 30 chamadas sequenciais pelo gateway (`localhost:8080`) com cookie de sessão, em 22/11/2026: média 18,9 ms, p50 17,9 ms, p95 22,9 ms, máximo 29,3 ms — dentro da meta de 500 ms. O relatório de 90 dias, nas mesmas condições, teve média 12,4 ms e p95 13,4 ms. A medição inclui loopback e gateway; não é SLA de produção.

## PI-42: e-mails de pedido

Eventos e destinatários: `pagamento_confirmado` (comprador e artesão, quando o pedido chega a `confirmado`, ou seja, após pagamento aprovado pelo provedor e reserva confirmada), `enviado` (comprador, com rastreio) e `entregue` (comprador). Um trigger da migration V6 grava a fila `pedido_emails` na mesma transação da mudança de estado; a chave `(pedido_id, evento, destinatario)` impede duplicidade. Pedido recusado, expirado ou estornado antes de `confirmado` não gera confirmação.

O envio é feito por um worker agendado (`balaio.mail.intervalo-ms`, padrão 15 s), fora da transação do pedido e do checkout. Cada lote reserva as linhas com `FOR UPDATE SKIP LOCKED`; falhas incrementam `tentativas`, guardam `ultimo_erro` e reagendam com espera exponencial (30 s a 1 h). Após 8 falhas a linha fica `falhou`. Situação: `GET /internal/v1/pedidos/{id}/emails`; reagendar falhas sem reenviar o que já foi entregue: `POST /internal/v1/pedidos/{id}/emails/reenviar` (ambas com `X-Balaio-Service-Token`). O e-mail do destinatário vem de `GET /internal/v1/contas/{id}/contato` no profile-service, protegido pelo mesmo `BALAIO_INTERNAL_TOKEN`.

Variáveis: `COMMERCE_SMTP_HOST` (padrão `localhost`), `COMMERCE_SMTP_PORT` (`1025`), `COMMERCE_SMTP_USERNAME`, `COMMERCE_SMTP_PASSWORD`, `COMMERCE_SMTP_AUTH` (`false`), `COMMERCE_SMTP_STARTTLS` (`false`), `COMMERCE_SMTP_*_TIMEOUT`, `COMMERCE_MAIL_FROM` e `COMMERCE_FRONTEND_BASE_URL` (link no corpo). O health de e-mail do Actuator fica desligado para que um SMTP fora do ar não derrube o serviço. Dentro do Compose, `localhost` é o próprio container: sem um SMTP de teste acessível, os envios ficam pendentes e reaparecem com `ultimo_erro` na rota interna, o que é o comportamento esperado.

## PI-212 / PI-263: frete

Contrato, variáveis e como obter o token do Melhor Envio Sandbox: `docs/PI-263-melhor-envio-sandbox.md`.
