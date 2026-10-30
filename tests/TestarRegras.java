import br.com.minicompanheiros.Regras;

/** Testes locais das regras de trabalho e durabilidade (sem bibliotecas Minecraft). */
public final class TestarRegras {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(!Regras.podeTrabalhar(0, true), "Fome zerada nao pode trabalhar");
        check(!Regras.podeTrabalhar(20, false), "Sem ferramenta nao pode trabalhar");
        check(Regras.podeTrabalhar(1, true), "Fome positiva e ferramenta devem permitir");
        check(Regras.picaretaSuficiente(2, 2), "Picareta de ferro extrai diamante");
        check(!Regras.picaretaSuficiente(1, 2), "Pedra nao extrai diamante");
        check(!Regras.picaretaSuficiente(2, 3), "Ferro nao extrai detritos ancestrais");
        check(Regras.picaretaSuficiente(3, 3), "Diamante extrai detritos ancestrais");
        check(Regras.reduzirFome(1, 2) == 0, "Fome nunca negativa");
        check(Regras.reduzirFome(20, 1) == 19, "Consumo normal de fome");
        check(Regras.reduzirFome(12, -5) == 12, "Consumo negativo nao devolve comida");
        check(Regras.reduzirFome(25, 1) == 19, "Fome limitada em 20");
        System.out.println("OK: " + checks + " testes das regras Java passaram.");
    }
}
