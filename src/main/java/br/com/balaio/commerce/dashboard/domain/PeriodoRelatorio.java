package br.com.balaio.commerce.dashboard.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public record PeriodoRelatorio(LocalDate de, LocalDate ate, String preset) {
    public static final ZoneId FUSO = ZoneId.of("America/Recife");
    public static final int MAXIMO_DIAS = 366;
    private static final Map<String, Integer> DIAS_POR_PRESET = Map.of("7d", 7, "30d", 30, "90d", 90);

    public static PeriodoRelatorio resolver(String preset, String de, String ate, Clock relogio) {
        LocalDate hoje = LocalDate.now(relogio.withZone(FUSO));
        boolean personalizado = (de != null && !de.isBlank()) || (ate != null && !ate.isBlank());
        if (personalizado) {
            if (preset != null && !preset.isBlank() && !"personalizado".equals(preset)) throw invalido();
            LocalDate inicio = data(de);
            LocalDate fim = data(ate);
            if (fim.isBefore(inicio) || ChronoUnit.DAYS.between(inicio, fim) + 1 > MAXIMO_DIAS) throw invalido();
            return new PeriodoRelatorio(inicio, fim, "personalizado");
        }
        String escolhido = preset == null || preset.isBlank() ? "30d" : preset;
        if ("mes".equals(escolhido)) return new PeriodoRelatorio(hoje.withDayOfMonth(1), hoje, "mes");
        Integer dias = DIAS_POR_PRESET.get(escolhido);
        if (dias == null) throw invalido();
        return new PeriodoRelatorio(hoje.minusDays(dias - 1L), hoje, escolhido);
    }

    public OffsetDateTime inicio() {
        return de.atStartOfDay(FUSO).toOffsetDateTime();
    }

    public OffsetDateTime fimExclusivo() {
        return ate.plusDays(1).atStartOfDay(FUSO).toOffsetDateTime();
    }

    public long dias() {
        return ChronoUnit.DAYS.between(de, ate) + 1;
    }

    private static LocalDate data(String texto) {
        if (texto == null || texto.isBlank()) throw invalido();
        try {
            return LocalDate.parse(texto.trim());
        } catch (DateTimeParseException formatoInvalido) {
            throw invalido();
        }
    }

    private static PeriodoInvalido invalido() {
        return new PeriodoInvalido();
    }
}
