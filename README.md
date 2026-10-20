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

Crie migrações SQL versionadas em `src/main/resources/db/migration` quando o primeiro modelo persistente for implementado. As rotas públicas planejadas estão mapeadas no gateway; cada endpoint continua a ser implementado neste serviço conforme os contratos do frontend e da Fake API.
