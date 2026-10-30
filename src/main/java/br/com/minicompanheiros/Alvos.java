package br.com.minicompanheiros;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;

/** Categorias selecionadas no menu: todas da versao 1.16.5, sem itens inventados. */
public final class Alvos {
    public static final int PARADO = 0, MINERAR = 1, MADEIRA = 2, CACAR = 3, MONSTROS = 4;

    public static final Block[] MINERIOS = {
            Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE,
            Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE,
            Blocks.ANCIENT_DEBRIS
    };
    public static final String[] NOMES_MINERIOS = {
            "Carvao", "Ferro", "Ouro", "Diamante", "Esmeralda",
            "Redstone", "Lapis-lazuli", "Quartzo", "Ouro do Nether",
            "Detritos ancestrais"
    };
    // Niveis de ferramentas vanilla: madeira/ouro=0, pedra=1, ferro=2, diamante=3.
    public static final int[] NIVEL_PICARETA = {0, 1, 2, 2, 2, 2, 1, 0, 0, 3};

    public static final Block[] MADEIRAS = {
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG,
            Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
            Blocks.CRIMSON_STEM, Blocks.WARPED_STEM
    };
    public static final String[] NOMES_MADEIRAS = {
            "Carvalho", "Pinheiro", "Betula", "Selva", "Acacia", "Carvalho escuro",
            "Carmesim", "Distorcida"
    };
    public static final EntityType<?>[] ANIMAIS = {
            EntityType.COW, EntityType.PIG, EntityType.CHICKEN,
            EntityType.SHEEP, EntityType.RABBIT
    };
    public static final String[] NOMES_ANIMAIS = {
            "Vaca (carne/couro)", "Porco (carne)", "Galinha (carne/pena)",
            "Ovelha (carne/la)", "Coelho (carne/pele)"
    };
    public static final EntityType<?>[] MONSTROS_TIPOS = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.HUSK, EntityType.DROWNED, EntityType.SLIME
    };
    public static final String[] NOMES_MONSTROS = {
            "Zumbi", "Esqueleto (ossos)", "Aranha (linha)",
            "Zumbi do deserto", "Afogado", "Slime"
    };

    public static String[] choices(int job) {
        switch (job) {
            case MINERAR: return NOMES_MINERIOS;
            case MADEIRA: return NOMES_MADEIRAS;
            case CACAR: return NOMES_ANIMAIS;
            case MONSTROS: return NOMES_MONSTROS;
            default: return new String[0];
        }
    }

    public static EntityType<?> targetMob(int job, int choice) {
        if (!valid(job, choice)) return null;
        if (job == CACAR) return ANIMAIS[choice];
        if (job == MONSTROS) return MONSTROS_TIPOS[choice];
        return null;
    }

    public static boolean valid(int job, int choice) {
        switch (job) {
            case PARADO: return choice == 0;
            case MINERAR: return choice >= 0 && choice < MINERIOS.length;
            case MADEIRA: return choice >= 0 && choice < MADEIRAS.length;
            case CACAR: return choice >= 0 && choice < ANIMAIS.length;
            case MONSTROS: return choice >= 0 && choice < MONSTROS_TIPOS.length;
            default: return false;
        }
    }

    private Alvos() { }
}
