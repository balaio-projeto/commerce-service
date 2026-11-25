CREATE OR REPLACE FUNCTION enfileirar_email_pedido() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.estado IS DISTINCT FROM OLD.estado THEN
        IF NEW.estado = 'confirmado' THEN
            INSERT INTO pedido_emails(pedido_id, evento, destinatario)
            VALUES (NEW.id, 'pagamento_confirmado', 'comprador'), (NEW.id, 'pagamento_confirmado', 'artesao')
            ON CONFLICT DO NOTHING;
        ELSIF NEW.estado IN ('producao', 'enviado', 'entregue', 'recusado', 'reembolsado', 'falha_pagamento') THEN
            INSERT INTO pedido_emails(pedido_id, evento, destinatario)
            VALUES (NEW.id, NEW.estado, 'comprador')
            ON CONFLICT DO NOTHING;
        ELSIF NEW.estado = 'cancelado' THEN
            INSERT INTO pedido_emails(pedido_id, evento, destinatario)
            VALUES (NEW.id, 'cancelado', 'comprador')
            ON CONFLICT DO NOTHING;
            IF OLD.estado IN ('reembolso_pendente', 'confirmado', 'producao', 'enviado') THEN
                INSERT INTO pedido_emails(pedido_id, evento, destinatario)
                VALUES (NEW.id, 'cancelado', 'artesao')
                ON CONFLICT DO NOTHING;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
