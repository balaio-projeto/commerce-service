package br.com.balaio.commerce.dashboard.domain;

public class PeriodoInvalido extends RuntimeException {
    public static final String CODIGO = "PERIODO_INVALIDO";

    public PeriodoInvalido() {
        super("Use periodo=7d, 30d, 90d ou mes, ou informe de e ate (AAAA-MM-DD) com até 366 dias.");
    }

    public String codigo() {
        return CODIGO;
    }
}
