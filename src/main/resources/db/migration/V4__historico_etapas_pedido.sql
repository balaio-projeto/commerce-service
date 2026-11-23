ALTER TABLE pedidos ADD COLUMN historico_incompleto BOOLEAN NOT NULL DEFAULT false;
UPDATE pedidos SET historico_incompleto = true;

CREATE TABLE pedido_etapas (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pedido_id UUID NOT NULL REFERENCES pedidos(id),
    estado VARCHAR(40) NOT NULL,
    titulo VARCHAR(120) NOT NULL,
    detalhe TEXT NOT NULL,
    nota TEXT,
    criada_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pedido_etapas_pedido ON pedido_etapas(pedido_id, id);

CREATE FUNCTION registrar_etapa_pedido() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.estado IS DISTINCT FROM OLD.estado THEN
        INSERT INTO pedido_etapas(pedido_id, estado, titulo, detalhe, nota, criada_em)
        VALUES (NEW.id, NEW.estado,
            CASE NEW.estado
                WHEN 'confirmado' THEN 'Pedido confirmado'
                WHEN 'producao' THEN 'Em produção'
                WHEN 'enviado' THEN 'Pedido enviado'
                WHEN 'entregue' THEN 'Pedido entregue'
                WHEN 'cancelado' THEN 'Pedido cancelado'
                WHEN 'recusado' THEN 'Pedido recusado'
                WHEN 'reembolsado' THEN 'Pedido reembolsado'
                ELSE 'Situação do pedido' END,
            CASE NEW.estado
                WHEN 'aguardando_reserva' THEN 'Aguardando reserva das peças.'
                WHEN 'aguardando_pagamento' THEN 'Aguardando pagamento.'
                WHEN 'confirmacao_pendente' THEN 'Aguardando confirmação das peças.'
                WHEN 'confirmado' THEN 'Pagamento e peças confirmados.'
                WHEN 'producao' THEN 'O artesão iniciou a produção.'
                ELSE 'O pedido mudou de situação.' END,
            CASE WHEN NEW.estado IN ('recusado', 'cancelado', 'reembolsado') THEN NEW.motivo ELSE NULL END,
            now());
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_pedido_etapas AFTER INSERT OR UPDATE OF estado ON pedidos
    FOR EACH ROW EXECUTE FUNCTION registrar_etapa_pedido();
