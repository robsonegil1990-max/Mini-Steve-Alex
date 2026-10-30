package br.com.minicompanheiros.network;

import br.com.minicompanheiros.Alvos;
import br.com.minicompanheiros.CompanheiroEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Mensagem do cliente: servidor confere distancia, proprietario e indices. */
public final class MensagemOrdem {
    public static final int TRABALHAR = 0;
    public static final int RECOLHER_TUDO = 1;
    public static final int DEVOLVER_TUDO = 2;
    public static final int ALTERNAR_SEGUIR = 3;
    public static final int RETORNAR = 4;
    public static final int ALIMENTAR = 5;
    public static final int RECOLHER_SLOT = 6;
    public static final int DEVOLVER_FERRAMENTA = 7;
    public static final int ALTERNAR_IA = 8;

    public final int entityId, action, job, choice;

    public MensagemOrdem(int entityId, int action, int job, int choice) {
        this.entityId = entityId;
        this.action = action;
        this.job = job;
        this.choice = choice;
    }

    public static void encode(MensagemOrdem m, PacketBuffer buf) {
        buf.writeVarInt(m.entityId);
        buf.writeVarInt(m.action);
        buf.writeVarInt(m.job);
        buf.writeVarInt(m.choice);
    }

    public static MensagemOrdem decode(PacketBuffer buf) {
        return new MensagemOrdem(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(MensagemOrdem m, Supplier<NetworkEvent.Context> provider) {
        NetworkEvent.Context ctx = provider.get();
        ctx.enqueueWork(() -> {
            ServerPlayerEntity player = ctx.getSender();
            if (player == null) return;
            Entity e = player.level.getEntity(m.entityId);
            if (!(e instanceof CompanheiroEntity)) return;
            CompanheiroEntity helper = (CompanheiroEntity) e;
            if (!helper.isOwner(player) || player.distanceToSqr(helper) > 64.0D) return;
            switch (m.action) {
                case TRABALHAR:
                    if (Alvos.valid(m.job, m.choice)) helper.setOrder(m.job, m.choice);
                    break;
                case RECOLHER_TUDO: helper.collect(player); break;
                case DEVOLVER_TUDO: helper.returnTools(player); break;
                case ALTERNAR_SEGUIR: helper.toggleFollow(); break;
                case RETORNAR: helper.returnToOwner(); break;
                case ALTERNAR_IA: helper.toggleAutonomous(player); break;
                case ALIMENTAR: helper.feedFromInventory(player); break;
                case RECOLHER_SLOT: helper.takeSlot(player, m.choice); break;
                case DEVOLVER_FERRAMENTA: helper.returnTool(player, m.choice); break;
                default: return;
            }
            helper.sendState(player, false);
        });
        ctx.setPacketHandled(true);
    }
}
