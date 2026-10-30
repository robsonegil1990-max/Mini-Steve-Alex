package br.com.minicompanheiros.network;

import br.com.minicompanheiros.MiniCompanheiros;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;

public final class Messages {
    private static final String PROTOCOL = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiniCompanheiros.MOD_ID, "commands"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        CHANNEL.registerMessage(0, MensagemOrdem.class,
                MensagemOrdem::encode, MensagemOrdem::decode, MensagemOrdem::handle);
        CHANNEL.registerMessage(1, MensagemEstado.class,
                MensagemEstado::encode, MensagemEstado::decode, MensagemEstado::handle);
    }

    private Messages() { }
}
