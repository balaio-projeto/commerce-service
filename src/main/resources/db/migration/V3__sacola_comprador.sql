CREATE TABLE sacola_itens (
    comprador_id UUID NOT NULL,
    slug VARCHAR(160) NOT NULL,
    quantidade INTEGER NOT NULL CHECK (quantidade BETWEEN 1 AND 3),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comprador_id, slug)
);
