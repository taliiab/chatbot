package com.app.back.controllers;

import com.app.back.models.Usuario;
import com.app.back.repositories.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> dados) {
        String email = dados.get("email") != null ? dados.get("email").trim() : "";
        String senha = dados.get("senha") != null ? dados.get("senha").trim() : "";

        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);

        if (usuarioOpt.isPresent()) {
            Usuario usuario = usuarioOpt.get();
            
            if (usuario.getSenha().trim().equals(senha)) {
                Map<String, Object> resposta = new HashMap<>();
                resposta.put("status", "sucesso");
                resposta.put("nome", usuario.getNome());
                resposta.put("email", usuario.getEmail());
                return ResponseEntity.ok(resposta);
            }
        } else {
            System.out.println("DEBUG LOGIN: Usuário com o e-mail [" + email + "] NÃO foi encontrado no banco.");
        }

        Map<String, String> erro = new HashMap<>();
        erro.put("mensagem", "E-mail ou senha incorretos.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(erro);
    }
}