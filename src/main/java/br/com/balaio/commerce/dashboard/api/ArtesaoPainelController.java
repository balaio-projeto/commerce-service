package br.com.balaio.commerce.dashboard.api;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.commerce.dashboard.application.port.in.RelatorioVendasUseCase;
import br.com.balaio.commerce.dashboard.application.port.in.ResumoPainelUseCase;
import br.com.balaio.commerce.shared.api.Envelope;
import br.com.balaio.commerce.shared.security.PapelJwt;

@RestController
public class ArtesaoPainelController {
    private final RelatorioVendasUseCase relatorio;
    private final ResumoPainelUseCase resumo;

    public ArtesaoPainelController(RelatorioVendasUseCase relatorio, ResumoPainelUseCase resumo) {
        this.relatorio = relatorio;
        this.resumo = resumo;
    }

    @GetMapping("/api/v1/artesao/vendas/relatorio")
    public ResponseEntity<Envelope> relatorio(@RequestParam(required = false) String periodo,
            @RequestParam(required = false) String de, @RequestParam(required = false) String ate,
            @AuthenticationPrincipal Jwt jwt) {
        UUID artesao = PapelJwt.exigir(jwt, "artesao");
        return semCache(new Envelope(relatorio.relatorio(artesao, periodo, de, ate), null));
    }

    @GetMapping("/api/v1/artesao/painel/resumo")
    public ResponseEntity<Envelope> resumo(@AuthenticationPrincipal Jwt jwt) {
        UUID artesao = PapelJwt.exigir(jwt, "artesao");
        return semCache(new Envelope(resumo.resumo(artesao, jwt.getTokenValue()), null));
    }

    private ResponseEntity<Envelope> semCache(Envelope corpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(corpo);
    }
}
