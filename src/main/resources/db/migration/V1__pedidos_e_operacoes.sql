CREATE TABLE pedidos (
    id UUID PRIMARY KEY,
    comprador_id UUID NOT NULL,
    artesao_id UUID NOT NULL,
    estado VARCHAR(40) NOT NULL,
    encerramento VARCHAR(40),
    motivo TEXT,
    chave_checkout VARCHAR(160) NOT NULL,
    fingerprint_checkout VARCHAR(64) NOT NULL,
    endereco JSONB NOT NULL,
    frete_id VARCHAR(80) NOT NULL,
    frete NUMERIC(12,2) NOT NULL,
    total NUMERIC(12,2) NOT NULL,
    meio VARCHAR(20) NOT NULL,
    tentativas INTEGER NOT NULL DEFAULT 0,
    ultimo_erro TEXT,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(comprador_id, chave_checkout)
);
CREATE TABLE pedido_itens (
    pedido_id UUID NOT NULL REFERENCES pedidos(id),
    slug VARCHAR(160) NOT NULL,
    quantidade INTEGER NOT NULL CHECK(quantidade BETWEEN 1 AND 3),
    preco NUMERIC(12,2) NOT NULL,
    disponibilidade VARCHAR(32) NOT NULL,
    reserva_id UUID,
    reserva_estado VARCHAR(20),
    PRIMARY KEY(pedido_id, slug)
);
CREATE INDEX idx_pedidos_pendentes ON pedidos(estado, atualizado_em);
CREATE TABLE eventos_pagamento (
    id VARCHAR(160) PRIMARY KEY,
    pedido_id UUID NOT NULL REFERENCES pedidos(id),
    resultado VARCHAR(20) NOT NULL,
    recebido_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE decisoes_mediacao (
    id UUID PRIMARY KEY,
    pedido_id UUID NOT NULL REFERENCES pedidos(id),
    decisao VARCHAR(40) NOT NULL,
    recebida_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
