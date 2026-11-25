CREATE TABLE cupons (
    id UUID PRIMARY KEY,
    codigo VARCHAR(40),
    descricao VARCHAR(200) NOT NULL,
    tipo VARCHAR(20) NOT NULL,
    valor NUMERIC(12,2) NOT NULL DEFAULT 0,
    desconto_maximo NUMERIC(12,2),
    pedido_minimo NUMERIC(12,2) NOT NULL DEFAULT 0,
    inicio TIMESTAMPTZ,
    fim TIMESTAMPTZ,
    limite_usos INTEGER,
    limite_por_comprador INTEGER,
    usos INTEGER NOT NULL DEFAULT 0,
    ativo BOOLEAN NOT NULL DEFAULT true,
    criado_por UUID,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cupons_tipo CHECK (tipo IN ('percentual', 'valor_fixo', 'frete_gratis')),
    CONSTRAINT ck_cupons_codigo CHECK (codigo IS NULL OR codigo ~ '^[A-Z0-9][A-Z0-9_-]{2,39}$'),
    CONSTRAINT ck_cupons_percentual CHECK (tipo <> 'percentual' OR (valor > 0 AND valor <= 100)),
    CONSTRAINT ck_cupons_valor_fixo CHECK (tipo <> 'valor_fixo' OR valor > 0),
    CONSTRAINT ck_cupons_desconto_maximo CHECK (desconto_maximo IS NULL OR desconto_maximo > 0),
    CONSTRAINT ck_cupons_pedido_minimo CHECK (pedido_minimo >= 0),
    CONSTRAINT ck_cupons_validade CHECK (inicio IS NULL OR fim IS NULL OR fim > inicio),
    CONSTRAINT ck_cupons_limite_usos CHECK (limite_usos IS NULL OR limite_usos > 0),
    CONSTRAINT ck_cupons_limite_comprador CHECK (limite_por_comprador IS NULL OR limite_por_comprador > 0),
    CONSTRAINT ck_cupons_usos CHECK (usos >= 0 AND (limite_usos IS NULL OR usos <= limite_usos))
);
CREATE UNIQUE INDEX uk_cupons_codigo ON cupons (codigo) WHERE codigo IS NOT NULL;
CREATE INDEX idx_cupons_automaticos ON cupons (criado_em) WHERE codigo IS NULL AND ativo;

ALTER TABLE pedidos ADD COLUMN cupom_id UUID REFERENCES cupons(id);
ALTER TABLE pedidos ADD COLUMN cupom_codigo VARCHAR(40);
ALTER TABLE pedidos ADD COLUMN cupom_snapshot JSONB;
ALTER TABLE pedidos ADD COLUMN desconto_produtos NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE pedidos ADD COLUMN desconto_frete NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE pedidos ADD CONSTRAINT ck_pedidos_descontos
    CHECK (desconto_produtos >= 0 AND desconto_frete >= 0 AND desconto_frete <= frete);

CREATE TABLE cupom_usos (
    pedido_id UUID PRIMARY KEY REFERENCES pedidos(id),
    cupom_id UUID NOT NULL REFERENCES cupons(id),
    comprador_id UUID NOT NULL,
    desconto NUMERIC(12,2) NOT NULL CHECK (desconto >= 0),
    estado VARCHAR(20) NOT NULL DEFAULT 'consumido',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    liberado_em TIMESTAMPTZ,
    CONSTRAINT ck_cupom_usos_estado CHECK (estado IN ('consumido', 'liberado'))
);
CREATE INDEX idx_cupom_usos_comprador ON cupom_usos (cupom_id, comprador_id) WHERE estado = 'consumido';

CREATE FUNCTION liberar_cupom_do_pedido() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    cupom_liberado UUID;
BEGIN
    IF NEW.estado IS DISTINCT FROM OLD.estado
            AND NEW.estado IN ('cancelado', 'recusado', 'reembolsado', 'falha_reserva', 'falha_pagamento') THEN
        UPDATE cupom_usos SET estado = 'liberado', liberado_em = now()
        WHERE pedido_id = NEW.id AND estado = 'consumido'
        RETURNING cupom_id INTO cupom_liberado;
        IF cupom_liberado IS NOT NULL THEN
            UPDATE cupons SET usos = usos - 1, atualizado_em = now() WHERE id = cupom_liberado AND usos > 0;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_liberar_cupom_do_pedido AFTER UPDATE OF estado ON pedidos
    FOR EACH ROW EXECUTE FUNCTION liberar_cupom_do_pedido();
