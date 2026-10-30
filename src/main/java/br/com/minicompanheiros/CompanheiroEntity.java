package br.com.minicompanheiros;

import br.com.minicompanheiros.network.MensagemEstado;
import br.com.minicompanheiros.network.Messages;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.CreatureEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.attributes.AttributeModifierMap;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.goal.FloatGoal;
import net.minecraft.entity.ai.goal.LookAtGoal;
import net.minecraft.entity.ai.goal.RandomWalkingGoal;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.monster.MonsterEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TieredItem;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkHooks;
import net.minecraftforge.fml.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Ajudante server-side: nao gera recursos, nao carrega chunks e so trabalha em area proxima.
 * Controles, dano, fome, equipamento e inventario sao autoritativos no servidor.
 */
public class CompanheiroEntity extends CreatureEntity {
    private static final DataParameter<Boolean> ALEX = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> JOB = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.INT);
    private static final DataParameter<Integer> CHOICE = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.INT);
    private static final DataParameter<Integer> FOOD = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.INT);
    private static final DataParameter<Boolean> FOLLOW = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> RETURNING = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> AUTO = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.BOOLEAN);

    private static final int RANGE = 16;
    private static final int HEIGHT = 8;
    private static final int WIDTH = RANGE * 2 + 1;
    // Ordens de busca: blocos mais proximos sao procurados primeiro, em vez de
    // comecar sempre numa borda do raio de 16 blocos. Computado uma unica vez.
    private static final List<BlockPos> SEARCH_ORDER = createSearchOrder();

    private static List<BlockPos> createSearchOrder() {
        List<BlockPos> list = new ArrayList<>();
        for (int y = -HEIGHT; y <= HEIGHT; y++) {
            for (int x = -RANGE; x <= RANGE; x++) {
                for (int z = -RANGE; z <= RANGE; z++) {
                    if (x * x + z * z <= RANGE * RANGE) list.add(new BlockPos(x, y, z));
                }
            }
        }
        // distancia horizontal, depois vertical: o mini procura no proprio nivel antes de subir/descer.
        Collections.sort(list, Comparator.comparingInt(p -> p.getX() * p.getX() + p.getZ() * p.getZ() + p.getY() * p.getY() * 2));
        return Collections.unmodifiableList(list);
    }
    private static final int STORAGE_SIZE = 9;
    private static final int PICK = 0, AXE = 1, SWORD = 2;

    @Nullable private UUID ownerId;
    private BlockPos home = BlockPos.ZERO;
    private final NonNullList<ItemStack> tools = NonNullList.withSize(3, ItemStack.EMPTY);
    private final NonNullList<ItemStack> storage = NonNullList.withSize(STORAGE_SIZE, ItemStack.EMPTY);
    @Nullable private BlockPos targetBlock;
    @Nullable private BlockPos standingBlock;
    @Nullable private LivingEntity targetMob;
    private int scanIndex;
    private int huntMisses;
    private int unreachableTicks;
    private int cooldown;
    private int pickupWindow;
    @Nullable private BlockPos pickupPosition;
    private boolean attackedCurrentTarget;
    private int workActions;
    private int healingTicks;
    private boolean recoveryPause;
    private int nextAutoAttempt;
    private int lastAutoJob;
    private int authorisedJobs; // apenas categorias que o dono escolheu manualmente
    private final int[] preferredTarget = new int[5];
    private final int[] preferences = new int[5];
    private final int[] successes = new int[5];
    private final int[] blockedJobUntil = new int[5];
    private final Map<Long, Integer> failedLocations = new HashMap<>();

    public CompanheiroEntity(EntityType<? extends CreatureEntity> type, World world) {
        super(type, world);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        // A mao mostra uma COPIA da ferramenta; os slots 'tools' sao a fonte real.
        // Nunca deve cair a copia visual, para evitar duplicacao.
        setDropChance(EquipmentSlotType.MAINHAND, -1.0F);
        // Caminhamento vanilla com custos altos para espinhos, fogo e agua.
        setPathfindingMalus(PathNodeType.DANGER_FIRE, 20.0F);
        setPathfindingMalus(PathNodeType.DAMAGE_FIRE, -1.0F);
        setPathfindingMalus(PathNodeType.DANGER_OTHER, 20.0F);
        setPathfindingMalus(PathNodeType.DAMAGE_OTHER, -1.0F);
        setPathfindingMalus(PathNodeType.WATER, 12.0F);
        xpReward = 0;
    }

    public static AttributeModifierMap.MutableAttribute attributes() {
        return CreatureEntity.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.27D)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ALEX, false);
        entityData.define(JOB, Alvos.PARADO);
        entityData.define(CHOICE, 0);
        entityData.define(FOOD, 16);
        entityData.define(FOLLOW, true);
        entityData.define(RETURNING, false);
        entityData.define(AUTO, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(9, new RandomWalkingGoal(this, 0.65D, 160) {
            @Override public boolean canUse() {
                return CompanheiroEntity.this.getJob() == Alvos.PARADO
                        && !CompanheiroEntity.this.isFollowing()
                        && !CompanheiroEntity.this.isReturning() && super.canUse();
            }
        });
        goalSelector.addGoal(10, new LookAtGoal(this, PlayerEntity.class, 6.0F));
    }

    @Override public IPacket<?> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
    @Override public boolean removeWhenFarAway(double distance) { return false; }

    public boolean isAlex() { return entityData.get(ALEX); }
    public int getJob() { return entityData.get(JOB); }
    public int getChoice() { return entityData.get(CHOICE); }
    public int getFoodLevel() { return entityData.get(FOOD); }
    public boolean isFollowing() { return entityData.get(FOLLOW); }
    public boolean isReturning() { return entityData.get(RETURNING); }
    public boolean isAutonomous() { return entityData.get(AUTO); }

    public void initializeOwner(UUID uuid, BlockPos place, boolean alex) {
        ownerId = uuid;
        home = place.immutable();
        entityData.set(ALEX, alex);
        setCustomName(new StringTextComponent(alex ? "Mini Alex" : "Mini Steve"));
        setCustomNameVisible(true);
        // Espada inicial de madeira: estetica pronta, mas dano pequeno e durabilidade real.
        tools.set(SWORD, new ItemStack(Items.WOODEN_SWORD));
        refreshHeldItem();
    }

    public boolean isOwner(PlayerEntity player) {
        return ownerId != null && ownerId.equals(player.getUUID());
    }

    /** Tarefas manuais registram preferencias que a IA pode usar futuramente. */
    public void setOrder(int job, int choice) {
        if (!Alvos.valid(job, choice)) return;
        entityData.set(AUTO, false);
        if (job != Alvos.PARADO) {
            authorisedJobs |= 1 << (job - 1);
            preferredTarget[job] = choice;
            preferences[job] = Math.min(16, preferences[job] + 2);
        }
        configureJob(job, choice, job != Alvos.PARADO);
    }

    private void configureJob(int job, int choice, boolean changeHome) {
        entityData.set(JOB, job);
        entityData.set(CHOICE, choice);
        entityData.set(RETURNING, false);
        if (changeHome) home = blockPosition().immutable();
        scanIndex = 0;
        huntMisses = 0;
        targetBlock = null;
        standingBlock = null;
        targetMob = null;
        attackedCurrentTarget = false;
        pickupWindow = 0;
        pickupPosition = null;
        unreachableTicks = 0;
        getNavigation().stop();
        refreshHeldItem();
    }

    public void toggleAutonomous(PlayerEntity player) {
        if (!isOwner(player)) return;
        if (isAutonomous()) {
            entityData.set(AUTO, false);
            configureJob(Alvos.PARADO, 0, false);
            entityData.set(RETURNING, true);
            player.displayClientMessage(new StringTextComponent("IA desligada. Voltando ao dono."), true);
            return;
        }
        if (authorisedJobs == 0) {
            player.displayClientMessage(new StringTextComponent("Escolha ao menos uma tarefa antes de ligar a IA."), true);
            return;
        }
        entityData.set(AUTO, true);
        home = blockPosition().immutable();
        Arrays.fill(blockedJobUntil, 0);
        nextAutoAttempt = 0;
        lastAutoJob = getJob();
        chooseNextAutonomousJob();
        player.displayClientMessage(new StringTextComponent("IA ligada! So executara tarefas autorizadas por voce."), true);
    }

    private void chooseNextAutonomousJob() {
        boolean[] availableTools = new boolean[5];
        for (int job = 1; job <= 4; job++) {
            availableTools[job] = hasToolFor(job, preferredTarget[job]);
        }
        int next = Cerebro.escolherTarefa(authorisedJobs, availableTools, blockedJobUntil,
                tickCount, lastAutoJob, preferences, successes);
        if (next == 0) {
            configureJob(Alvos.PARADO, 0, false);
            entityData.set(RETURNING, true);
            nextAutoAttempt = tickCount + 200;
        } else {
            lastAutoJob = next;
            configureJob(next, preferredTarget[next], false);
        }
    }

    public void toggleFollow() {
        entityData.set(FOLLOW, !isFollowing());
        entityData.set(RETURNING, false);
        if (!isFollowing() && getJob() == Alvos.PARADO) getNavigation().stop();
    }

    public void returnToOwner() {
        entityData.set(AUTO, false);
        configureJob(Alvos.PARADO, 0, false);
        entityData.set(RETURNING, true);
    }

    private void finishJob() {
        if (isAutonomous() && getJob() != Alvos.PARADO) {
            // Antes de repetir uma categoria sem recursos, tenta as outras categorias.
            blockedJobUntil[getJob()] = tickCount + 400;
            chooseNextAutonomousJob();
        } else {
            configureJob(Alvos.PARADO, 0, false);
            entityData.set(RETURNING, true);
        }
    }

    @Override
    protected ActionResultType mobInteract(PlayerEntity player, Hand hand) {
        if (!level.isClientSide) {
            if (!isOwner(player)) {
                player.displayClientMessage(new StringTextComponent("Esse ajudante pertence a outro jogador."), true);
                return ActionResultType.SUCCESS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (player.isShiftKeyDown() && isTool(held)) {
                equip(player, held);
            } else if (player.isShiftKeyDown() && held.getItem().isEdible()) {
                feedFromStack(player, held);
            } else {
                sendState((ServerPlayerEntity) player, true);
            }
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }

    private boolean isTool(ItemStack item) {
        return item.getItem() instanceof PickaxeItem || item.getItem() instanceof AxeItem || item.getItem() instanceof SwordItem;
    }

    private void equip(PlayerEntity player, ItemStack held) {
        int slot = held.getItem() instanceof PickaxeItem ? PICK : held.getItem() instanceof AxeItem ? AXE : SWORD;
        ItemStack incoming = held.copy();
        incoming.setCount(1);
        ItemStack old = tools.get(slot);
        tools.set(slot, incoming);
        if (!player.abilities.instabuild) held.shrink(1);
        if (!old.isEmpty()) giveOrDrop(player, old.copy());
        refreshHeldItem();
        player.displayClientMessage(new StringTextComponent("Equipado! Abra o menu para ver as ferramentas."), true);
    }

    private void refreshHeldItem() {
        int job = getJob();
        ItemStack chosen = tools.get(job == Alvos.MINERAR ? PICK : job == Alvos.MADEIRA ? AXE : SWORD);
        if (chosen.isEmpty()) chosen = tools.get(SWORD);
        setItemSlot(EquipmentSlotType.MAINHAND, chosen.copy());
    }

    private static void giveOrDrop(PlayerEntity player, ItemStack item) {
        if (!item.isEmpty()) {
            player.inventory.add(item);
            if (!item.isEmpty()) player.drop(item, false);
        }
    }

    private boolean feedFromStack(PlayerEntity player, ItemStack item) {
        if (item.isEmpty() || !item.getItem().isEdible() || item.getItem().getFoodProperties() == null) return false;
        if (getFoodLevel() >= 20) return false;
        int nutrition = Math.max(1, item.getItem().getFoodProperties().getNutrition());
        entityData.set(FOOD, Math.min(20, getFoodLevel() + nutrition));
        if (!player.abilities.instabuild) item.shrink(1);
        player.displayClientMessage(new StringTextComponent("Ajudante alimentado! Fome: " + getFoodLevel() + "/20"), true);
        return true;
    }

    /** Botao do menu: consome um alimento de dentro do inventario do dono. */
    public void feedFromInventory(PlayerEntity player) {
        if (!isOwner(player)) return;
        if (getFoodLevel() >= 20) return;
        for (int i = 0; i < player.inventory.getContainerSize(); i++) {
            if (feedFromStack(player, player.inventory.getItem(i))) return;
        }
        player.displayClientMessage(new StringTextComponent("Nao encontrei comida no seu inventario."), true);
    }

    public void collect(PlayerEntity player) {
        if (!isOwner(player)) return;
        for (int i = 0; i < STORAGE_SIZE; i++) takeSlot(player, i);
    }

    public void takeSlot(PlayerEntity player, int index) {
        if (!isOwner(player) || index < 0 || index >= STORAGE_SIZE) return;
        ItemStack old = storage.get(index);
        if (!old.isEmpty()) {
            giveOrDrop(player, old.copy());
            storage.set(index, ItemStack.EMPTY);
        }
    }

    public void returnTool(PlayerEntity player, int slot) {
        if (!isOwner(player) || slot < 0 || slot >= tools.size()) return;
        ItemStack tool = tools.get(slot);
        if (!tool.isEmpty()) {
            giveOrDrop(player, tool.copy());
            tools.set(slot, ItemStack.EMPTY);
            refreshHeldItem();
        }
    }

    public void returnTools(PlayerEntity player) {
        if (!isOwner(player)) return;
        setOrder(Alvos.PARADO, 0);
        for (int i = 0; i < tools.size(); i++) returnTool(player, i);
    }

    public void sendState(ServerPlayerEntity player, boolean open) {
        if (!isOwner(player)) return;
        ItemStack[] slots = new ItemStack[12];
        for (int i = 0; i < 3; i++) slots[i] = tools.get(i).copy();
        for (int i = 0; i < STORAGE_SIZE; i++) slots[3 + i] = storage.get(i).copy();
        MensagemEstado packet = new MensagemEstado(getId(), open, getFoodLevel(), getJob(),
                getChoice(), isFollowing(), isReturning(), isAutonomous(), slots);
        Messages.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level.isClientSide || ownerId == null) return;
        if (cooldown > 0) cooldown--;
        if (pickupWindow > 0) pickupWindow--;
        else pickupPosition = null;
        if (pickupWindow > 0 && tickCount % 8 == 0) collectNearbyDrops();
        if (++healingTicks >= 280) {
            healingTicks = 0;
            if (getFoodLevel() >= 15 && getHealth() < getMaxHealth()) {
                heal(1.0F);
                entityData.set(FOOD, getFoodLevel() - 1);
            }
        }
        if (getFoodLevel() <= 6) eatFromOwnBag();
        if (!recoveryPause && (isOnFire() || getHealth() <= getMaxHealth() * .32F)) {
            recoveryPause = true;
            getNavigation().stop();
        }
        if (recoveryPause) {
            if (Cerebro.recuperado(getHealth(), getMaxHealth(), isOnFire())) {
                recoveryPause = false;
                entityData.set(RETURNING, false);
            } else {
                followOwnerTick();
                return;
            }
        }
        if (getJob() == Alvos.PARADO) {
            if (isAutonomous() && tickCount >= nextAutoAttempt && getFoodLevel() > 0 && !isFull()) {
                chooseNextAutonomousJob();
            }
            if (getJob() == Alvos.PARADO && (isFollowing() || isReturning())) followOwnerTick();
            return;
        }
        int plan = Cerebro.decidir(getHealth(), getMaxHealth(), getFoodLevel(), false,
                false, isFull(), hasRequiredTool());
        if (plan == Cerebro.GUARDAR_ITENS) { finishJob(); return; }
        if (plan == Cerebro.VOLTAR || plan == Cerebro.TROCAR_FERRAMENTA) {
            getNavigation().stop();
            followOwnerTick();
            return;
        }
        if (blockPosition().distSqr(home) > (RANGE + 5) * (RANGE + 5)) {
            if (tickCount % 24 == 0) getNavigation().moveTo(home.getX() + .5D, home.getY(), home.getZ() + .5D, 1.0D);
            return;
        }
        if (getJob() == Alvos.CACAR || getJob() == Alvos.MONSTROS) huntTick();
        else gatherTick();
    }

    /** Usa apenas alimento fisicamente presente na mochila e nao come itens perigosos. */
    private boolean eatFromOwnBag() {
        if (getFoodLevel() > 6) return false;
        for (int i = 0; i < STORAGE_SIZE; i++) {
            ItemStack stack = storage.get(i);
            if (stack.isEmpty() || !stack.getItem().isEdible() || stack.getItem().getFoodProperties() == null) continue;
            if (stack.getItem() == Items.ROTTEN_FLESH || stack.getItem() == Items.POISONOUS_POTATO
                    || stack.getItem() == Items.SPIDER_EYE || stack.getItem() == Items.PUFFERFISH
                    || stack.getItem() == Items.CHICKEN) continue;
            int nutrition = stack.getItem().getFoodProperties().getNutrition();
            if (nutrition < 2) continue;
            entityData.set(FOOD, Math.min(20, getFoodLevel() + nutrition));
            stack.shrink(1);
            if (stack.isEmpty()) storage.set(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    @Nullable
    private PlayerEntity ownerNearby() {
        if (ownerId == null) return null;
        for (PlayerEntity player : level.players()) {
            if (ownerId.equals(player.getUUID()) && player.isAlive() && !player.isSpectator()) return player;
        }
        return null;
    }

    private void followOwnerTick() {
        PlayerEntity owner = ownerNearby();
        if (owner == null) return;
        double d = distanceToSqr(owner);
        if (d < 8.0D) {
            if (isReturning()) entityData.set(RETURNING, false);
            getNavigation().stop();
            return;
        }
        if (tickCount % 18 == 0) getNavigation().moveTo(owner, 1.12D);
    }

    private boolean hasRequiredTool() {
        return hasToolFor(getJob(), getChoice());
    }

    private boolean hasToolFor(int job, int choice) {
        if (!Alvos.valid(job, choice)) return false;
        ItemStack tool = tools.get(job == Alvos.MINERAR ? PICK : job == Alvos.MADEIRA ? AXE : SWORD);
        if (tool.isEmpty()) return false;
        if (job == Alvos.MINERAR) {
            return tool.getItem() instanceof PickaxeItem && Regras.picaretaSuficiente(
                    ((TieredItem) tool.getItem()).getTier().getLevel(), Alvos.NIVEL_PICARETA[choice]);
        }
        if (job == Alvos.MADEIRA) return tool.getItem() instanceof AxeItem;
        if (job == Alvos.CACAR || job == Alvos.MONSTROS) return tool.getItem() instanceof SwordItem;
        return false;
    }

    private void spendEnergy() {
        workActions++;
        if (workActions >= 5) {
            workActions = 0;
            entityData.set(FOOD, Regras.reduzirFome(getFoodLevel(), 1));
        }
    }

    private void gatherTick() {
        if (targetBlock == null) {
            if (tickCount % 8 == 0) scanForBlocks(260);
            return;
        }
        if (standingBlock == null || !level.isLoaded(targetBlock) || !isDesiredBlock(targetBlock)) {
            clearTarget(); return;
        }
        if (distanceToSqr(standingBlock.getX() + .5D, standingBlock.getY(), standingBlock.getZ() + .5D) > 3.1D) {
            if (tickCount % 18 == 0 && !getNavigation().moveTo(standingBlock.getX() + .5D,
                    standingBlock.getY(), standingBlock.getZ() + .5D, 1.0D)) unreachableTicks += 18;
            if (++unreachableTicks > 220) {
                rememberFailedTarget(targetBlock);
                clearTarget();
            }
            return;
        }
        if (cooldown > 0) return;
        int job = getJob();
        int slot = job == Alvos.MINERAR ? PICK : AXE;
        ItemStack tool = tools.get(slot);
        BlockPos pos = targetBlock;
        if (!level.isLoaded(pos) || !isDesiredBlock(pos)) { clearTarget(); return; }
        if (!safeStand(standingBlock)) { rememberFailedTarget(pos); clearTarget(); return; }
        BlockState state = level.getBlockState(pos);
        ServerWorld server = (ServerWorld) level;
        List<ItemStack> loot = Block.getDrops(state, server, pos, level.getBlockEntity(pos), this, tool);
        // Colhe somente o bloco achado fisicamente. Nunca inventa itens nem usa raio-X.
        if (level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
            swing(Hand.MAIN_HAND);
            for (ItemStack drop : loot) {
                ItemStack remain = store(drop.copy());
                if (!remain.isEmpty()) Block.popResource(level, pos, remain);
            }
            damageTool(slot);
            successes[job] = Math.min(100, successes[job] + 1);
            spendEnergy();
            cooldown = job == Alvos.MINERAR ? 55 : 44;
        }
        clearTarget();
    }

    private void scanForBlocks(int budget) {
        for (int searched = 0; searched < budget; searched++) {
            if (scanIndex >= SEARCH_ORDER.size()) { finishJob(); return; }
            BlockPos pos = home.offset(SEARCH_ORDER.get(scanIndex++));
            if (!level.isLoaded(pos) || isTemporarilyBlocked(pos) || !isDesiredBlock(pos)) continue;
            BlockPos stand = findStand(pos);
            if (stand == null) continue;
            targetBlock = pos;
            standingBlock = stand;
            unreachableTicks = 0;
            if (distanceToSqr(stand.getX() + .5D, stand.getY(), stand.getZ() + .5D) > 3.1D
                    && !getNavigation().moveTo(stand.getX() + .5D, stand.getY(), stand.getZ() + .5D, 1.0D)) {
                rememberFailedTarget(pos);
                clearTarget();
            }
            return;
        }
    }

    private void rememberFailedTarget(BlockPos pos) {
        if (pos == null) return;
        if (failedLocations.size() >= 32) {
            Iterator<Long> keys = failedLocations.keySet().iterator();
            if (keys.hasNext()) failedLocations.remove(keys.next());
        }
        failedLocations.put(pos.asLong(), tickCount + 1200);
    }

    private boolean isTemporarilyBlocked(BlockPos pos) {
        Integer until = failedLocations.get(pos.asLong());
        if (until == null) return false;
        if (until <= tickCount) { failedLocations.remove(pos.asLong()); return false; }
        return true;
    }

    private boolean isDesiredBlock(BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        if (getJob() == Alvos.MINERAR) return block == Alvos.MINERIOS[getChoice()];
        if (getJob() == Alvos.MADEIRA) return block == Alvos.MADEIRAS[getChoice()] && hasLeavesNearby(pos);
        return false;
    }

    /** Procura a copa seguindo a coluna do tronco, sem carregar novos chunks. */
    private boolean hasLeavesNearby(BlockPos origin) {
        Block trunk = level.getBlockState(origin).getBlock();
        for (int y = 0; y <= 16; y++) {
            BlockPos checkTrunk = origin.above(y);
            if (!level.isLoaded(checkTrunk)) return false;
            if (y > 0 && level.getBlockState(checkTrunk).getBlock() != trunk) {
                // As folhas tambem podem estar um ou dois blocos acima do ultimo tronco.
                for (int above = 0; above <= 2; above++) {
                    if (folhagemNoNivel(checkTrunk.above(above))) return true;
                }
                return false;
            }
            if (folhagemNoNivel(checkTrunk)) return true;
        }
        return false;
    }

    private boolean folhagemNoNivel(BlockPos center) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos around = center.offset(dx, 0, dz);
                if (!level.isLoaded(around)) continue;
                Block block = level.getBlockState(around).getBlock();
                if (block instanceof LeavesBlock || block == Blocks.NETHER_WART_BLOCK
                        || block == Blocks.WARPED_WART_BLOCK) return true;
            }
        }
        return false;
    }

    @Nullable
    private BlockPos findStand(BlockPos target) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = target.relative(direction);
            if (!level.isLoaded(pos) || !level.isLoaded(pos.above()) || !level.isLoaded(pos.below())) continue;
            if (safeStand(pos)) return pos;
        }
        return null;
    }

    private boolean safeStand(BlockPos pos) {
        boolean clear = level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above());
        boolean floor = !level.isEmptyBlock(pos.below()) && !dangerous(level.getBlockState(pos.below()).getBlock());
        boolean hazard = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos around = pos.relative(dir);
            if (level.isLoaded(around) && dangerous(level.getBlockState(around).getBlock())) {
                hazard = true;
                break;
            }
        }
        return Cerebro.podePassar(floor, clear, hazard);
    }

    private boolean dangerous(Block block) {
        return block == Blocks.LAVA || block == Blocks.WATER || block == Blocks.FIRE || block == Blocks.SOUL_FIRE
                || block == Blocks.CACTUS || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE;
    }

    private void huntTick() {
        EntityType<?> wanted = Alvos.targetMob(getJob(), getChoice());
        if (wanted == null) { finishJob(); return; }
        if (targetMob != null && !targetMob.isAlive() && attackedCurrentTarget) {
            // So recolhe drops quando o alvo que o mini atingiu realmente morreu.
            pickupPosition = targetMob.blockPosition().immutable();
            pickupWindow = 115;
            attackedCurrentTarget = false;
        }
        if (targetMob == null || !targetMob.isAlive() || targetMob.getType() != wanted) {
            targetMob = null;
            attackedCurrentTarget = false;
            if (tickCount % 70 != 0) return;
            AxisAlignedBB area = new AxisAlignedBB(home).inflate(RANGE, HEIGHT, RANGE);
            List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, area,
                    mob -> mob.isAlive() && !mob.hasCustomName() && mob.getType() == wanted
                            && (!(mob instanceof AnimalEntity) || !((AnimalEntity) mob).isBaby())
                            && !(mob instanceof TameableEntity)
                            && mob.distanceToSqr(this) < (RANGE + 4) * (RANGE + 4));
            double best = Double.MAX_VALUE;
            for (LivingEntity mob : nearby) {
                if (mob.distanceToSqr(this) < best) { best = mob.distanceToSqr(this); targetMob = mob; }
            }
            if (targetMob == null) {
                if (++huntMisses >= 5) finishJob();
                return;
            }
            huntMisses = 0;
            unreachableTicks = 0;
            attackedCurrentTarget = false;
        }
        if (targetMob.blockPosition().distSqr(home) > (RANGE + 2) * (RANGE + 2)
                || (level.isLoaded(targetMob.blockPosition())
                    && dangerous(level.getBlockState(targetMob.blockPosition()).getBlock()))) {
            targetMob = null;
            attackedCurrentTarget = false;
            return;
        }
        if (distanceToSqr(targetMob) > 4.5D || !canSee(targetMob)) {
            if (tickCount % 18 == 0 && !getNavigation().moveTo(targetMob, 1.08D)) unreachableTicks += 18;
            if (++unreachableTicks > 260) {
                targetMob = null;
                attackedCurrentTarget = false;
                getNavigation().stop();
            }
            return;
        }
        getNavigation().stop();
        if (cooldown > 0) return;
        ItemStack sword = tools.get(SWORD);
        int tier = ((TieredItem) sword.getItem()).getTier().getLevel();
        swing(Hand.MAIN_HAND);
        if (targetMob.hurt(DamageSource.mobAttack(this), 2.0F + Math.min(tier, 4) * 0.85F)) {
            // Monstros podem reagir ao ataque. Caçar continua tendo risco real.
            if (targetMob instanceof MonsterEntity) ((MonsterEntity) targetMob).setTarget(this);
            damageTool(SWORD);
            if (!targetMob.isAlive()) successes[getJob()] = Math.min(100, successes[getJob()] + 1);
            spendEnergy();
            attackedCurrentTarget = true;
        }
        cooldown = 25;
    }

    private void collectNearbyDrops() {
        if (pickupPosition == null || pickupWindow <= 0) return;
        final BlockPos center = pickupPosition;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(2.4D),
                drop -> drop.isAlive() && drop.tickCount < 105
                        && drop.distanceToSqr(center.getX() + 0.5D, center.getY() + 0.5D,
                                center.getZ() + 0.5D) < 4.0D)) {
            ItemStack remaining = store(item.getItem().copy());
            if (remaining.isEmpty()) item.remove();
            else item.setItem(remaining);
        }
    }

    private ItemStack store(ItemStack given) {
        if (given.isEmpty()) return ItemStack.EMPTY;
        for (int i = 0; i < STORAGE_SIZE && !given.isEmpty(); i++) {
            ItemStack current = storage.get(i);
            if (!current.isEmpty() && ItemStack.isSame(current, given) && ItemStack.tagMatches(current, given)) {
                int amount = Math.min(given.getCount(), current.getMaxStackSize() - current.getCount());
                if (amount > 0) { current.grow(amount); given.shrink(amount); }
            }
        }
        for (int i = 0; i < STORAGE_SIZE && !given.isEmpty(); i++) {
            if (storage.get(i).isEmpty()) {
                int amount = Math.min(given.getCount(), given.getMaxStackSize());
                ItemStack part = given.copy();
                part.setCount(amount);
                storage.set(i, part);
                given.shrink(amount);
            }
        }
        return given;
    }

    private boolean isFull() {
        for (ItemStack item : storage) if (item.isEmpty() || item.getCount() < item.getMaxStackSize()) return false;
        return true;
    }

    private void damageTool(int slot) {
        ItemStack item = tools.get(slot);
        if (item.isEmpty()) return;
        item.hurtAndBreak(1, this, entity -> entity.broadcastBreakEvent(Hand.MAIN_HAND));
        if (item.isEmpty()) tools.set(slot, ItemStack.EMPTY);
        refreshHeldItem();
    }

    private void clearTarget() {
        targetBlock = null;
        standingBlock = null;
        unreachableTicks = 0;
        getNavigation().stop();
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource damage, int looting, boolean hitByPlayer) {
        super.dropCustomDeathLoot(damage, looting, hitByPlayer);
        for (int i = 0; i < tools.size(); i++) {
            if (!tools.get(i).isEmpty()) spawnAtLocation(tools.get(i).copy());
            tools.set(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < storage.size(); i++) {
            if (!storage.get(i).isEmpty()) spawnAtLocation(storage.get(i).copy());
            storage.set(i, ItemStack.EMPTY);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        tag.putLong("Home", home.asLong());
        tag.putBoolean("Alex", isAlex());
        tag.putInt("Job", getJob());
        tag.putInt("Choice", getChoice());
        tag.putInt("Food", getFoodLevel());
        tag.putBoolean("Follow", isFollowing());
        tag.putBoolean("Returning", isReturning());
        tag.putBoolean("Autonomous", isAutonomous());
        tag.putBoolean("Recovery", recoveryPause);
        tag.putInt("AuthorisedJobs", authorisedJobs);
        tag.putInt("LastAutoJob", lastAutoJob);
        tag.putIntArray("PreferredTarget", preferredTarget);
        tag.putIntArray("Preferences", preferences);
        tag.putIntArray("Successes", successes);
        tag.putInt("WorkActions", workActions);
        for (int i = 0; i < 3; i++) if (!tools.get(i).isEmpty()) tag.put("Tool" + i, tools.get(i).save(new CompoundNBT()));
        ListNBT saved = new ListNBT();
        for (int i = 0; i < STORAGE_SIZE; i++) {
            if (!storage.get(i).isEmpty()) {
                CompoundNBT stack = storage.get(i).save(new CompoundNBT());
                stack.putByte("Slot", (byte) i);
                saved.add(stack);
            }
        }
        tag.put("Storage", saved);
    }

    @Override
    public void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) ownerId = tag.getUUID("Owner");
        home = tag.contains("Home") ? BlockPos.of(tag.getLong("Home")) : blockPosition();
        entityData.set(ALEX, tag.getBoolean("Alex"));
        entityData.set(FOOD, tag.contains("Food") ? Math.max(0, Math.min(20, tag.getInt("Food"))) : 16);
        entityData.set(FOLLOW, !tag.contains("Follow") || tag.getBoolean("Follow"));
        entityData.set(RETURNING, tag.getBoolean("Returning"));
        entityData.set(AUTO, tag.getBoolean("Autonomous"));
        recoveryPause = tag.getBoolean("Recovery");
        authorisedJobs = tag.getInt("AuthorisedJobs") & 15;
        lastAutoJob = tag.getInt("LastAutoJob");
        restoreArray(tag.getIntArray("PreferredTarget"), preferredTarget);
        restoreArray(tag.getIntArray("Preferences"), preferences);
        restoreArray(tag.getIntArray("Successes"), successes);
        for (int task = 1; task <= 4; task++) {
            if (!Alvos.valid(task, preferredTarget[task])) {
                authorisedJobs &= ~(1 << (task - 1));
                preferredTarget[task] = 0;
            }
        }
        workActions = tag.getInt("WorkActions");
        for (int i = 0; i < 3; i++) {
            tools.set(i, tag.contains("Tool" + i) ? ItemStack.of(tag.getCompound("Tool" + i)) : ItemStack.EMPTY);
        }
        for (int i = 0; i < STORAGE_SIZE; i++) storage.set(i, ItemStack.EMPTY);
        ListNBT saved = tag.getList("Storage", 10);
        for (int i = 0; i < saved.size(); i++) {
            CompoundNBT item = saved.getCompound(i);
            int slot = item.getByte("Slot") & 255;
            if (slot < STORAGE_SIZE) storage.set(slot, ItemStack.of(item));
        }
        int job = tag.getInt("Job"), choice = tag.getInt("Choice");
        if (Alvos.valid(job, choice)) {
            entityData.set(JOB, job);
            entityData.set(CHOICE, choice);
        }
        refreshHeldItem();
    }

    private static void restoreArray(int[] from, int[] into) {
        if (from.length == into.length) {
            for (int i = 0; i < into.length; i++) into[i] = Math.max(0, Math.min(100, from[i]));
        }
    }
}
