ALTER TABLE pedidos ADD COLUMN rastreio VARCHAR(40);
ALTER TABLE pedidos ADD COLUMN transportadora VARCHAR(60);
ALTER TABLE pedidos ADD COLUMN frete_cotacao JSONB;

CREATE OR REPLACE FUNCTION registrar_etapa_pedido() RETURNS trigger LANGUAGE plpgsql AS $$
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
                WHEN 'enviado' THEN 'O artesão despachou o pedido.'
                WHEN 'entregue' THEN 'O artesão confirmou a entrega.'
                ELSE 'O pedido mudou de situação.' END,
            CASE
                WHEN NEW.estado IN ('recusado', 'cancelado', 'reembolsado') THEN NEW.motivo
                WHEN NEW.estado = 'enviado' AND NEW.rastreio IS NOT NULL THEN
                    'Código de rastreio: ' || NEW.rastreio
                    || COALESCE(' (' || NEW.transportadora || ')', '')
                ELSE NULL END,
            now());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE pedido_emails (
    pedido_id UUID NOT NULL REFERENCES pedidos(id),
    evento VARCHAR(40) NOT NULL,
    destinatario VARCHAR(20) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'pendente',
    tentativas INTEGER NOT NULL DEFAULT 0,
    proxima_tentativa_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_erro VARCHAR(120),
    enviado_em TIMESTAMPTZ,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pedido_id, evento, destinatario),
    CHECK (estado IN ('pendente', 'enviado', 'falhou')),
    CHECK (destinatario IN ('comprador', 'artesao'))
);
CREATE INDEX idx_pedido_emails_pendentes ON pedido_emails(proxima_tentativa_em) WHERE estado = 'pendente';

CREATE FUNCTION enfileirar_email_pedido() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.estado IS DISTINCT FROM OLD.estado THEN
        IF NEW.estado = 'confirmado' THEN
            INSERT INTO pedido_emails(pedido_id, evento, destinatario)
            VALUES (NEW.id, 'pagamento_confirmado', 'comprador'), (NEW.id, 'pagamento_confirmado', 'artesao')
            ON CONFLICT DO NOTHING;
        ELSIF NEW.estado IN ('enviado', 'entregue') THEN
            INSERT INTO pedido_emails(pedido_id, evento, destinatario)
            VALUES (NEW.id, NEW.estado, 'comprador')
            ON CONFLICT DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_pedido_emails AFTER UPDATE OF estado ON pedidos
    FOR EACH ROW EXECUTE FUNCTION enfileirar_email_pedido();
