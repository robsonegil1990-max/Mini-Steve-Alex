package br.com.minicompanheiros;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class ItemCompanheiro extends Item {
    private final boolean alex;

    public ItemCompanheiro(boolean alex, Properties properties) {
        super(properties);
        this.alex = alex;
    }

    @Override
    public ActionResultType useOn(ItemUseContext context) {
        World level = context.getLevel();
        PlayerEntity player = context.getPlayer();
        if (player == null) return ActionResultType.FAIL;
        BlockPos place = context.getClickedPos().relative(context.getClickedFace());
        if (!level.isEmptyBlock(place) || !level.isEmptyBlock(place.above())) {
            return ActionResultType.FAIL;
        }
        if (!level.isClientSide) {
            CompanheiroEntity helper = MiniCompanheiros.COMPANHEIRO.get().create(level);
            if (helper == null) return ActionResultType.FAIL;
            helper.setPos(place.getX() + 0.5D, place.getY(), place.getZ() + 0.5D);
            helper.initializeOwner(player.getUUID(), place, alex);
            level.addFreshEntity(helper);
            ItemStack item = context.getItemInHand();
            if (!player.abilities.instabuild) item.shrink(1);
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }
}
