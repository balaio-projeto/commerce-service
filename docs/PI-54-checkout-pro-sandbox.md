# PI-54 — roteiro manual Checkout Pro sandbox

## Preparação

1. Crie uma aplicação Checkout Pro de teste no Mercado Pago e use contas distintas de vendedor e comprador, ambas do Brasil. Guarde token, segredo webhook, usuários e senhas somente no gerenciador de segredos ou no `.env` local ignorado pelo Git. Não use a conta de produção.
2. Disponibilize gateway e frontend em URLs públicas HTTPS de homologação, por túnel de desenvolvimento quando necessário. Configure no Mercado Pago o webhook `https://<gateway>/api/v1/pagamentos/mercado-pago/notificacoes` para `payment`. Configure `MERCADOPAGO_ENVIRONMENT=sandbox`, `MERCADOPAGO_ACCESS_TOKEN`, `MERCADOPAGO_WEBHOOK_SECRET`, `MERCADOPAGO_NOTIFICATION_URL` e `FRONTEND_RETURN_URL`. A base da API permanece `https://api.mercadopago.com` com credencial de teste.
3. Execute as migrations do commerce sem alterar V1. Inicie catálogo, commerce, gateway e frontend. Verifique healthchecks. O frontend deve apontar para o gateway de homologação.

## Fluxo

1. Com comprador de teste, adicione peça única e finalize checkout. Confira HTTP 202, ID do pedido e `checkoutUrl` com host `sandbox.mercadopago.com`. Repita o POST com a mesma `Idempotency-Key` e payload: ID, preferência e URL devem permanecer iguais. Troque o payload mantendo a chave: deve retornar 409.
2. No checkout hospedado, entre com a conta compradora de teste e use os métodos/cenários de teste oficiais para aprovação, recusa e pagamento pendente. Nenhum dado de cartão passa pelo Balaio. O retorno do navegador deve mostrar apenas o estado persistido; sem webhook e consulta server-side, o pedido permanece pendente.
3. Confirme no banco do commerce a intenção, a notificação recebida, o payment ID e o estado. No catálogo, confirme a reserva da peça única; aprovado deve levar a `CONSUMIDA`. Reenvie o mesmo webhook assinado: uma notificação lógica e nenhuma segunda transição.
4. Para recusa/cancelamento, confirme `encerramento_pendente` até liberação efetiva no catálogo. Para pagamento aprovado, solicite cancelamento antes da produção ou decisão de mediação `cancelar_reembolsar`; o pedido deve ficar em `reembolso_pendente` até consulta do status `refunded` ou valor reembolsado integral ao Mercado Pago. Se o sandbox recusar a operação, registre o estado pendente e a resposta do provedor sem declarar reembolso concluído.
5. Interrompa temporariamente a consulta ao provedor e o catálogo, depois restabeleça. Confirme que o worker reprocessa notificações e reservas pendentes sem criar outra preferência ou liberar duas vezes a peça.

Não execute este roteiro com credenciais, contas, endpoints ou capturas de produção. O Checkout Pro desta entrega não oferece retenção ou repasse automático ao artesão.

Referências oficiais: [contas de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts), [preferências](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/create-preference/post), [webhooks](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-content/notifications/webhooks), [reembolso e cancelamento](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/refunds-and-cancellations).
