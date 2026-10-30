package br.com.minicompanheiros.client;

import br.com.minicompanheiros.CompanheiroEntity;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.entity.model.PlayerModel;
import net.minecraft.util.ResourceLocation;

/** PlayerModel vanilla com ferramenta realmente visivel na mao. Sem hooks de OptiFine. */
public class RenderCompanheiro extends MobRenderer<CompanheiroEntity, PlayerModel<CompanheiroEntity>> {
    private static final ResourceLocation STEVE = new ResourceLocation("minecraft", "textures/entity/steve.png");
    private static final ResourceLocation ALEX = new ResourceLocation("minecraft", "textures/entity/alex.png");
    private final PlayerModel<CompanheiroEntity> normal = new PlayerModel<>(0.0F, false);
    private final PlayerModel<CompanheiroEntity> slim = new PlayerModel<>(0.0F, true);

    public RenderCompanheiro(EntityRendererManager manager) {
        super(manager, new PlayerModel<>(0.0F, false), 0.24F);
        // Camada vanilla: mostra espada, machado e picareta conforme a tarefa.
        addLayer(new ItemInHandLayer<>(this));
    }

    @Override
    public ResourceLocation getTextureLocation(CompanheiroEntity helper) {
        return helper.isAlex() ? ALEX : STEVE;
    }

    @Override
    public void render(CompanheiroEntity helper, float yaw, float partialTicks,
                       MatrixStack matrix, IRenderTypeBuffer buffer, int light) {
        this.model = helper.isAlex() ? slim : normal;
        this.model.rightArmPose = helper.getMainHandItem().isEmpty()
                ? BipedModel.ArmPose.EMPTY : BipedModel.ArmPose.ITEM;
        matrix.pushPose();
        try {
            // 63% da altura do jogador: 'medio', nao miniatura minuscúla.
            matrix.scale(0.63F, 0.63F, 0.63F);
            super.render(helper, yaw, partialTicks, matrix, buffer, light);
        } finally {
            matrix.popPose();
        }
    }
}
