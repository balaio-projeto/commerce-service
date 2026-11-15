CREATE TABLE pagamento_intencoes (
    pedido_id UUID PRIMARY KEY REFERENCES pedidos(id),
    estado VARCHAR(32) NOT NULL,
    preferencia_id VARCHAR(160) UNIQUE,
    checkout_url TEXT,
    pagamento_id VARCHAR(80) UNIQUE,
    status_provedor VARCHAR(40),
    erro VARCHAR(80),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE pagamento_notificacoes (
    request_id VARCHAR(160) NOT NULL,
    pagamento_id VARCHAR(80) NOT NULL,
    processado_em TIMESTAMPTZ,
    tentativas INTEGER NOT NULL DEFAULT 0,
    proxima_tentativa_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_erro VARCHAR(80),
    recebido_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, pagamento_id)
);
CREATE INDEX idx_pagamento_notificacoes_pendentes ON pagamento_notificacoes(recebido_em)
    WHERE processado_em IS NULL;
ALTER TABLE pedidos ADD COLUMN processamento_ate TIMESTAMPTZ;

