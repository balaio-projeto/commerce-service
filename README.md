# commerce-service

Sacola, pedidos, pagamento Checkout Pro em sandbox e frete demonstrativo. O serviço é independente, usa Java 25 e Spring Boot 4.1.1 e recebe requisições externas por meio do api-gateway.

## Stack inicial

- Spring MVC, Spring Data JPA e PostgreSQL
- Flyway para migrações do banco próprio
- Bean Validation
- Spring Boot Actuator

## Execução

Porta HTTP padrão: 8083. Para executar pela IDE, defina `COMMERCE_SERVICE_PORT` (opcional), `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. O banco e suas credenciais são configurados no repositório `platform`.

No Windows: `.\mvnw.cmd spring-boot:run`. Testes: `.\mvnw.cmd test`.

O `Dockerfile` constrói uma imagem própria. Em `platform`, use `docker compose up --build -d commerce-service` para iniciar este serviço e seu banco ou `docker compose --profile extended up --build -d` para iniciar todos. Health: `http://localhost:8083/actuator/health`.

## PI-187: sacola do comprador

`GET /api/v1/estado/sacola` devolve `{dados:[{slug,quantidade}],erro:null}` para o comprador autenticado; sem itens, `dados` é `[]`. `PUT /api/v1/estado/sacola` recebe `{sacola:[{slug,quantidade}]}` e substitui atomicamente as linhas da conta do `sub` no JWT. Não aceita identidade nem preço no corpo. Quantidades válidas são 1 a 3, e peça única aceita somente 1. O commerce valida slug, preço atual e disponibilidade pela cotação HTTP interna do catálogo. A sacola não cria reserva nem congela preço; o checkout faz nova cotação antes de reservar.

## PI-188: revalidação antes do checkout

`POST /api/v1/sacola/revalidar` exige JWT de comprador e lê apenas os itens persistidos do `sub`; o corpo é ignorado. A resposta 200 segue o envelope `{dados,erro}`. Exemplo: `{ "dados": { "itens": [{ "slug": "peca-exemplo", "quantidade": 1, "precoAtual": 42.50, "disponibilidade": "disponivel", "situacao": "disponivel" }], "podeProsseguir": true, "revalidadoEm": "2026-11-17T00:00:00Z" }, "erro": null }`. `precoAtual` é o preço final por unidade já descontado na cotação do catálogo. Item ausente ou em conflito é retornado como `situacao:"indisponivel"`, `disponibilidade:"indisponivel"`, `precoAtual:null` e impede prosseguir; os demais itens continuam visíveis. Sacola vazia devolve `itens:[]` e `podeProsseguir:false`.

Falha de rede, timeout ou resposta malformada em qualquer cotação devolve 503 com `{dados:null,erro:{codigo:"CATALOGO_INDISPONIVEL",...}}`; não há sucesso parcial. Uma peça ausente (404) ou em conflito (409) continua como indisponibilidade do item. A operação não grava preço, não altera a sacola e não cria reserva. A resposta é uma fotografia; o checkout consulta novamente e aplica a reserva atômica da PI-190. Para reproduzir localmente, iniciar o Compose com `docker compose --profile extended up --build -d --wait` em `platform`, autenticar um comprador, adicionar peças à sacola e chamar o POST com `Authorization: Bearer <JWT de teste>`. Repetir após alterar preço ou inativar uma peça no catálogo; interromper o catálogo para verificar o 503. Não usar credenciais de produção.

Medição local em 17/11/2026: Compose `extended` com PostgreSQL 18 e catálogo local, sacola com duas peças publicadas, 30 chamadas sequenciais HTTP diretas a `localhost:8083` após aquecimento. Tempo observado do cliente ao serviço: média 28,87 ms, p95 49,63 ms, mínimo 19,67 ms e máximo 59,90 ms. Como meta de regressão **nesse ambiente** para duas peças, usar p95 até 150 ms; a medição inclui loopback/cliente e não é um SLA de produção. Com o catálogo parado, a resposta foi 503, `dados:null`, em 2.071,54 ms, distinguindo falha da latência normal. Os timeouts globais não foram alterados.

## PI-54: Checkout Pro em sandbox

`POST /api/v1/checkout` exige JWT de comprador e `Idempotency-Key`. Aceita `itens: [{slug, quantidade}]`, `freteId: "padrao"`, `meio: "checkout_pro"` e `endereco` com `cep`, `endereco`, `cidade` e `estado`. A resposta 202 mantém `{dados,erro}` e inclui `checkoutUrl` apenas quando a preferência está pronta. O preço dos itens, o frete demonstrativo de R$ 38,90 e o total são calculados no commerce; o catálogo fornece a cotação e reserva peças únicas por API interna. Encomendas não usam reserva exclusiva.

O commerce persiste o pedido antes de reservar e cria uma intenção local antes de chamar `POST /checkout/preferences`. A mesma chave e payload retornam o mesmo pedido e URL; payload divergente recebe 409. A cotação e os comandos remotos de reserva ocorrem fora da transação local; um lease no pedido impede workers simultâneos e expira após falha do processo. Depois de um timeout ambíguo, a intenção fica pendente e o worker consulta `GET /checkout/preferences/search` pela `external_reference` antes de vincular a preferência encontrada. Se a busca não encontrar resultado, ele **não repete o POST** automaticamente, para impedir segunda cobrança. Esse caso exige conciliação operacional. A preferência expira após 13 minutos; depois de 16 minutos sem pagamento na busca autenticada, o commerce inicia a liberação da reserva. A reserva do catálogo expira em 15 minutos.

`POST /api/v1/pagamentos/mercado-pago/notificacoes` recebe somente notificações `payment` com HMAC válida. A notificação é gravada antes do HTTP 200; o worker consulta `GET /v1/payments/{id}` e `GET /merchant_orders/{id}` com o token de teste e verifica referência, preferência, valor, moeda e `live_mode=false`. O antigo endpoint interno de pagamento simulado foi removido. Redirect, query string e retorno do navegador não mudam o estado financeiro. Status `approved` inicia confirmação da reserva; `pending`, `in_process`, `authorized`, `in_mediation` e estados desconhecidos não encerram o pedido; `rejected`, `cancelled` e `expired` iniciam liberação. Cobrança aprovada após encerramento inicia reembolso pendente, sem marcar venda concluída.

`PATCH /api/v1/pedidos/{id}/cancelar` exige o comprador e `/recusar` exige o artesão titular. Cancelamento de pagamento pendente usa `PUT /v1/payments/{id}` com `status=cancelled` quando há payment ID; sem pagamento identificado, aguarda expiração e consulta autenticada antes de encerrar. Pedido aprovado passa por `reembolso_pendente` e usa `POST /v1/payments/{id}/refunds` com chave idempotente; somente `refunded` ou valor reembolsado integral consultado ao provedor permite `reembolsado` e liberação da reserva. A decisão `cancelar_reembolsar` da mediação interna segue a mesma regra. Falha de rede mantém estado pendente e é reprocessada. O sandbox pode recusar cancelamento ou reembolso conforme o método e estado do pagamento; o serviço não promete conclusão antecipada. Não há retenção nem repasse ao artesão nesta integração.

`GET /api/v1/pedidos` lista somente os pedidos do comprador do JWT. Para o ateliê, `GET /api/v1/artesao/pedidos-pendentes` lista apenas pedidos `confirmado` com `id`, `pecaSlug`, `comprador`, `quando` e `valor`; `GET /api/v1/artesao/vendas` retorna pedidos pagos ainda ativos para as métricas e vendas. Ambas exigem JWT de artesão e filtram pelo `sub` validado, ignorando qualquer `artesao` informado na query. O nome do comprador aparece como rótulo genérico até existir contrato de consulta de perfil para este dado.

`PATCH /api/v1/pedidos/{id}/estado` recebe `{ "estado": "producao" }` e exige o artesão titular no JWT. Somente pedidos `confirmado` podem avançar; outro artesão recebe 403 e estado inválido ou transição repetida não altera o pedido. A resposta é `{dados:<pedido atualizado>,erro:null}`; o comprador vê o mesmo estado persistido em `GET /api/v1/pedidos/{id}`. O vínculo com o artesão é obtido de `proprietarioId` na cotação HTTP do catálogo durante o checkout e armazenado em `pedidos.artesao_id`.

O detalhe do comprador inclui os itens (slug, quantidade e preço cotado), endereço do checkout e `etapas` ordenadas. A migration V4 registra a etapa inicial e cada mudança de `estado` na mesma transação da escrita, inclusive as realizadas pelo pagamento; operações que não mudam o estado não criam etapa. Ela marca os pedidos anteriores à V4 com histórico incompleto de forma persistente: `historicoInferido: true` continua presente após novas transições, e nenhuma data é inventada para etapas anteriores. A listagem e os contratos do artesão não expõem o endereço. `GET /internal/v1/pedidos/{id}/participantes` usa o token interno existente e retorna somente `compradorId` e `artesaoId` para autorização de conversa pelo community; não é rota pública do Gateway.

Para ativar o pagamento sandbox, defina `MERCADOPAGO_ENVIRONMENT=sandbox`, `MERCADOPAGO_ACCESS_TOKEN` de teste, `MERCADOPAGO_WEBHOOK_SECRET`, `MERCADOPAGO_NOTIFICATION_URL` HTTPS pública e `FRONTEND_RETURN_URL` HTTPS pública. A API do provedor permanece `https://api.mercadopago.com` com credenciais de teste. Sem essas variáveis, o serviço inicia sem conexão externa e `POST /api/v1/checkout` retorna 503 antes de criar pedido. Configuração parcial ou de produção impede a inicialização. A URL devolvida ao navegador deve pertencer a `sandbox.mercadopago.com`. Não se armazenam cartão, CVV, token de provedor ou segredo no pedido ou frontend. O roteiro de validação manual está em `docs/PI-54-checkout-pro-sandbox.md`.

Contratos conferidos na [Preferences API](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/create-preference/post), [webhooks](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-content/notifications/webhooks), [consulta de pagamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/get-payment/get), [pedido comercial](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/merchant-orders/get-merchant-order/get) e [reembolsos/cancelamentos](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/refunds-and-cancellations).

Para testar com PostgreSQL 18 via Testcontainers: `$env:COMMERCE_POSTGRES_TEST='true'; .\mvnw.cmd '-Djava.version=21' clean verify`.
