package br.com.minicompanheiros.client;

import br.com.minicompanheiros.Alvos;
import br.com.minicompanheiros.CompanheiroEntity;
import br.com.minicompanheiros.network.MensagemEstado;
import br.com.minicompanheiros.network.MensagemOrdem;
import br.com.minicompanheiros.network.Messages;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.InventoryScreen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.StringTextComponent;

/**
 * Menu 'inventario' desenhado usando apenas render vanilla: leve e sem incompatibilidades
 * conhecidas por usar GLSL/OpenGL proprio. Snapshot de itens vem do servidor.
 */
public class TelaCompanheiro extends Screen {
    private static final int PANEL_W = 340, PANEL_H = 218;
    private final int helperId;
    private MensagemEstado snapshot;
    private Button followButton;
    private Button autoButton;
    private final int section; // 0 painel, 1 minerar, 2 madeira, 3 caca, 4 monstros, 5 mochila

    public TelaCompanheiro(int entityId, MensagemEstado initial, int section) {
        super(new StringTextComponent("Mini Companheiros"));
        helperId = entityId;
        snapshot = initial;
        this.section = section;
    }

    public int getHelperId() { return helperId; }
    public void updateState(MensagemEstado update) {
        snapshot = update;
        if (followButton != null) {
            followButton.setMessage(new StringTextComponent(snapshot.follow ? "Seguir: SIM" : "Seguir: NAO"));
        }
        if (autoButton != null) {
            autoButton.setMessage(new StringTextComponent(snapshot.autonomous ? "IA: LIGADA" : "IA: DESLIGADA"));
        }
    }

    private int x0() { return (width - PANEL_W) / 2; }
    private int y0() { return (height - PANEL_H) / 2; }

    @Override
    protected void init() {
        int x = x0(), y = y0();
        if (section == 0) {
            int a = x + 143, b = x + 235, w = 87;
            button(a, y + 37, w, "Minerar", () -> openSection(Alvos.MINERAR));
            button(b, y + 37, w, "Madeira", () -> openSection(Alvos.MADEIRA));
            button(a, y + 61, w, "Cacar", () -> openSection(Alvos.CACAR));
            button(b, y + 61, w, "Monstros", () -> openSection(Alvos.MONSTROS));
            button(a, y + 87, w, "Mochila", () -> openSection(5));
            button(b, y + 87, w, "Recolher", () -> send(MensagemOrdem.RECOLHER_TUDO, 0, 0));
            followButton = button(a, y + 111, w, snapshot.follow ? "Seguir: SIM" : "Seguir: NAO",
                    () -> send(MensagemOrdem.ALTERNAR_SEGUIR, 0, 0));
            button(b, y + 111, w, "Voltar", () -> send(MensagemOrdem.RETORNAR, 0, 0));
            button(a, y + 137, w, "Alimentar", () -> send(MensagemOrdem.ALIMENTAR, 0, 0));
            button(b, y + 137, w, "Parar", () -> send(MensagemOrdem.TRABALHAR, Alvos.PARADO, 0));
            autoButton = button(a, y + 163, w, snapshot.autonomous ? "IA: LIGADA" : "IA: DESLIGADA",
                    () -> send(MensagemOrdem.ALTERNAR_IA, 0, 0));
            button(b, y + 163, w, "Tirar equips", () -> send(MensagemOrdem.DEVOLVER_TUDO, 0, 0));
            button(x + 142, y + 192, 180, "Fechar", this::onClose);
        } else if (section == 5) {
            button(x + 147, y + 146, 176, "Recolher todos os itens", () -> send(MensagemOrdem.RECOLHER_TUDO, 0, 0));
            button(x + 147, y + 171, 176, "Devolver todas ferramentas", () -> send(MensagemOrdem.DEVOLVER_TUDO, 0, 0));
            button(x + 147, y + 195, 176, "Voltar ao menu", () -> openSection(0));
        } else {
            String[] choices = Alvos.choices(section);
            for (int i = 0; i < choices.length; i++) {
                final int index = i;
                button(x + 24 + (i % 2) * 150, y + 42 + (i / 2) * 27, 144,
                        choices[i], () -> choose(section, index));
            }
            button(x + 95, y + 190, 150, "Voltar ao menu", () -> openSection(0));
        }
    }

    private Button button(int x, int y, int w, String title, Runnable click) {
        return addButton(new Button(x, y, w, 20, new StringTextComponent(title), btn -> click.run()));
    }

    private void openSection(int tab) {
        Minecraft.getInstance().setScreen(new TelaCompanheiro(helperId, snapshot, tab));
    }

    private void choose(int job, int choice) {
        send(MensagemOrdem.TRABALHAR, job, choice);
        openSection(0);
    }

    private void send(int action, int job, int choice) {
        Messages.CHANNEL.sendToServer(new MensagemOrdem(helperId, action, job, choice));
    }

    @Override
    public void render(MatrixStack stack, int mouseX, int mouseY, float partialTicks) {
        renderBackground(stack);
        int x = x0(), y = y0();
        // Moldura de pedra com visual inspirado no inventario do Minecraft.
        fill(stack, x - 2, y - 2, x + PANEL_W + 2, y + PANEL_H + 2, 0xFF151519);
        fill(stack, x, y, x + PANEL_W, y + PANEL_H, 0xFFB8B8B8);
        fill(stack, x + 2, y + 2, x + PANEL_W - 2, y + PANEL_H - 2, 0xFF686868);
        fill(stack, x + 4, y + 4, x + PANEL_W - 4, y + PANEL_H - 4, 0xFF25292F);
        fill(stack, x + 7, y + 29, x + PANEL_W - 7, y + 30, 0xFF67707B);
        String sectionTitle = section == 0 ? "CENTRAL DO AJUDANTE" :
                section == 5 ? "MOCHILA E EQUIPAMENTOS" :
                section == Alvos.MINERAR ? "ESCOLHER MINERIO" :
                section == Alvos.MADEIRA ? "ESCOLHER MADEIRA" :
                section == Alvos.CACAR ? "ESCOLHER ANIMAL" : "ESCOLHER MONSTRO";
        drawCenteredString(stack, font, sectionTitle, width / 2, y + 12, 0xFFF6E4A1);

        if (section == 0 || section == 5) {
            fill(stack, x + 8, y + 34, x + 132, y + 210, 0xFF131921);
            drawCenteredString(stack, font, helperName(), x + 70, y + 42, 0xFFF3E9D9);
            drawMiniPreview(x, y, mouseX, mouseY);
            drawBars(stack, x + 16, y + 160);
            String job = snapshot.returning ? "Voltando ao dono" :
                    snapshot.job == Alvos.PARADO ? (snapshot.follow ? "Seguindo" : "Parado") :
                    snapshot.food <= 0 ? "Sem comida" :
                    snapshot.slots[snapshot.job == Alvos.MINERAR ? 0 : snapshot.job == Alvos.MADEIRA ? 1 : 2].isEmpty()
                            ? "Sem ferramenta" :
                    snapshot.job == Alvos.MINERAR ? "Minerando" :
                    snapshot.job == Alvos.MADEIRA ? "Cortando madeira" :
                    snapshot.job == Alvos.CACAR ? "Cacando animais" : "Cacando monstros";
            drawCenteredString(stack, font, job, x + 70, y + 191, 0xFFE5E5E5);
            if (snapshot.autonomous) drawCenteredString(stack, font, "IA LOCAL ATIVA", x + 70, y + 205, 0xFF7CDB9B);
        }
        if (section == 5) {
            drawString(stack, font, "FERRAMENTAS", x + 147, y + 40, 0xFFF3E9D9);
            for (int i = 0; i < 3; i++) slot(stack, x + 151 + i * 25, y + 56, snapshot.slots[i]);
            drawString(stack, font, "Pic / Max / Esp", x + 147, y + 83, 0xFFC8C8C8);
            drawString(stack, font, "ITENS COLETADOS", x + 246, y + 40, 0xFFF3E9D9);
            for (int i = 0; i < 9; i++) {
                slot(stack, x + 251 + (i % 3) * 24, y + 56 + (i / 3) * 24, snapshot.slots[i + 3]);
            }
            drawString(stack, font, "Clique num slot para retirar", x + 147, y + 133, 0xFFDBD9A0);
        }
        if (section > 0 && section < 5) {
            drawCenteredString(stack, font, "So procura alvos proximos, seguros e em chunks carregados.",
                    width / 2, y + 178, 0xFFB3BFC9);
        }
        super.render(stack, mouseX, mouseY, partialTicks);
        if (section == 5) drawHoveredItem(stack, x, y, mouseX, mouseY);
    }

    private String helperName() {
        if (minecraft == null || minecraft.level == null) return "Mini Steve / Alex";
        Entity e = minecraft.level.getEntity(helperId);
        return e instanceof CompanheiroEntity && ((CompanheiroEntity) e).isAlex() ? "Mini Alex" : "Mini Steve";
    }

    private void drawMiniPreview(int x, int y, int mouseX, int mouseY) {
        if (minecraft == null || minecraft.level == null) return;
        Entity e = minecraft.level.getEntity(helperId);
        if (e instanceof CompanheiroEntity) {
            // O proprio renderer vanilla do mini e exibido dentro do menu.
            InventoryScreen.renderEntityInInventory(x + 71, y + 150, 66,
                    (float) (x + 71 - mouseX) * 0.08F,
                    (float) (y + 85 - mouseY) * 0.08F, (CompanheiroEntity) e);
        }
    }

    private void drawBars(MatrixStack stack, int x, int y) {
        float health = 16.0F;
        if (minecraft != null && minecraft.level != null) {
            Entity e = minecraft.level.getEntity(helperId);
            if (e instanceof CompanheiroEntity) health = ((CompanheiroEntity) e).getHealth();
        }
        drawString(stack, font, "Vida " + (int) Math.ceil(health) + "/16", x, y, 0xFFECECEC);
        fill(stack, x, y + 12, x + 103, y + 17, 0xFF4F1B1B);
        fill(stack, x, y + 12, x + (int) (103.0F * Math.max(0, health) / 16.0F), y + 17, 0xFFD84B50);
        int food = snapshot.food;
        if (minecraft != null && minecraft.level != null) {
            Entity e = minecraft.level.getEntity(helperId);
            if (e instanceof CompanheiroEntity) food = ((CompanheiroEntity) e).getFoodLevel();
        }
        drawString(stack, font, "Fome " + food + "/20", x, y + 23, 0xFFECECEC);
        fill(stack, x + 49, y + 24, x + 103, y + 29, 0xFF4B3F23);
        fill(stack, x + 49, y + 24, x + 49 + (int) (54.0F * food / 20.0F), y + 29, 0xFFE2BC5B);
    }

    private void slot(MatrixStack stack, int x, int y, ItemStack item) {
        fill(stack, x - 2, y - 2, x + 19, y + 19, 0xFF06090E);
        fill(stack, x - 1, y - 1, x + 18, y + 18, 0xFF7A7C7C);
        fill(stack, x, y, x + 17, y + 17, 0xFF24282D);
        if (!item.isEmpty() && minecraft != null) {
            minecraft.getItemRenderer().renderAndDecorateItem(item, x, y);
            minecraft.getItemRenderer().renderGuiItemDecorations(font, item, x, y);
        }
    }

    private void drawHoveredItem(MatrixStack stack, int x, int y, int mouseX, int mouseY) {
        for (int i = 0; i < 12; i++) {
            int cx = i < 3 ? x + 151 + i * 25 : x + 251 + ((i - 3) % 3) * 24;
            int cy = i < 3 ? y + 56 : y + 56 + ((i - 3) / 3) * 24;
            if (mouseX >= cx && mouseX < cx + 17 && mouseY >= cy && mouseY < cy + 17) {
                if (!snapshot.slots[i].isEmpty()) renderTooltip(stack, snapshot.slots[i], mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (section == 5 && button == 0) {
            int x = x0(), y = y0();
            for (int i = 0; i < 12; i++) {
                int sx = i < 3 ? x + 151 + i * 25 : x + 251 + ((i - 3) % 3) * 24;
                int sy = i < 3 ? y + 56 : y + 56 + ((i - 3) / 3) * 24;
                if (mouseX >= sx && mouseX < sx + 17 && mouseY >= sy && mouseY < sy + 17) {
                    if (i < 3) send(MensagemOrdem.DEVOLVER_FERRAMENTA, 0, i);
                    else send(MensagemOrdem.RECOLHER_SLOT, 0, i - 3);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean isPauseScreen() { return false; }
}
