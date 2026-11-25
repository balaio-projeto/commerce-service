package br.com.balaio.commerce.promotion.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.promotion.application.CupomInvalido;
import br.com.balaio.commerce.promotion.application.port.in.AdministrarCuponsUseCase;
import br.com.balaio.commerce.promotion.domain.DadosCupom;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.PapelJwt;

@RestController
public class CuponsAdminController {
    private static final String PAPEL_ADMIN = "admin";
    private final AdministrarCuponsUseCase cupons;

    public CuponsAdminController(AdministrarCuponsUseCase cupons) {
        this.cupons = cupons;
    }

    public record Situacao(Boolean ativo) { }

    @GetMapping("/api/v1/admin/cupons")
    public Envelope listar(@AuthenticationPrincipal Jwt jwt) {
        PapelJwt.exigir(jwt, PAPEL_ADMIN);
        return new Envelope(cupons.listar().stream().map(CupomResponse::de).toList(), null);
    }

    @GetMapping("/api/v1/admin/cupons/{id}")
    public Envelope consultar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        PapelJwt.exigir(jwt, PAPEL_ADMIN);
        return new Envelope(CupomResponse.de(cupons.consultar(id)), null);
    }

    @PostMapping("/api/v1/admin/cupons")
    public ResponseEntity<Envelope> criar(@RequestBody DadosCupom dados, @AuthenticationPrincipal Jwt jwt) {
        UUID administrador = PapelJwt.exigir(jwt, PAPEL_ADMIN);
        return ResponseEntity.status(201).body(new Envelope(CupomResponse.de(cupons.criar(dados, administrador)), null));
    }

    @PutMapping("/api/v1/admin/cupons/{id}")
    public Envelope editar(@PathVariable UUID id, @RequestBody DadosCupom dados, @AuthenticationPrincipal Jwt jwt) {
        PapelJwt.exigir(jwt, PAPEL_ADMIN);
        return new Envelope(CupomResponse.de(cupons.editar(id, dados)), null);
    }

    @PatchMapping("/api/v1/admin/cupons/{id}/situacao")
    public Envelope situacao(@PathVariable UUID id, @RequestBody Situacao situacao, @AuthenticationPrincipal Jwt jwt) {
        PapelJwt.exigir(jwt, PAPEL_ADMIN);
        if (situacao == null || situacao.ativo() == null)
            throw new CupomInvalido(Map.of("ativo", "Informe se o cupom fica ativo."));
        return new Envelope(CupomResponse.de(cupons.definirAtivo(id, situacao.ativo())), null);
    }
}
