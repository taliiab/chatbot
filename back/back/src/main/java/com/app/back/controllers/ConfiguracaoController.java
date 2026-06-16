package com.app.back.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class ConfiguracaoController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/produtos")
    public ResponseEntity<List<Map<String, Object>>> listarProdutos() {
        String sql = "SELECT nome, preco FROM produtos";
        List<Map<String, Object>> produtos = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(produtos);
    }

    @GetMapping("/configuracoes")
    public ResponseEntity<List<Map<String, Object>>> listarConfiguracoes() {
        String sql = "SELECT chave, valor FROM configuracoes";
        List<Map<String, Object>> configs = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(configs);
    }

    @PostMapping("/configuracoes/salvar-tudo")
    public ResponseEntity<?> salvarTudo(@RequestBody Map<String, Object> dados) {
        double precoExtra = Double.parseDouble(dados.get("preco_extra").toString());
        double precoJumbo = Double.parseDouble(dados.get("preco_jumbo").toString());
        String qtdFreteGratis = dados.get("qtd_frete_gratis").toString();
        double valorFretePadrao = Double.parseDouble(dados.get("valor_frete_padrao").toString());

        jdbcTemplate.update("UPDATE produtos SET preco = ? WHERE nome = 'Extra' OR nome = 'Dúzia'", precoExtra);
        jdbcTemplate.update("UPDATE produtos SET preco = ? WHERE nome = 'Jumbo'", precoJumbo);
        jdbcTemplate.update("UPDATE configuracoes SET valor = ? WHERE chave = 'qtd_frete_gratis'", qtdFreteGratis);
        jdbcTemplate.update("UPDATE configuracoes SET valor = ? WHERE chave = 'valor_frete_padrao'", valorFretePadrao);

        return ResponseEntity.ok(Map.of("status", "sucesso", "mensagem", "Configurações atualizadas com sucesso!"));
    }
}