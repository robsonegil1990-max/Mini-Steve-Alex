package br.com.minicompanheiros.network;

import br.com.minicompanheiros.client.ClientGui;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Snapshot do inventario e status enviado do servidor ao cliente que e dono. */
public final class MensagemEstado {
    public final int entityId;
    public final boolean open;
    public final int food, job, choice;
    public final boolean follow, returning, autonomous;
    public final ItemStack[] slots;

    public MensagemEstado(int entityId, boolean open, int food, int job, int choice,
                          boolean follow, boolean returning, boolean autonomous, ItemStack[] slots) {
        this.entityId = entityId;
        this.open = open;
        this.food = food;
        this.job = job;
        this.choice = choice;
        this.follow = follow;
        this.returning = returning;
        this.autonomous = autonomous;
        this.slots = slots;
    }

    public static void encode(MensagemEstado m, PacketBuffer buf) {
        buf.writeVarInt(m.entityId);
        buf.writeBoolean(m.open);
        buf.writeVarInt(m.food);
        buf.writeVarInt(m.job);
        buf.writeVarInt(m.choice);
        buf.writeBoolean(m.follow);
        buf.writeBoolean(m.returning);
        buf.writeBoolean(m.autonomous);
        for (int i = 0; i < 12; i++) buf.writeItem(m.slots[i]);
    }

    public static MensagemEstado decode(PacketBuffer buf) {
        int id = buf.readVarInt();
        boolean open = buf.readBoolean();
        int food = buf.readVarInt(), job = buf.readVarInt(), choice = buf.readVarInt();
        boolean follow = buf.readBoolean(), returning = buf.readBoolean(), autonomous = buf.readBoolean();
        ItemStack[] inventory = new ItemStack[12];
        for (int i = 0; i < inventory.length; i++) inventory[i] = buf.readItem();
        return new MensagemEstado(id, open, food, job, choice, follow, returning, autonomous, inventory);
    }

    public static void handle(MensagemEstado message, Supplier<NetworkEvent.Context> provider) {
        NetworkEvent.Context ctx = provider.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientGui.onState(message)));
        ctx.setPacketHandled(true);
    }
}
