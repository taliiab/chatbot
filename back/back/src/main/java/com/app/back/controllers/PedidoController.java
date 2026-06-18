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
            double subtotal = dados.get("subtotal") != null ? Double.parseDouble(dados.get("subtotal").toString()) : 0.0;
            double total = dados.get("total") != null ? Double.parseDouble(dados.get("total").toString()) : subtotal;
            double custoFrete = dados.get("custo_frete") != null ? Double.parseDouble(dados.get("custo_frete").toString()) : 0.0;

            Object dataEntregaInput = dados.get("data_entrega");
            java.sql.Timestamp dataEntrega = null;

            if (dataEntregaInput != null && !dataEntregaInput.toString().trim().isEmpty()) {
                try {
                    String d = dataEntregaInput.toString().trim();
                    if (d.length() == 10) d += " 00:00:00";
                    dataEntrega = java.sql.Timestamp.valueOf(d);
                } catch (Exception e) {
                    System.err.println("Erro ao converter data: " + e.getMessage());
                }
            }

            String sqlPedido = "INSERT INTO pedidos (id, id_cliente, status_entrega, subtotal, total, data_criacao, data_entrega, custo_frete) VALUES (?, ?, 'Pendente', ?, ?, ?, ?, ?)";
            jdbcTemplate.update(sqlPedido, 
                idPedido, 
                idCliente, 
                subtotal, 
                total, 
                java.time.LocalDateTime.now(), 
                dataEntrega, 
                custoFrete
            );

            Map<String, Object> endereco = (Map<String, Object>) dados.get("endereco");
            String sqlEndereco = "INSERT INTO endereco_entrega (id_pedido, id_cliente, rua, numero, bairro, cep, complemento) VALUES (?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(sqlEndereco, idPedido, idCliente, endereco.get("rua"), endereco.get("numero"),
                    endereco.get("bairro"), endereco.get("cep"), endereco.get("complemento"));

            List<Map<String, Object>> itens = (List<Map<String, Object>>) dados.get("itens");
            String sqlItem = "INSERT INTO itens_pedido (id_pedido, id_produto, quantidade, preco_unitario, valor_item) VALUES (?, ?, ?, ?, ?)";
            for (Map<String, Object> item : itens) {
                jdbcTemplate.update(sqlItem, idPedido, 
                    Integer.parseInt(item.get("id_produto").toString()), 
                    Integer.parseInt(item.get("quantidade").toString()), 
                    Double.parseDouble(item.get("preco_unitario").toString()), 
                    Double.parseDouble(item.get("valor_item").toString()));
            }

            jdbcTemplate.update("INSERT INTO pagamentos (id_pedido, status_pagamento, metodo_pagamento, valor) VALUES (?, 'Pendente', ?, ?)",
                    idPedido, dados.get("metodo_pagamento"), total);

            return ResponseEntity.ok(Map.of("status", "sucesso", "mensagem", "Pedido realizado com sucesso!"));

        } catch (Exception e) {
            e.printStackTrace(); 
            return ResponseEntity.badRequest().body(Map.of("status", "erro", "mensagem", e.getMessage()));
        }
    }

    @PostMapping("/pedidos/confirmar-pagamento")
    public ResponseEntity<?> confirmarPagamento(@RequestParam String id) {
        String sql = "UPDATE pagamentos SET status_pagamento = 'Aprovado' WHERE id_pedido = ?";
        int updated = jdbcTemplate.update(sql, id);
        if (updated == 0) return ResponseEntity.badRequest().body("Pedido não encontrado");
        return ResponseEntity.ok(Map.of("status", "sucesso"));
    }

    @PostMapping("/pedidos/confirmar-entrega")
    public ResponseEntity<?> confirmarEntrega(@RequestParam String id) {
        String sql = "UPDATE pedidos SET status_entrega = 'Entregue' WHERE id = ?";
        int updated = jdbcTemplate.update(sql, id);
        if (updated == 0) return ResponseEntity.badRequest().body("Pedido não encontrado");
        return ResponseEntity.ok(Map.of("status", "sucesso"));
    }

}