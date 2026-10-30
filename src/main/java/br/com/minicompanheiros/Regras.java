package br.com.minicompanheiros;

/** Regras de equilibrio sem dependencia de Forge, testadas separadamente. */
public final class Regras {
    public static boolean podeTrabalhar(int fome, boolean ferramentaCorreta) {
        return fome > 0 && ferramentaCorreta;
    }

    public static boolean picaretaSuficiente(int nivelAtual, int nivelNecessario) {
        return nivelAtual >= nivelNecessario;
    }

    public static int reduzirFome(int atual, int consumo) {
        return Math.max(0, Math.min(20, atual) - Math.max(0, consumo));
    }

    private Regras() { }
}
