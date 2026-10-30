# Mini Steve & Mini Alex — fonte + compilacao na nuvem

Este pacote NAO eh um mod .jar pronto. Contem a fonte V0.3, testada apenas offline,
com GitHub Actions para compilar o .jar em um servidor, sem instalar Java/Gradle no seu PC.

Leia primeiro `COMO_GERAR_O_JAR_SEM_COMPILAR_NO_PC.txt`.

**Build so sera considerado concluido quando o GitHub Actions mostrar sucesso.
Ainda falta testar o .jar real no Minecraft 1.16.5 com Forge 36.1.0 e OptiFine G8.**

Projeto-base V0.3; versao e API mantidas para Forge 1.16.5.


# Mini Steve & Mini Alex — V0.3.0, IA LOCAL (Forge 1.16.5)

**Projeto-fonte do mod.** Fornece ajudantes craftaveis e uma IA programada leve, sem internet, sem servicos pagos e sem gerar itens do nada. O projeto esta pronto para **tentar compilar no Windows**. **Nao contem um `.jar` jogavel precompilado**: este ambiente nao conseguiu obter as dependencias Forge para o build. O resultado real depende do build no seu PC e de testes no Minecraft.

## Como obter o mod e instalar

1. Extraia TODO o ZIP em uma pasta; nao copie o ZIP para `mods`.
2. Execute **`INSTALAR_COM_UM_CLIQUE.bat`**, com internet. O instalador procura ou baixa JDK 8, verifica o codigo e baixa Forge 36.1.0/Gradle; se o build passar, ele cria e instala **`mini-companheiros-1.16.5-0.3.0.jar`**.
3. Se o TLauncher usar pasta propria, cole o caminho da pasta **`mods`** quando o instalador perguntar. O script faz backup de versoes antigas antes de copiar o novo JAR.
4. Abra um mundo de TESTE com o perfil **Minecraft Java 1.16.5 + Forge 36.1.0**. Depois teste com **OptiFine HD U G8**, obtido separadamente em `https://optifine.net/downloads`.
5. Consulte `CHECKLIST_TESTES.md`. Se falhar, abra `ERRO_DA_COMPILACAO.log` ou `latest.log` e envie o erro.

### Como usar no jogo (quando o build passar)

- Craft na bancada 3x3: diamante/esmeralda, blocos de ferro, obsidiana, sucata de netherita. Centro: suporte de armadura (Steve) ou abobora entalhada (Alex). Receitas nos arquivos `data/minicompanheiros/recipes`.
- Clique com botao direito no chao para criar o mini. Clique direito no mini para abrir o painel (nome, personagem 3D, vida, fome, ferramentas e mochila).
- **Shift + clique direito** segurando espada, picareta ou machado para equipa-lo (a ferramenta aparece na mao e perde durabilidade). Ele nasce com espada de madeira.
- Escolha `Minerar`, `Madeira`, `Cacar` ou `Monstros` e o alvo exato (o mini nao usa mineracao com raio-X nem derruba construcoes de madeira sem folhas por perto).
- Clique em **`IA: DESLIGADA`** para ativar a IA. **A IA so alterna entre as categorias e alvos que voce ja autorizou uma vez manualmente.** Selecionar uma tarefa no menu desliga o modo automatico para respeitar a ordem manual.
- O assistente guarda em memoria as tarefas preferidas, os resultados e posicoes temporariamente inacessiveis, evitando repetir um caminho ruim. O progresso de tarefas e preferencias e salvo no mundo.
- Se a mochila estiver cheia, ele tenta voltar ao dono. Em baixa vida, recua. Sem fome, consome um alimento permitido da propria mochila ou para o trabalho. Nao gera alimentos.
- `Mochila` mostra 9 itens e 3 ferramentas; clique para retirar recursos. `Seguir`, `Voltar`, `Parar`, `Alimentar` e retirar ferramentas estao no painel principal.

## IA leve / limites reais

- E uma **IA de regras e navegacao**, NAO uma IA de linguagem nem um modelo que aprende via redes neurais. As prioridades sao adaptadas a partir das ordens e sucessos, com memoria de erros de caminho.
- Busca por **raio de 16 blocos e altura de +/-8** em chunks carregados, por proximidade. Distancia de trabalho limitada, sem explorar mundos ilimitados.
- Evita destinos perigosos visiveis (lava, agua, cactus, fogo, magma, fogueira) e aumenta custos de caminho para riscos; **nao garante evitar toda armadilha**.
- Minera apenas minérios expostos e acessiveis, com picareta adequada. A madeira vem de troncos proximos a folhagem, e animais filhotes/pets estao protegidos.
- Tem 16 pontos de vida, fome de 0 a 20, 9 espacos na mochila e desgaste normal das ferramentas. A IA pode morrer; itens da mochila/ferramentas caem ao morrer.
- A acao consome tempo real (sem gerar drops artificiais). **Sem teleporte** e sem busca global do mapa; se o dono mudar muito de lugar, o mini pode nao alcança-lo.
- Nao testa protecoes de terrenos de servidores. Indicado inicialmente para single-player.

## Estado das verificacoes

| Teste | Situacao |
|---|---|
| Sintaxe dos 13 arquivos Java (nivel Java 8) | PASSOU offline |
| Testes das regras de sobrevivencia | PASSOU offline |
| 3859 testes de decisoes da IA local | PASSOU offline |
| Receitas, texturas, indice de alvos, formatos JSON | PASSOU offline |
| Compilacao com bibliotecas reais do Forge | **PENDENTE** (rede indisponivel aqui) |
| Funcionalidade no jogo e OptiFine/shaders | **PENDENTE** (precisa testar) |

**Aviso de compatibilidade:** OptiFine HD U G8 lista Forge 36.1.0 oficialmente, mas essa lista nao comprova a compatibilidade especifica deste mod. Como usa renderer padrao vanilla e nao depende de classes OptiFine, foi pensado para reduzir conflitos. Nunca use este mod no seu unico mundo sem backup.
