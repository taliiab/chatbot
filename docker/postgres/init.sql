CREATE TABLE IF NOT EXISTS clientes (
    id_whatsapp TEXT PRIMARY KEY,
    nome TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS produtos (
    id INTEGER PRIMARY KEY,
    nome TEXT NOT NULL UNIQUE
);

INSERT INTO produtos (id, nome)
VALUES
    (1, 'Dúzia Extra'),
    (2, 'Jumbo')
ON CONFLICT (id) DO UPDATE
SET nome = EXCLUDED.nome;

CREATE TABLE IF NOT EXISTS pedidos (
    id TEXT PRIMARY KEY,
    id_cliente TEXT NOT NULL REFERENCES clientes (id_whatsapp),
    data_entrega DATE,
    status_entrega TEXT NOT NULL DEFAULT 'Pendente',
    custo_frete NUMERIC(10,2) NOT NULL DEFAULT 0,
    subtotal NUMERIC(10,2) NOT NULL DEFAULT 0,
    total NUMERIC(10,2) NOT NULL DEFAULT 0,
    data_criacao TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS itens_pedido (
    id_pedido TEXT NOT NULL REFERENCES pedidos (id) ON DELETE CASCADE,
    id_produto INTEGER NOT NULL REFERENCES produtos (id),
    quantidade INTEGER NOT NULL,
    preco_unitario NUMERIC(10,2) NOT NULL,
    valor_item NUMERIC(10,2) NOT NULL,
    PRIMARY KEY (id_pedido, id_produto)
);

CREATE TABLE IF NOT EXISTS endereco_entrega (
    id_pedido TEXT PRIMARY KEY REFERENCES pedidos (id) ON DELETE CASCADE,
    id_cliente TEXT NOT NULL REFERENCES clientes (id_whatsapp),
    rua TEXT NOT NULL,
    numero TEXT NOT NULL,
    bairro TEXT NOT NULL,
    cep TEXT,
    complemento TEXT
);

CREATE TABLE IF NOT EXISTS pagamentos (
    id_pedido TEXT PRIMARY KEY REFERENCES pedidos (id) ON DELETE CASCADE,
    metodo_pagamento TEXT NOT NULL,
    valor NUMERIC(10,2) NOT NULL,
    status_pagamento TEXT NOT NULL DEFAULT 'Pendente'
);
