# Pendências de QA antes do push do commerce-service

Restrinja as alterações ao repositório `commerce-service`. Use a [matriz C1–C7](plano-arquitetura-hexagonal.md#3-matriz-de-preservação) como referência para preservar os contratos.

## P1 — Concluir o cancelamento quando a preferência nunca foi enviada

Em `CheckoutProService.preparar`, a intenção é gravada como `CRIANDO` antes da busca inicial de preferências. Se essa busca falhar, o fluxo termina sem enviar o `POST /checkout/preferences`. Depois que o provedor volta a responder, as buscas por `external_reference` retornam vazias. Em `processarOperacao`, a intenção continua `CRIANDO` e o retorno antecipado impede a avaliação do prazo de cancelamento. O teste em PostgreSQL confirmou que o pedido ainda estava em `cancelamento_pendente` após simular 17 minutos e executar os workers. [Log da reprodução](../target/qa-logs/revalidacao-qa-preferencia-nao-enviada.log)

1. Acrescente um teste em `PedidoWorkflowPostgresTest`. Use um pedido de peça única, faça a busca inicial falhar antes do POST e confirme que nenhuma preferência foi enviada. Cancele o pedido, faça o provedor voltar a responder sem preferência e simule o vencimento do prazo. Após reprocessar, espere `cancelado`, a causa original preservada e a liberação da mesma reserva uma única vez.
2. Ajuste `CheckoutProService.preparar` e `processarOperacao` para distinguir tentativa comprovadamente anterior ao envio de uma tentativa com resultado ambíguo. Uma busca vazia, por si só, não comprova que nenhum POST foi enviado. Considere também intenções `CRIANDO` já persistidas em V5 e a possibilidade de reinício entre a escrita local e a chamada remota.
3. Reuse a transição protegida de cancelamento. Mantenha a rede fora das transações e revalide estado, prazo e ausência de cobrança vinculada sob lock, na ordem pedido → intenção. Acione a liberação depois do commit. Preserve a recuperação por `external_reference` e a proteção contra repetir automaticamente um POST ambíguo.
4. Confirme que uma preferência recuperada ainda segue o fluxo financeiro existente, que uma aprovação concorrente ou tardia preserva o estorno e que falhas de rede mantêm o pedido recuperável.

## Antes do push

- Rode a suíte completa no diretório `commerce-service` com Java 25 e Docker. A última rodada passou com 53 testes. Exija zero falhas e zero ignorados, incluindo o novo cenário e as regressões de cancelamento concorrente, aprovação tardia e timeout ambíguo.
- Confira o diff, incluindo os novos arquivos de código e testes. Preserve os checksums das migrations V1–V5, as rotas e os contratos de integração. Confirme pelo Git que o conjunto a enviar está restrito ao commerce.

```powershell
$env:COMMERCE_POSTGRES_TEST = 'true'
.\mvnw.cmd -B clean verify
```

## Condições do ambiente de integração

O checkout com endereço salvo já passou com o perfil real em `de38261` e um override que injeta `PROFILE_SERVICE_URL=http://profile-service:8082` no commerce. Confirme que o ambiente de destino oferece a rota e define a variável. Se faltar algum pré-requisito, registre a pendência sem alterar `platform` ou `profile-service`. O aceite de pagamentos usou um simulador HTTPS. O [sandbox oficial do Mercado Pago](PI-54-checkout-pro-sandbox.md) ainda depende de credenciais de teste e URLs públicas.

## Registro da correção

O commerce grava `SEM_ENVIO` antes da busca inicial e muda para `CRIANDO` em uma transação curta antes do POST, com o pedido ainda elegível. Intenções `CRIANDO` legadas e tentativas interrompidas após esse commit continuam em recuperação por `external_reference`. A busca vazia sozinha mantém esses casos pendentes.

Para a intenção `SEM_ENVIO`, o cancelamento exige buscas de preferência e de pagamento bem-sucedidas e vazias. A transição reconfirma estado, prazo de 14 minutos e ausência de cobrança sob os locks de pedido e intenção. A liberação usa a reserva original e acontece após o commit. O worker deixa de buscar preferências de intenções `SEM_ENVIO` em pedidos já encerrados.

Antes da correção, os testes de falha anterior ao POST e de busca inicial concorrente reproduziram o cancelamento travado. Depois da correção, `.\mvnw.cmd -B clean verify` com Java 25 e PostgreSQL 18 passou com 56 testes, zero falhas e zero ignorados. Executaram-se 4 testes de sacola, 7 de HTTP e 27 de workflow. A comparação com o binário do aceite anterior confirmou que as mudanças de produção se concentram em `CheckoutProService`, `PagamentoRepository` e `PagamentoJdbcRepository`. Rotas, clientes de integração, configuração e migrations V1–V5 foram preservados.

O Compose local de `platform` (`1953e2c`) ainda precisa do override para injetar `PROFILE_SERVICE_URL` no commerce. O checkout local de `profile-service` (`8dce17c`) está numa revisão anterior à rota de endereços. O aceite anterior cobriu a revisão `de38261`, disponível em `origin/develop` local. A revisão e a configuração do ambiente de destino continuam por confirmar, assim como a homologação PI-54 no sandbox oficial.
