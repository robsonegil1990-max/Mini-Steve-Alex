package br.com.minicompanheiros;

/**
 * IA local e deterministica: regras de sobrevivencia, escolha entre tarefas autorizadas
 * e memoria de preferencia. Nao acessa internet, chatbots ou modelos de linguagem.
 * Esta classe nao depende do Minecraft e possui testes unitarios.
 */
public final class Cerebro {
    public static final int TRABALHAR = 0;
    public static final int COMER = 1;
    public static final int VOLTAR = 2;
    public static final int DESCANSAR = 3;
    public static final int GUARDAR_ITENS = 4;
    public static final int TROCAR_FERRAMENTA = 5;

    public static int decidir(float vida, float vidaMaxima, int fome, boolean emPerigo,
                              boolean haComida, boolean mochilaCheia, boolean temFerramenta) {
        if (emPerigo || vida <= vidaMaxima * 0.32F) return DESCANSAR;
        if (mochilaCheia) return GUARDAR_ITENS;
        if (fome <= 6 && haComida) return COMER;
        if (fome == 0) return VOLTAR;
        if (!temFerramenta) return TROCAR_FERRAMENTA;
        return TRABALHAR;
    }

    /** So escolhe categorias liberadas pelo jogador e com ferramenta disponivel. */
    public static int escolherTarefa(int autorizadas, boolean[] ferramentas, int[] bloqueadasAte,
                                    int agora, int ultimaTarefa, int[] preferencias, int[] exitos) {
        int escolhida = 0;
        int melhor = Integer.MIN_VALUE;
        for (int tarefa = 1; tarefa <= 4; tarefa++) {
            if ((autorizadas & (1 << (tarefa - 1))) == 0) continue;
            if (tarefa >= ferramentas.length || !ferramentas[tarefa]) continue;
            if (tarefa >= bloqueadasAte.length || agora < bloqueadasAte[tarefa]) continue;
            int preferencia = tarefa < preferencias.length ? Math.max(0, preferencias[tarefa]) : 0;
            int exito = tarefa < exitos.length ? Math.max(0, exitos[tarefa]) : 0;
            int pontuacao = Math.min(16, preferencia) * 5 + Math.min(10, exito);
            if (tarefa == ultimaTarefa) pontuacao -= 30;
            if (pontuacao > melhor) {
                melhor = pontuacao;
                escolhida = tarefa;
            }
        }
        return escolhida;
    }

    /** Pausa automatica para recuperacao, nunca exige recursos ilimitados. */
    public static boolean recuperado(float vida, float vidaMaxima, boolean pegandoFogo) {
        return !pegandoFogo && vida >= vidaMaxima * 0.68F;
    }

    public static boolean podePassar(boolean chaoSolido, boolean espacoLivre, boolean riscoPerto) {
        return chaoSolido && espacoLivre && !riscoPerto;
    }

    private Cerebro() { }
}
