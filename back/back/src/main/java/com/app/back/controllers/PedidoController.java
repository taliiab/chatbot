package com.app.back.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Transactional
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class PedidoController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/pedidos")
    public ResponseEntity<List<Map<String, Object>>> listarPedidos(
            @RequestParam String dataDe,
            @RequestParam String dataAte,
            @RequestParam List<String> status,
            @RequestParam List<String> pagamento) {

        String placeholdersStatus = String.join(",", status.stream().map(s -> "?").toArray(String[]::new));
        String placeholdersPagamento = String.join(",", pagamento.stream().map(p -> "?").toArray(String[]::new));

        String sql = "SELECT p.id AS id, " +
                "       p.id_cliente AS id_cliente, " +
                "       p.status_entrega AS status_entrega, " +
                "       COALESCE(SUM(i.quantidade), 0) AS quantidade, " +
                "       pag.metodo_pagamento AS metodo_pagamento, " +
                "       p.subtotal AS subtotal, " +
                "       p.custo_frete AS custo_frete, " +
                "       p.total AS total, " +
                "       COALESCE(pag.status_pagamento, 'Pendente') AS status_pagamento, " +
                "       c.nome AS nome, " +
                "       e.rua AS rua, " +
                "       e.numero AS numero, " +
                "       e.complemento AS complemento, " +
                "       e.bairro AS bairro " +
                "FROM pedidos p " +
                "LEFT JOIN clientes c ON p.id_cliente = c.id_whatsapp " +
                "LEFT JOIN endereco_entrega e ON p.id = e.id_pedido " +
                "LEFT JOIN pagamentos pag ON p.id = pag.id_pedido " +
                "LEFT JOIN itens_pedido i ON p.id = i.id_pedido " +
                "WHERE ((p.data_entrega BETWEEN ? AND ?) OR (p.data_entrega IS NULL AND p.data_criacao BETWEEN ? AND ?)) " +
                "  AND p.status_entrega IN (" + placeholdersStatus + ") " +
                "  AND (pag.status_pagamento IN (" + placeholdersPagamento + ") OR pag.status_pagamento IS NULL) " +
                "GROUP BY p.id, pag.metodo_pagamento, pag.status_pagamento, c.nome, e.rua, e.numero, e.complemento, e.bairro";

        List<Object> params = new ArrayList<>();

        params.add(java.sql.Timestamp.valueOf(dataDe + " 00:00:00"));
        params.add(java.sql.Timestamp.valueOf(dataAte + " 23:59:59"));

        params.add(java.sql.Timestamp.valueOf(dataDe + " 00:00:00"));
        params.add(java.sql.Timestamp.valueOf(dataAte + " 23:59:59"));

        params.addAll(status);
        params.addAll(pagamento);

        List<Map<String, Object>> pedidos = jdbcTemplate.queryForList(sql, params.toArray());
        return ResponseEntity.ok(pedidos);
    }

    @PostMapping(value = "/pedidos/atualizar-status-entrega", consumes = "application/json")
    public ResponseEntity<?> atualizarStatusEntrega(@RequestBody List<String> idsPedidos) {
        if (idsPedidos == null || idsPedidos.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "erro", "mensagem", "Nenhum ID de pedido enviado."));
        }

        String placeholders = String.join(",", idsPedidos.stream().map(id -> "?").toArray(String[]::new));

        String sql = "UPDATE pedidos SET status_entrega = 'Em Processo de Entrega' " +
                "WHERE id IN (" + placeholders + ") AND status_entrega = 'Pendente'";

        jdbcTemplate.update(sql, idsPedidos.toArray());

        return ResponseEntity.ok(Map.of(
                "status", "sucesso",
                "mensagem", "Pedidos atualizados para 'Em Processo de Entrega' com sucesso!"
        ));
    }

    @PostMapping("/pedidos/cancelar")
    public ResponseEntity<?> cancelarPedido(@RequestParam String id) {
        String sql = "UPDATE pedidos SET status_entrega = 'Cancelado' WHERE id = ? AND status_entrega = 'Pendente'";
        int linhasAfetadas = jdbcTemplate.update(sql, id);

        if (linhasAfetadas == 0) {
            return ResponseEntity.badRequest().body(Map.of("status", "erro", "mensagem", "O pedido não pode ser cancelado no status atual."));
        }
        return ResponseEntity.ok(Map.of("status", "sucesso", "mensagem", "Pedido cancelado com sucesso."));
    }

    @PostMapping("/pedidos/cadastrar")
    @Transactional
    public ResponseEntity<?> cadastrarPedidoCompleto(@RequestBody Map<String, Object> dados) {
        try {
            Map<String, Object> cliente = (Map<String, Object>) dados.get("cliente");
            String idCliente = (String) cliente.get("id_whatsapp");
            String sqlCliente = "INSERT INTO clientes (id_whatsapp, nome) VALUES (?, ?) ON CONFLICT (id_whatsapp) DO UPDATE SET nome = EXCLUDED.nome";
            jdbcTemplate.update(sqlCliente, idCliente, cliente.get("nome"));

            String idPedido = dados.get("id_pedido").toString();
            Object subtotalObj = dados.get("subtotal");
            double subtotal = subtotalObj != null ? Double.parseDouble(subtotalObj.toString()) : 0.0;
            
            Object totalObj = dados.get("total") != null ? dados.get("total") : dados.get("subtotal");
            double total = totalObj != null ? Double.parseDouble(totalObj.toString()) : subtotal;

            String sqlPedido = "INSERT INTO pedidos (id, id_cliente, status_entrega, subtotal, total) VALUES (?, ?, 'Pendente', ?, ?)";
            jdbcTemplate.update(sqlPedido, idPedido, idCliente, subtotal, total);

            Map<String, Object> endereco = (Map<String, Object>) dados.get("endereco");
            String sqlEndereco = "INSERT INTO endereco_entrega (id_pedido, id_cliente, rua, numero, bairro, cep, complemento) VALUES (?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(sqlEndereco, idPedido, idCliente, endereco.get("rua"), endereco.get("numero"),
                    endereco.get("bairro"), endereco.get("cep"), endereco.get("complemento"));

            List<Map<String, Object>> itens = (List<Map<String, Object>>) dados.get("itens");
            String sqlItem = "INSERT INTO itens_pedido (id_pedido, id_produto, quantidade, preco_unitario, valor_item) VALUES (?, ?, ?, ?, ?)";
            for (Map<String, Object> item : itens) {
                int idProduto = Integer.parseInt(item.get("id_produto").toString());
                int quantidade = Integer.parseInt(item.get("quantidade").toString());
                double precoUnitario = Double.parseDouble(item.get("preco_unitario").toString());
                double valorItem = Double.parseDouble(item.get("valor_item").toString());

                jdbcTemplate.update(sqlItem, idPedido, idProduto, quantidade, precoUnitario, valorItem);
            }

            jdbcTemplate.update("INSERT INTO pagamentos (id_pedido, status_pagamento, metodo_pagamento, valor) VALUES (?, 'Pendente', ?, ?)",
                    idPedido, dados.get("metodo_pagamento"), total);

            return ResponseEntity.ok(Map.of("status", "sucesso", "mensagem", "Pedido realizado com sucesso!"));

        } catch (Exception e) {
            e.printStackTrace(); 
            return ResponseEntity.badRequest().body(Map.of("status", "erro", "mensagem", e.getMessage()));
        }
    }


}