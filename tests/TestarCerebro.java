import br.com.minicompanheiros.Cerebro;

public final class TestarCerebro {
    private static int n;
    private static void ok(boolean b, String message) { n++; if (!b) throw new AssertionError(message); }
    public static void main(String[] args) {
        ok(Cerebro.decidir(4f, 16f, 20, false, false, false, true) == Cerebro.DESCANSAR, "vida baixa");
        ok(Cerebro.decidir(16f, 16f, 20, true, false, false, true) == Cerebro.DESCANSAR, "perigo");
        ok(Cerebro.decidir(16f, 16f, 20, false, false, true, true) == Cerebro.GUARDAR_ITENS, "mochila cheia");
        ok(Cerebro.decidir(16f, 16f, 5, false, true, false, true) == Cerebro.COMER, "comer");
        ok(Cerebro.decidir(16f, 16f, 0, false, false, false, true) == Cerebro.VOLTAR, "sem comida");
        ok(Cerebro.decidir(16f, 16f, 9, false, false, false, false) == Cerebro.TROCAR_FERRAMENTA, "sem ferramenta");
        ok(Cerebro.decidir(16f, 16f, 9, false, false, false, true) == Cerebro.TRABALHAR, "trabalhar");
        ok(!Cerebro.podePassar(true, true, true), "evitar perigo");
        ok(!Cerebro.podePassar(false, true, false), "nao pisar no vazio");
        ok(Cerebro.podePassar(true, true, false), "piso seguro");
        ok(Cerebro.recuperado(12f, 16f, false), "recuperacao");
        ok(!Cerebro.recuperado(10f, 16f, false), "ainda ferido");
        ok(!Cerebro.recuperado(16f, 16f, true), "ainda em fogo");
        boolean[] tools = {false, true, true, false, false};
        int[] blocked = new int[5], pref = {0, 3, 1, 0, 0}, success = new int[5];
        ok(Cerebro.escolherTarefa(0, tools, blocked, 100, 0, pref, success) == 0, "nada autorizado");
        ok(Cerebro.escolherTarefa(1, tools, blocked, 100, 0, pref, success) == 1, "uma autorizacao");
        ok(Cerebro.escolherTarefa(3, tools, blocked, 100, 0, pref, success) == 1, "prioriza selecao");
        blocked[1] = 500;
        ok(Cerebro.escolherTarefa(3, tools, blocked, 100, 0, pref, success) == 2, "respeita bloqueio temporario");
        ok(Cerebro.escolherTarefa(1, tools, blocked, 100, 0, pref, success) == 0, "sem tarefa liberada disponivel");
        blocked[1] = 0;
        ok(Cerebro.escolherTarefa(3, tools, blocked, 100, 1, pref, success) == 2, "evita repetir tarefa recente");
        // Testes de combinacoes de todas as autorizacoes, ferramentas e ultima tarefa.
        for (int permissionMask = 0; permissionMask < 16; permissionMask++) {
            for (int toolMask = 0; toolMask < 16; toolMask++) {
                boolean[] available = new boolean[5];
                for (int job = 1; job <= 4; job++) {
                    available[job] = (toolMask & (1 << (job - 1))) != 0;
                }
                for (int lastJob = 0; lastJob <= 4; lastJob++) {
                    int candidate = Cerebro.escolherTarefa(permissionMask, available, blocked,
                            100, lastJob, pref, success);
                    ok(candidate >= 0 && candidate <= 4, "categoria fora do intervalo");
                    ok(candidate == 0 || (permissionMask & (1 << (candidate - 1))) != 0,
                            "IA nunca pode usar categoria sem autorizacao");
                    ok(candidate == 0 || available[candidate],
                            "IA nunca pode usar categoria sem ferramenta");
                }
            }
        }
        System.out.println("OK: " + n + " testes de IA local passaram.");
    }
}
