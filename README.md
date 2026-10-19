# commerce-service

Pedidos e fluxo de compra; pagamentos e frete reais ficam fora do escopo inicial.

## Stack inicial

- Java 25
- Spring Boot 4.1.1
- Spring MVC, Spring Data JPA e PostgreSQL
- Flyway para migrações de schema
- Bean Validation
- Spring Boot Actuator

## Executar

Este serviço precisa de um PostgreSQL configurado. Forneça SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME e SPRING_DATASOURCE_PASSWORD no ambiente local; as credenciais e a configuração compartilhada do Compose pertencem ao repositório platform.

No Windows:
.\mvnw.cmd spring-boot:run

Testes:
.\mvnw.cmd test

Crie migrações SQL versionadas em src/main/resources/db/migration, por exemplo V1__create_initial_schema.sql.