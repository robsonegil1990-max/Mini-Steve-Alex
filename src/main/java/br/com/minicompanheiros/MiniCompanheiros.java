package br.com.minicompanheiros;

import br.com.minicompanheiros.network.Messages;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.RegistryObject;

@Mod(MiniCompanheiros.MOD_ID)
public class MiniCompanheiros {
    public static final String MOD_ID = "minicompanheiros";
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITIES, MOD_ID);

    public static final RegistryObject<EntityType<CompanheiroEntity>> COMPANHEIRO = ENTITIES.register("companheiro",
            () -> EntityType.Builder.<CompanheiroEntity>of(CompanheiroEntity::new, EntityClassification.CREATURE)
                    .sized(0.42f, 1.14f).clientTrackingRange(8).build(MOD_ID + ":companheiro"));
    public static final RegistryObject<Item> MINI_STEVE = ITEMS.register("mini_steve",
            () -> new ItemCompanheiro(false, new Item.Properties().tab(ItemGroup.TAB_MISC).stacksTo(16)));
    public static final RegistryObject<Item> MINI_ALEX = ITEMS.register("mini_alex",
            () -> new ItemCompanheiro(true, new Item.Properties().tab(ItemGroup.TAB_MISC).stacksTo(16)));

    public MiniCompanheiros() {
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ENTITIES.register(FMLJavaModLoadingContext.get().getModEventBus());
        Messages.register();
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void attributes(EntityAttributeCreationEvent event) {
            event.put(COMPANHEIRO.get(), CompanheiroEntity.attributes().build());
        }
    }
}
