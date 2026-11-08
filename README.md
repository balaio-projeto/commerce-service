# commerce-service

Sacola, pedidos, pagamentos e frete; integrações reais serão demonstradas em homologação. O serviço é independente, usa Java 25 e Spring Boot 4.1.1 e recebe requisições externas por meio do api-gateway.

## Stack inicial

- Spring MVC, Spring Data JPA e PostgreSQL
- Flyway para migrações do banco próprio
- Bean Validation
- Spring Boot Actuator

## Execução

Porta HTTP padrão: 8083. Para executar pela IDE, defina `COMMERCE_SERVICE_PORT` (opcional), `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. O banco e suas credenciais são configurados no repositório `platform`.

No Windows: `.\mvnw.cmd spring-boot:run`. Testes: `.\mvnw.cmd test`.

O `Dockerfile` constrói uma imagem própria. Em `platform`, use `docker compose up --build -d commerce-service` para iniciar este serviço e seu banco ou `docker compose --profile extended up --build -d` para iniciar todos. Health: `http://localhost:8083/actuator/health`.

## Pedidos demonstrativos (PI-190)

`POST /api/v1/checkout` exige JWT de comprador e `Idempotency-Key`. Aceita `itens: [{slug, quantidade}]`, `freteId: "padrao"`, `meio: "pix" | "cartao" | "boleto"` e `endereco` com `cep`, `endereco`, `cidade` e `estado`. A resposta 202 traz o pedido. O serviço consulta preços e proprietário no catálogo, separa compras por ateliê, calcula subtotal, frete e total no servidor e persiste o pedido antes de reservar cada peça única. `GET /api/v1/fretes` expõe a única opção demonstrativa, R$ 38,90. Encomendas não criam reservas exclusivas.

Estados: `aguardando_reserva` → `aguardando_pagamento` → `confirmacao_pendente` → `confirmado` → `producao`. A reserva vence em 15 minutos no catálogo; sem evento de pagamento, o worker encerra o pedido após 14 minutos e solicita liberação. Cancelamento e recusa passam por `encerramento_pendente` até o catálogo confirmar a liberação. O worker reprocessa operações pendentes com a mesma chave idempotente; após dez falhas, preserva o estado pendente e o último erro para intervenção operacional.

Somente `POST /internal/v1/pedidos/{id}/pagamento`, autenticado por `X-Balaio-Service-Token`, recebe um evento `eventoId` com `resultado: "aprovado" | "negado"`. É um **simulador interno** para homologação; a navegação do comprador nunca confirma pagamento. `POST /internal/v1/pedidos/{id}/decisao-mediacao` recebe `decisaoId` e `decisao: "cancelar_reembolsar" | "negar" | "manter_conversa"`. A decisão de reembolso também é simulada: não há cobrança nem devolução financeira. Antes de produção, comprador cancela em `PATCH /api/v1/pedidos/{id}/cancelar` e artesão recusa em `/recusar`; durante produção, o comprador precisa de mediação. `PATCH /api/v1/pedidos/{id}/estado` aceita `producao` para o artesão responsável.

`GET /api/v1/pedidos` lista somente os pedidos do comprador do JWT. Para o ateliê, `GET /api/v1/artesao/pedidos-pendentes` lista apenas pedidos `confirmado` com `id`, `pecaSlug`, `comprador`, `quando` e `valor`; `GET /api/v1/artesao/vendas` retorna pedidos pagos ainda ativos para as métricas e vendas. Ambas exigem JWT de artesão e filtram pelo `sub` validado, ignorando qualquer `artesao` informado na query. O nome do comprador aparece como rótulo genérico até existir contrato de consulta de perfil para este dado.

O endpoint interno de pagamento deve ser substituído por uma integração de provedor com validação de assinatura e conciliação quando houver contrato sandbox. O frete demonstrativo deve ser substituído por cotação vinculada ao endereço e à opção escolhida. Sem esses contratos não há pagamento, frete ou reembolso reais.

Para testar com PostgreSQL 18 via Testcontainers: `$env:COMMERCE_POSTGRES_TEST='true'; .\mvnw.cmd '-Djava.version=21' clean verify`.
