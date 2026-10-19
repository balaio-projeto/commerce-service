# Commerce Service

Serviço dono da sacola, checkout e ciclo de vida dos pedidos do Balaio. A primeira entrega prevê pagamento e frete simulados; integrações reais ficam para depois.

## Estado atual

Este repositório contém somente o esqueleto Spring Boot e dependências para persistência, validação e monitoramento. Não existem endpoints, regras de pedido, entidades ou migrações de negócio implementados. As rotas são planejadas a partir da [Fake API do frontend](https://github.com/lucaschavessf2/Balaio/blob/main/Frontend/docs/api.md).

## Escopo e responsabilidades

- Manter sacola e favoritos do visitante ou comprador.
- Validar itens e preços do catálogo e calcular o total no checkout.
- Criar pedidos e controlar seus estados.
- Simular opções de frete e forma de pagamento, sem cobrança nem compra real de etiqueta.
- Permitir cancelamento pelo comprador e aceite/recusa/atualização pelo artesão, com autorização no backend.
- Não incluir venda de matéria-prima nesta entrega.
- Não integrar pagamento ou frete reais agora; se houver complexidade suficiente, podem virar serviços separados depois.

Peças e preços pertencem ao `catalog-service`. Conversas, avaliações e mediações pertencem ao `community-service`. Não acessar diretamente bancos de outros serviços.

## API planejada

Prefixo `/api/v1`; ainda sem controllers.

| Método e rota | Uso |
| --- | --- |
| `GET /estado`, `PUT /estado/sacola`, `PUT /estado/favoritos` | Consultar/atualizar sacola e favoritos |
| `GET /fretes` | Opções de entrega simuladas |
| `POST /checkout` | Criar pedido com itens, endereço, frete e meio de pagamento simulado |
| `GET /pedidos`, `GET /pedidos/{id}` | Consultar pedidos do comprador |
| `PATCH /pedidos/{id}/cancelar` | Cancelar pedido permitido pelo estado atual |
| `GET /artesao/pedidos-pendentes` | Consultar pedidos aguardando decisão do artesão |
| `PATCH /pedidos/{id}/estado`, `PATCH /pedidos/{id}/recusar` | Atualizar ou recusar pedido pelo artesão |

Estados previstos no contrato simulado: `confirmado → producao → enviado → entregue`, ou `recusado`/`cancelado`. Conversas e avaliações têm endpoints no serviço comunitário.

## Contrato com o frontend

A Fake API é referência de payloads e fluxos, não uma integração de pagamento ou logística. O backend deve preservar `/api/v1` e o envelope `{ "dados": ..., "erro": null }`; listagens paginadas também incluem `paginacao`.

## Stack e desenvolvimento

- Java 25, Maven Wrapper, Spring Boot 4.1.1.
- Spring MVC, Spring Data JPA, Bean Validation, PostgreSQL, Flyway e Actuator.
- Requer JDK 25 e PostgreSQL. Use a infraestrutura do repositório `platform` quando disponível; configure `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` com a base própria do serviço. Não versione credenciais.
- Migrações em `src/main/resources/db/migration`, por exemplo `V1__create_orders.sql`.
- O `application.yaml` atual só define o nome; datasource e migrações ainda não existem.

Windows: ` .\mvnw.cmd spring-boot:run`, ` .\mvnw.cmd test`, ` .\mvnw.cmd package`. Em Linux/macOS, use `./mvnw`.

Com Actuator, consulte `/actuator/health`. Os testes atuais só verificam contexto; implemente testes para total, transições de estado, isolamento por usuário e autorização.

## OpenAPI / Swagger UI

Ainda não está no `pom.xml`. Recomendação: adicionar OpenAPI e Swagger UI quando os primeiros endpoints forem criados, usando `org.springdoc:springdoc-openapi-starter-webmvc-ui` 3.x para Spring Boot 4 (versão estável consultada: 3.1.1). URLs esperadas após configurar: `/swagger-ui/index.html` e `/v3/api-docs`.

## Referências

- [Contrato da API do frontend](https://github.com/lucaschavessf2/Balaio/blob/main/Frontend/docs/api.md)
- [Fake API](https://github.com/lucaschavessf2/Balaio/tree/main/Frontend/fake-api)
- [springdoc-openapi](https://springdoc.org/)
