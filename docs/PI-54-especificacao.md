# Especificação técnica — PI-54: pagamento sandbox com Checkout Pro

**História:** Como comprador, quero concluir o pagamento do pedido por um provedor em homologação, para percorrer o fluxo de compra completo sem cobrança em produção.  
**Entrega:** 25/11/2026.  
**Serviço coordenador:** `commerce-service`.  
**Provedor escolhido:** Mercado Pago Checkout Pro (Preferences API), hospedado, usando credenciais e contas de teste.  
**Escopo:** mapa de arquivos e operações necessários para o dev implementar; este documento não contém implementação.

## 1. Objetivo e limites obrigatórios

Substituir o pagamento simulado do PI-190 por criação real de preferência no ambiente de teste do Mercado Pago. O endpoint público existente `POST /api/v1/checkout` permanece o comando idempotente de compra: valida e persiste o pedido, reserva a peça única conforme PI-190 e inicia uma intenção/preferência de pagamento. A resposta traz o pedido e a URL de checkout hospedado necessária ao redirecionamento.

A verdade financeira vem do servidor: notificação autenticada e consulta autenticada ao recurso de pagamento. Retorno do navegador, query string, parâmetros `status`/`collection_status` e navegação de sucesso nunca confirmam pagamento. Até o servidor confirmar, o pedido continua pendente. Nenhum serviço Balaio recebe, processa ou armazena PAN, CVV ou dados de cartão; esses dados permanecem no checkout do Mercado Pago.

Somente credenciais de teste e base URL do sandbox/homologação podem ser configuradas nesta entrega. Bloquear a inicialização/integração quando a configuração disser produção ou faltar a indicação explícita de sandbox. Segredos são variáveis/secret manager por ambiente; não versionar token, segredo de webhook, senha de conta de teste ou arquivo `.env` real.

## 2. Contrato do provedor a seguir

A escolha é **Checkout Pro via Preferences API**, não Checkout Transparente/Bricks. A preferência é criada pelo servidor via `POST https://api.mercadopago.com/checkout/preferences`, com `Authorization: Bearer <access token de teste>` e corpo contendo `items` (título, quantidade, `unit_price`, `currency_id: BRL`); dados comerciais e total vêm do pedido calculado no commerce, nunca do browser. A resposta contém `id`, `init_point` e `sandbox_init_point`; guardar `id` e devolver ao frontend exclusivamente `sandbox_init_point` enquanto o perfil é sandbox. Rejeitar a criação se a URL sandbox estiver ausente ou não pertencer ao domínio sandbox esperado. Referências: [referência oficial POST /checkout/preferences](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/create-preference/post), [criação de preferência](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/create-payment-preference), [contas de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts).

Campos da preferência:

- `items`: itens do pedido precificados pelo servidor, moeda `BRL`.
- `external_reference`: ID interno imutável do pedido para correlação sem confiar em parâmetro do navegador.
- `back_urls`: URLs da aplicação para os resultados de sucesso, pendente e falha; devem retornar à tela de pedido.
- `notification_url`: URL pública HTTPS encaminhada pelo gateway ao webhook do commerce.
- Resposta: guardar `id` da preferência e a URL sandbox de checkout; nunca devolver access token, webhook secret ou outras credenciais.
- Criar uma preferência por pedido/tentativa lógica. Repetição do checkout com a mesma `Idempotency-Key` retorna o pedido/preferência previamente persistidos, sem criar nova compra. A Preferences API não deve ser presumida como idempotente por header: implementar idempotência local e, em timeout ambíguo, pesquisar por `external_reference` usando `GET /checkout/preferences/search` quando viável antes de criar nova preferência; registrar essa limitação e impedir duplicação por pedido.

O Mercado Pago envia Webhooks com `x-signature`, `x-request-id` e identificador do recurso em `data.id`. Validar assinatura HMAC usando o segredo de webhook, incluindo os campos/canonicalização exigidos pelo validador oficial; recusar assinatura inválida. O corpo da notificação é somente um aviso. Após validação, consultar autenticadamente `GET https://api.mercadopago.com/v1/payments/{id}` com access token de teste e confirmar que pagamento, `external_reference`, valor, moeda e preferência pertencem ao pedido local antes de alterar seu estado. A documentação lista a consulta `/v1/payments/[ID]`, validação de assinatura e resposta HTTP 200/201 para confirmar recebimento; desenhar processamento durável para não perder eventos aceitos. Referência: [Webhooks e notificações](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-content/notifications/webhooks).

**Estados do provedor → decisão interna:** aprovado somente quando consulta autenticada retornou status aprovado e correspondência de pedido/valor/moeda; recusado/negado mantém falha e inicia liberação da reserva conforme PI-190; pendente/em processamento permanece pendente; cancelado/expirado encerra pagamento e solicita liberação conforme contrato PI-190. Mapear todos os statuses reais documentados na implementação, incluindo estados não terminais; status desconhecido não pode confirmar nem liberar automaticamente sem regra segura.

## 3. Fluxo e invariantes

1. `POST /api/v1/checkout` valida JWT de comprador e `Idempotency-Key`, calcula pedido/total no commerce, persiste pedido e, para peça única, reserva pelo catálogo usando o contrato PI-190. Criar preferência após existência de pedido e reserva. Se reserva falhar/expirar antes da intenção, não criar checkout pagável.
2. Responder 202 quando processamento assíncrono/pendente, com ID, estado real do pedido e URL sandbox se disponível; preservar envelope público `{ dados, erro }`. Repetir a chave deve devolver a mesma compra e URL, sem segunda reserva/pagamento.
3. Frontend redireciona à URL hospedada somente após receber a resposta do commerce. O navegador volta à rota de confirmação, mas a tela consulta `GET /api/v1/pedidos/{id}` e exibe estado pendente até confirmação server-side.
4. Webhook valida assinatura, registra de forma durável o evento/recurso com chave única, consulta o pagamento ao provedor e reconcilia estado. Duplicação deve ser idempotente e notificação respondida 200/201 após persistência segura; falha temporária da consulta/processamento deve permitir retry/reconciliação sem perder evento.
5. Aprovação confirmada: efetuar a transição local por operação do commerce e confirmar/consumir a reserva do catálogo via PI-190. Se o pagamento for aprovado mas catálogo não confirmar consumo, manter estado recuperável e reconciliar; não liberar a mesma reserva nem marcar pedido como concluído incorretamente.
6. Negação, expiração ou falha definitiva: atualizar estado do pagamento/pedido e solicitar liberação idempotente da reserva pelo `catalog-service`. Uma falha de rede não é confirmação de liberação nem de falha de pagamento; gravar estado pendente e reconciliar sem duplicar comando.
7. Não permitir cancelar o pagamento aprovado como se fosse reembolso concluído. Cancelamento de pagamento ainda não concluído e reembolso de pagamento aprovado são operações distintas e devem atualizar o pedido apenas após resultado confirmado/consultado no provedor. Decisão de reembolso segue o comando e estados do commerce; community-service não altera pedido localmente.

## 4. Arquivos existentes a alterar

### 4.1 `commerce-service`

| Arquivo existente | Alteração prevista |
|---|---|
| `commerce-service/src/main/java/br/com/balaio/commerce/order/PedidosController.java` | Adaptar resposta de `POST /api/v1/checkout` para expor checkout hospedado e estado de pagamento. Remover/substituir endpoint de simulação `POST /internal/v1/pedidos/{id}/pagamento` por endpoint público de webhook dedicado sem JWT de usuário, mas validado pela assinatura do provedor. Manter rotas de consulta; acrescentar comando autenticado de cancelamento/reembolso apenas conforme política definida. |
| `commerce-service/src/main/java/br/com/balaio/commerce/order/PedidoService.java` | Orquestrar persistência, reserva PI-190, intenção de pagamento, estados, confirmação, falha/expiração, liberação e reconciliação; continuar dono da máquina de estados do pedido. Separar chamadas remotas da transação local e tratar timeout como resultado desconhecido. |
| `commerce-service/src/main/java/br/com/balaio/commerce/order/CatalogoCliente.java` | Integrar consumo/confirmação/liberação conforme PI-190, mantendo idempotência, timeout e autenticação serviço-a-serviço. Não alterar diretamente o catálogo/banco. |
| `commerce-service/src/main/java/br/com/balaio/commerce/order/PedidoException.java` e `PedidoErrorHandler.java` | Manter envelopes e erros consistentes para indisponibilidade do provedor, pagamento pendente, configuração inválida e comandos incompatíveis com estado atual. |
| `commerce-service/src/main/java/br/com/balaio/commerce/security/JwtCommerceSecurityConfig.java` | Permitir a rota de webhook sem JWT de comprador, sem torná-la confiável anonimamente: a autenticação específica é assinatura HMAC; restringir a exceção exclusivamente à rota e método do webhook. Demais rotas continuam JWT. |
| `commerce-service/src/main/resources/application.yaml` | Adicionar URL/API do provedor explicitamente de teste, modo sandbox obrigatório, timeouts, URLs públicas de retorno/webhook e nomes de variáveis para token de teste/segredo webhook; nenhum valor secreto real. Não deixar configuração de produção ativa por fallback. |
| `commerce-service/src/main/resources/db/migration/V1__pedidos_e_operacoes.sql` | Não reescrever migration aplicada. Criar nova migration incremental para persistir preferência, pagamento(s), status/expiração, tentativas/reconciliação e eventos idempotentes/outbox necessários. Confirmar se tabela `eventos_pagamento` atual será migrada ou substituída sem apagar histórico. |
| `commerce-service/README.md` | Trocar a descrição do simulador por contrato/rotas/status PI-54, variáveis por ambiente, setup das contas de teste, política de cancelamento/reembolso/repasse e roteiro operacional sem segredos. |
| `commerce-service/pom.xml` | Confirmar dependência/versão suportada do SDK oficial Mercado Pago para preferência, consulta, assinatura e reembolso; registrar dependência versionada e atualizar só se necessário. |
| `commerce-service/src/test/java/br/com/balaio/commerce/order/PedidoWorkflowPostgresTest.java` | Evoluir cenários existentes de pagamento simulado para estados reais mockados/sandbox contract: aprovação, recusa, expiração, repetição, reserva única, duplicidade e falha parcial. Teste não pode chamar API de produção. |

### 4.2 `api-gateway`

| Arquivo existente | Alteração prevista |
|---|---|
| `api-gateway/src/main/resources/application.yaml` | Incluir caminho HTTP do webhook na rota commerce, com predicado restrito à rota/método definidos e preservando query `data.id`; não expor rotas internas de serviço. Checkout/consulta já são encaminhados ao commerce. |
| `api-gateway/src/test/java/br/com/balaio/api_gateway/ApiGatewayApplicationTests.java` | Adicionar verificação de encaminhamento do webhook e preservação de headers/query necessários; garantir que demais rotas commerce não mudem. |
| `api-gateway/README.md` | Documentar encaminhamento HTTPS público do webhook e dependência de URL alcançável pelo Mercado Pago em homologação. |

### 4.3 Frontend `Balaio/Frontend`

| Arquivo existente | Alteração prevista |
|---|---|
| `Balaio/Frontend/src/services/api/pedidos.servico.ts` | Ajustar tipo de retorno do checkout para incluir URL sandbox; manter reuso da `Idempotency-Key` até conclusão do POST, inclusive em falha de rede/timeout ambíguo. Expor consulta/estado atualizado do pedido. |
| `Balaio/Frontend/src/components/carrinho/CheckoutFormulario.tsx` | Remover opções e textos de pagamento simulado; retirar campos que insinuem escolha de pagamento Balaio se Checkout Pro controla meios. Ao receber URL do commerce, redirecionar ao checkout hospedado. Não limpar sacola/perder chave antes de confirmar criação do pedido. |
| `Balaio/Frontend/src/app/confirmation/[id]/page.tsx` | Ler retorno de sucesso/pendente/falha como indicação visual apenas; obter estado autorizado do backend por consulta/polling controlado. Mostrar “aguardando confirmação” até confirmação do servidor, falha real, expiração ou pagamento aprovado. Nunca derivar status financeiro só de query string ou redirect. |
| `Balaio/Frontend/src/app/orders/[id]/page.tsx` | Mostrar status de pagamento, cancelamento/reembolso e instruções corretas conforme estado persistido; evitar declarar reembolso concluído antes da confirmação. |
| `Balaio/Frontend/src/types/dominio.ts` | Tipar os estados efetivamente expostos pelo commerce e metadados não sensíveis da intenção (status e URL apenas quando necessária); não incluir token do provedor, segredo ou dados de cartão. |
| `Balaio/Frontend/src/proxy.ts` | Conferir que rotas API públicas autenticadas seguem fluxo existente e webhook não passa pelo proxy de sessão do navegador; alterar somente se o proxy tratar paths do Gateway de modo incompatível. |
| `Balaio/Frontend/docs/api.md` e `Balaio/Frontend/docs/arquitetura.md` | Atualizar contrato de checkout, redirects, fonte de verdade do status e ausência de cartão/dados financeiros sensíveis no frontend, se esses docs descrevem checkout atual. |

### 4.4 `platform` e catálogo — apenas integração/contrato PI-190

| Arquivo existente | Alteração prevista |
|---|---|
| `platform/docker-compose.yml` (confirmar nome/arquivo no repo platform) | Passar configuração sandbox ao commerce via ambiente/secret local excluído do Git e garantir URL pública de webhook somente no perfil de homologação. Não adicionar credenciais reais ao Compose. |
| `catalog-service/src/main/java/br/com/balaio/catalog/reserva/ReservasInternasController.java`, `ReservaService.java`, migration `V5__reservas_pecas_unicas.sql` e `V6__consumo_reserva_apos_pagamento.sql` | **Alinhar/confirmar contrato PI-190**, não duplicar regras no escopo PI-54. Reconfirmar reservar/confirmar consumo/liberar, expiração e semântica de repetição. Mudança no catálogo somente se o contrato existente não permitir confirmar/liberar reserva de forma idempotente e recuperável; criar migration incremental, nunca editar V1–V6 já aplicadas. |

## 5. Novos arquivos esperados

Nomes abaixo seguem a estrutura atual e podem ser ajustados ao padrão já existente, preservando as fronteiras e responsabilidades:

### `commerce-service`

- `src/main/java/br/com/balaio/commerce/payment/CheckoutProClient.java` ou adapter equivalente: cliente server-to-server para criar preferência, consultar pagamento e executar operação de cancelamento/reembolso sandbox. URL base API `https://api.mercadopago.com`; `POST /checkout/preferences` e `GET /v1/payments/{id}` usam `Authorization: Bearer` com token de teste; jamais logar Authorization/token.
- `src/main/java/br/com/balaio/commerce/payment/PaymentProviderPort.java` e `MercadoPagoCheckoutProAdapter.java` (se mantida a separação hexagonal): porta de pagamento e adapter Mercado Pago; interface focada em criar preferência/consultar/cancelar/reembolsar.
- `src/main/java/br/com/balaio/commerce/payment/PaymentWebhookController.java`: rota `POST /api/v1/pagamentos/mercado-pago/notificacoes` (ou caminho acordado com gateway), validar assinatura antes de consumir evento. Resposta enxuta HTTP 200/201 após gravação segura; respostas de autenticação inválida/erro conforme guia.
- `src/main/java/br/com/balaio/commerce/payment/WebhookSignatureVerifier.java`: integração com validador oficial ou implementação compatível coberta por vetores oficiais; valida `x-signature`, `x-request-id`, `data.id` e tolerância temporal exigida.
- DTOs separados para notificação, preferência, pagamento consultado e resposta pública de checkout; não desserializar webhook e tratá-lo diretamente como resultado financeiro.
- `src/main/java/br/com/balaio/commerce/payment/PaymentReconciliationWorker.java` (ou componente de aplicação equivalente): reenviar processamento de eventos persistidos e consultar estados pendentes após falhas/reinício; aplicar backoff, limite observável e fila/outbox durável.
- Persistence entities/repositories/adapters para pagamento/preferência e inbox/outbox de webhook; garantir unicidade da notificação e do recurso de pagamento onde aplicável.
- Migration incremental `src/main/resources/db/migration/V2__pagamentos_checkout_pro.sql` ou próximo número livre: campos/tabelas/índices de intenção e pagamento, unicidade dos IDs remotos e evento recebido, correlação com pedido. Não armazenar dados de cartão.
- Configuração tipada de `payment.mercadopago.*` com validação startup do modo sandbox, URL e credenciais.
- Testes de unidade para adapter/status/assinatura; teste de integração Postgres para concorrência/idempotência/worker; client HTTP mockado para garantir que testes não chamem endpoint de produção; cenários fake de resposta não autenticada não confirmam pedido.
- Roteiro manual sandbox versionado em `commerce-service/docs/PI-54-checkout-pro-sandbox.md` ou seção dedicada no README, incluindo contas teste vendedor/comprador separadas, URL acessível de webhook, pagamento aprovado/recusado/pendente, cancelamento e reembolso conforme elegibilidade.

### Frontend

- Se polling ficar complexo para a rota existente, novo componente `Balaio/Frontend/src/components/pedidos/EstadoPagamento.tsx` para consultar pedido, representar pendente/aprovado/recusado/expirado/reembolsado e limitar frequência; não precisa novo endpoint se `GET /api/v1/pedidos/{id}` entregar estado atualizado.

## 6. Rotas/contratos a implementar ou ajustar

1. **Manter e ampliar** `POST /api/v1/checkout`: JWT comprador + `Idempotency-Key`; total só do servidor. Retorno 202 pode conter `pedido`, `estadoPagamento` e `checkoutUrl` sandbox sem segredos. Mesma chave e mesmo payload devolvem mesma intenção; payload divergente para a mesma chave retorna conflito conforme comportamento idempotente vigente.
2. **Criar** `POST /api/v1/pagamentos/mercado-pago/notificacoes`: webhook externo, sem JWT de usuário; HMAC obrigatória e consulta autenticada server-to-server do payment ID em `/v1/payments/{id}`. Não aceitar operação de confirmação por body de cliente ou endpoint interno simulador.
3. **Manter** `GET /api/v1/pedidos/{id}` como leitura autenticada do estado autoritativo. Garantir resposta que permita UX saber status de pagamento, instante/causa pública e se reserva está em reconciliação, sem expor identificadores sensíveis.
4. **Ajustar/remover** `POST /internal/v1/pedidos/{id}/pagamento` após migração, eliminando o simulador do runtime de homologação. Não deixar caminho alternativo que confirme pedido sem provedor autenticado.
5. **Definir explicitamente** política e comando autenticado para cancelar antes da aprovação e reembolsar após pagamento aprovado, conforme decisão de pedido do commerce. A chamada remota ao MP é idempotente ou reconciliável; registrar comando pendente antes da rede e estado financeiro só muda após resposta/consulta confirmada. Não criar endpoint público de reembolso baseado somente em pedidoId sem autorização e regras de negócio.
6. **Catálogo PI-190**: commerce usa apenas endpoints internos já acordados para reservar, confirmar/consumir e liberar; não acessar banco catalog. Verificar contrato e nomes antes de codificar.

## 7. Cancelamento, reembolso, repasse e comunicação

A documentação do Mercado Pago distingue cancelamento antes de finalizar/processar de reembolso após captura; reembolso usa operação `POST /v1/payments/{id}/refunds` (contrato atual a confirmar) e deve refletir a resposta/status remoto. Em produção a documentação menciona janela de até 180 dias e saldo suficiente, mas esta história é sandbox; não codificar garantias de produção a partir dessa janela. Referência: [cancelamentos e reembolsos](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/refunds-and-cancellations).

Antes da implementação, confirmar com produto/PI-190 e documentação atual: quais status de pagamento canceláveis, suporte do ambiente de teste para cancelamento/reembolso e forma de criar cenário demonstrável; gravar `cancelamento_pendente`/`reembolso_pendente` quando operação remota tiver resultado desconhecido; somente refletir `cancelado`/`reembolsado` após confirmação/consulta. Cancelamento depois de aprovação não libera peça como se não tivesse havido cobrança; deve seguir política de mediação/reembolso no commerce e liberação da reserva após resolução aplicável.

Checkout Pro hospedado não deve ser descrito como custódia de valores ou retenção até entrega. Não foi estabelecida nesta especificação uma capacidade de split/repasse para artesãos no sandbox. Portanto, a interface e documentação desta entrega não prometem retenção, liberação pós-entrega ou repasse automático. Se split/marketplace for requisito para o pagamento demonstrável, validar com conta integradora/test seller e documentação específica antes de implementar; sem confirmação, limitar PI-54 a pagamento/reembolso sandbox e registrar repasse como fora deste fluxo.

## 8. Segredos e configuração por ambiente

Documentar nomes/configuração sem valores:

- `MERCADOPAGO_ACCESS_TOKEN` (somente token de teste no perfil local/homologação).
- `MERCADOPAGO_WEBHOOK_SECRET` (segredo HMAC da aplicação).
- `MERCADOPAGO_BASE_URL` ou constante segura para API oficial; sandbox Checkout Pro usa URL de checkout sandbox devolvida pelo provedor e API oficial com credenciais de teste, confirmar detalhes do ambiente no setup.
- `MERCADOPAGO_ENVIRONMENT=sandbox` com validação obrigatória; rejeitar `production` nesta entrega.
- `MERCADOPAGO_NOTIFICATION_URL`, `FRONTEND_RETURN_URL`, timeouts e chave/configuração de conta teste conforme necessidade.

Usar variáveis injetadas/secret manager; `.env.example` só com nomes e valores fictícios. Adicionar `.env`, logs de request/response sensíveis e arquivos de credenciais ao `.gitignore` quando ainda não cobertos. Não incluir senha/username das contas teste em prompt, fixture commitada, evidência ou log; compartilhar dados das contas pelo canal seguro da equipe. Frontend nunca recebe access token ou webhook secret.

## 9. Testes e roteiro de verificação

Cobrir automaticamente:

- mesma chave no checkout (sequencial e concorrente): um pedido, uma reserva, uma intenção/preferência e mesmo resultado retornado;
- payload divergente com chave reutilizada: conflito; falha/timeout ambíguo do POST preference: reconciliação sem checkout duplicado;
- webhook válido seguido de consulta server-to-server: aprovado confirma apenas o pedido e reserva correspondentes com amount/currency/reference iguais;
- assinatura inválida, query `data.id` adulterada, evento de outro pedido, valor/moeda diferentes e modo live: não confirmar nem liberar indevidamente;
- repetição de webhook, mesma notificação concorrente e eventos fora de ordem: uma única transição efetiva; não regredir pedido terminal;
- recusado, pendente, expirado, cancelamento, reembolso confirmado e falha/timeout durante operações remotas; worker reiniciado recupera inbox/outbox e mantém estados pendentes;
- corrida entre aprovação, expiração/liberação PI-190 e cancelamento; nunca confirmar/consumir ou liberar duas vezes a peça única;
- frontend retorno de sucesso/pendente/falha sem webhook: continua exibindo pendente; após backend atualizar, mostra estado real. Nenhum teste usa endpoint de produção ou dados de cartão.

Roteiro manual deve declarar pré-requisitos (aplicação Mercado Pago em modo teste, conta test seller e test buyer do mesmo país, credenciais de teste, URL pública HTTPS para webhook, tunnel de desenvolvimento), iniciar fluxo no frontend, selecionar resposta usando métodos oficiais de teste, conferir notificação/consulta/status/registro do catálogo, duplicar evento e demonstrar cancelamento/reembolso sandbox compatível. Não commitar credenciais ou cartões em docs. As contas de teste devem ser distintas (vendedor e comprador); documentação oficial as recomenda para Checkout Pro: [contas de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts).

## 10. Critérios para dar PI-54 como concluída

- Fluxo sandbox de Checkout Pro e contrato de preferência/notificação/status/reembolso documentados e verificados contra documentação oficial vigente.
- `POST /checkout` idempotente cria pedido/reserva/intenção e fornece URL sandbox hospedada.
- Nenhuma confirmação se origina do redirect do navegador; somente consulta autenticada do provedor após webhook validado/reconciliação pode aprovar.
- Aprovado, recusado, expirado e webhook repetido mantêm estado coerente; peça única não é confirmada/liberada duas vezes; falhas parciais são recuperáveis.
- UI comunica status pendente/falha/aprovação do servidor, e cancelamento/reembolso refletem o provedor sem prometer repasse/retencão inexistente.
- Testes automáticos e roteiro manual documentam fluxo fim a fim; nenhuma chamada, credencial ou captura de produção.

## 11. Instrução para execução

Antes de escrever código, o dev deve conferir este mapa contra a branch atual e reabrir os contratos oficiais citados, especialmente schema/campos de resposta da preferência, URL sandbox, assinatura webhook, statuses de payment, cancelamento e reembolso. Resolver divergência deste plano com evidência de documentação/API e atualizar este documento/PR. Implementar somente no(s) repositório(s) e arquivos confirmados; preservar migrations aplicadas e compatibilidade do PI-190; testar apenas com HTTP mock/sandbox explicitamente configurado.

