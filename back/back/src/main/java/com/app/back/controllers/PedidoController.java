package com.app.back.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @PostMapping("/pedidos/confirmar-pagamento")
    public ResponseEntity<?> confirmarPagamento(@RequestParam String id) {
        try {
            String sqlCheckStatus = "SELECT status_entrega FROM pedidos WHERE id = ?";
            String statusEntrega = jdbcTemplate.queryForObject(sqlCheckStatus, String.class, id);

            if ("Cancelado".equalsIgnoreCase(statusEntrega)) {
                return ResponseEntity.badRequest().body(Map.of("status", "erro", "mensagem", "Não é possível pagar um pedido cancelado."));
            }

            String sqlCheck = "SELECT COUNT(*) FROM pagamentos WHERE id_pedido = ?";
            Integer count = jdbcTemplate.queryForObject(sqlCheck, Integer.class, id);

            if (count != null && count > 0) {
                String sqlUpdate = "UPDATE pagamentos SET status_pagamento = 'Aprovado' WHERE id_pedido = ?";
                jdbcTemplate.update(sqlUpdate, id);
            } else {
                String sqlInsert = "INSERT INTO pagamentos (id_pedido, metodo_pagamento, status_pagamento) VALUES (?, 'Pix', 'Aprovado')";
                jdbcTemplate.update(sqlInsert, id);
            }

            return ResponseEntity.ok(Map.of("status", "sucesso", "mensagem", "Pagamento aprovado com sucesso."));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "erro",
                    "mensagem", "Erro no banco de dados ao processar pagamento. Verifique o ID do pedido."
            ));
        }
    }

    @PostMapping("/pedidos/confirmar-entrega")
    public ResponseEntity<?> confirmarEntrega(@RequestParam String id) {
        String sql = "UPDATE pedidos SET status_entrega = 'Entregue' WHERE id = ? AND status_entrega NOT IN ('Cancelado', 'Entregue')";
        int linhasAfetadas = jdbcTemplate.update(sql, id);

        if (linhasAfetadas == 0) {
            return ResponseEntity.badRequest().body(Map.of("status", "erro", "mensagem", "Este pedido está cancelado ou já foi entregue."));
        }
        return ResponseEntity.ok(Map.of("status", "sucesso", "mensagem", "Pedido marcado como entregue."));
    }
}