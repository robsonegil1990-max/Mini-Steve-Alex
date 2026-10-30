package br.com.minicompanheiros.client;

import br.com.minicompanheiros.network.MensagemEstado;
import net.minecraft.client.Minecraft;

/** Isolado no pacote client para evitar classloading de GUI em servidor dedicado. */
public final class ClientGui {
    public static void onState(MensagemEstado packet) {
        Minecraft mc = Minecraft.getInstance();
        if (packet.open) {
            mc.setScreen(new TelaCompanheiro(packet.entityId, packet, 0));
        } else if (mc.screen instanceof TelaCompanheiro) {
            TelaCompanheiro screen = (TelaCompanheiro) mc.screen;
            if (screen.getHelperId() == packet.entityId) screen.updateState(packet);
        }
    }
    private ClientGui() { }
}
