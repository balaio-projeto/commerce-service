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

## PI-54: Checkout Pro em sandbox

`POST /api/v1/checkout` exige JWT de comprador e `Idempotency-Key`. Aceita `itens: [{slug, quantidade}]`, `freteId: "padrao"`, `meio: "checkout_pro"` e `endereco` com `cep`, `endereco`, `cidade` e `estado`. A resposta 202 mantém `{dados,erro}` e inclui `checkoutUrl` apenas quando a preferência está pronta. O preço dos itens, o frete demonstrativo de R$ 38,90 e o total são calculados no commerce; o catálogo fornece a cotação e reserva peças únicas por API interna. Encomendas não usam reserva exclusiva.

O commerce persiste o pedido antes de reservar e cria uma intenção local antes de chamar `POST /checkout/preferences`. A mesma chave e payload retornam o mesmo pedido e URL; payload divergente recebe 409. A cotação e os comandos remotos de reserva ocorrem fora da transação local; um lease no pedido impede workers simultâneos e expira após falha do processo. Depois de um timeout ambíguo, a intenção fica pendente e o worker consulta `GET /checkout/preferences/search` pela `external_reference` antes de vincular a preferência encontrada. Se a busca não encontrar resultado, ele **não repete o POST** automaticamente, para impedir segunda cobrança. Esse caso exige conciliação operacional. A preferência expira após 13 minutos; depois de 16 minutos sem pagamento na busca autenticada, o commerce inicia a liberação da reserva. A reserva do catálogo expira em 15 minutos.

`POST /api/v1/pagamentos/mercado-pago/notificacoes` recebe somente notificações `payment` com HMAC válida. A notificação é gravada antes do HTTP 200; o worker consulta `GET /v1/payments/{id}` e `GET /merchant_orders/{id}` com o token de teste e verifica referência, preferência, valor, moeda e `live_mode=false`. O antigo endpoint interno de pagamento simulado foi removido. Redirect, query string e retorno do navegador não mudam o estado financeiro. Status `approved` inicia confirmação da reserva; `pending`, `in_process`, `authorized`, `in_mediation` e estados desconhecidos não encerram o pedido; `rejected`, `cancelled` e `expired` iniciam liberação. Cobrança aprovada após encerramento inicia reembolso pendente, sem marcar venda concluída.

`PATCH /api/v1/pedidos/{id}/cancelar` exige o comprador e `/recusar` exige o artesão titular. Cancelamento de pagamento pendente usa `PUT /v1/payments/{id}` com `status=cancelled` quando há payment ID; sem pagamento identificado, aguarda expiração e consulta autenticada antes de encerrar. Pedido aprovado passa por `reembolso_pendente` e usa `POST /v1/payments/{id}/refunds` com chave idempotente; somente `refunded` ou valor reembolsado integral consultado ao provedor permite `reembolsado` e liberação da reserva. A decisão `cancelar_reembolsar` da mediação interna segue a mesma regra. Falha de rede mantém estado pendente e é reprocessada. O sandbox pode recusar cancelamento ou reembolso conforme o método e estado do pagamento; o serviço não promete conclusão antecipada. Não há retenção nem repasse ao artesão nesta integração.

`GET /api/v1/pedidos` lista somente os pedidos do comprador do JWT. Para o ateliê, `GET /api/v1/artesao/pedidos-pendentes` lista apenas pedidos `confirmado` com `id`, `pecaSlug`, `comprador`, `quando` e `valor`; `GET /api/v1/artesao/vendas` retorna pedidos pagos ainda ativos para as métricas e vendas. Ambas exigem JWT de artesão e filtram pelo `sub` validado, ignorando qualquer `artesao` informado na query. O nome do comprador aparece como rótulo genérico até existir contrato de consulta de perfil para este dado.

Configuração obrigatória: `MERCADOPAGO_ENVIRONMENT=sandbox`, `MERCADOPAGO_BASE_URL=https://api.mercadopago.com`, `MERCADOPAGO_ACCESS_TOKEN` de teste, `MERCADOPAGO_WEBHOOK_SECRET`, `MERCADOPAGO_NOTIFICATION_URL` HTTPS pública e `FRONTEND_RETURN_URL` HTTPS pública. O serviço falha na inicialização sem configuração sandbox explícita. A URL devolvida ao navegador deve pertencer a `sandbox.mercadopago.com`. Não se armazenam cartão, CVV, token de provedor ou segredo no pedido ou frontend. O roteiro de validação manual está em `docs/PI-54-checkout-pro-sandbox.md`.

Contratos conferidos na [Preferences API](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/create-preference/post), [webhooks](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-content/notifications/webhooks), [consulta de pagamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/get-payment/get), [pedido comercial](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/merchant-orders/get-merchant-order/get) e [reembolsos/cancelamentos](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/refunds-and-cancellations).

Para testar com PostgreSQL 18 via Testcontainers: `$env:COMMERCE_POSTGRES_TEST='true'; .\mvnw.cmd '-Djava.version=21' clean verify`.
