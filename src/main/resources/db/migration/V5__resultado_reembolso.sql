ALTER TABLE pagamento_intencoes ADD COLUMN valor_reembolsado NUMERIC(12,2) NOT NULL DEFAULT 0;
UPDATE pagamento_intencoes i SET valor_reembolsado = p.total
FROM pedidos p WHERE p.id = i.pedido_id AND (i.status_provedor = 'refunded'
    OR (i.status_provedor = 'approved' AND p.estado = 'reembolsado'));
