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
import net.minecraft.entity.ai.goal.SwimGoal;
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
    // 0 parado, 1 minerando/cortando, 2 golpeando. Sincronizado para animar no cliente.
    private static final int ACTION_IDLE = 0, ACTION_BREAK = 1, ACTION_ATTACK = 2;
    private static final DataParameter<Integer> ACTION = EntityDataManager.defineId(CompanheiroEntity.class, DataSerializers.INT);

    // Raio local de deteccao e raio maximo de exploracao (sem carregar chunks).
    private static final int RANGE = 16;
    private static final int EXPLORE_RADIUS = 80;
    private static final int EXPLORE_STEP = 12;
    private static final int EXPLORE_REACH = 28;
    private static final int MAX_SCOUT_TICKS = 220;
    private static final List<BlockPos> SCOUT_SPOTS = createScoutSpots();

    private static List<BlockPos> createScoutSpots() {
        List<BlockPos> offsets = new ArrayList<>();
        int sections = EXPLORE_RADIUS / EXPLORE_STEP;
        for (int x = -sections; x <= sections; x++) {
            for (int z = -sections; z <= sections; z++) {
                int xx = x * EXPLORE_STEP;
                int zz = z * EXPLORE_STEP;
                if (xx * xx + zz * zz <= EXPLORE_RADIUS * EXPLORE_RADIUS) {
                    offsets.add(new BlockPos(xx, 0, zz));
                }
            }
        }
        return Collections.unmodifiableList(offsets);
    }
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
    // A busca local e repetida a cada novo ponto fisicamente visitado.
    private BlockPos scanCenter = BlockPos.ZERO;
    private final boolean[] scouted = new boolean[SCOUT_SPOTS.size()];
    @Nullable private BlockPos scoutGoal;
    private int scoutTicks;
    private int huntMisses;
    private int unreachableTicks;
    private int cooldown;
    private int pickupWindow;
    @Nullable private BlockPos pickupPosition;
    private boolean attackedCurrentTarget;
    private int workActions;
    private int healingTicks;
    private boolean recoveryPause;
    // Progresso visual REAL da quebra (cada etapa e enviada aos jogadores proximos).
    @Nullable private BlockPos breakingBlock;
    private int breakingTicks;
    private int breakingDuration;
    private int breakingStage = -1;
    private int combatPoseTicks;
    private int autonomousActions;
    private int autoTaskDeadline;
    private boolean switchAfterAttack;
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
                .add(Attributes.FOLLOW_RANGE, 32.0D)
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
        entityData.define(ACTION, ACTION_IDLE);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new SwimGoal(this));
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
    public boolean isBreakingBlockAnimation() { return entityData.get(ACTION) == ACTION_BREAK; }
    public boolean isAttackingAnimation() { return entityData.get(ACTION) == ACTION_ATTACK; }

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
        // Ao escolher mais um alvo com IA ativa, a IA permanece ligada.
        // O comando Parar e que desativa o modo automatico.
        if (job == Alvos.PARADO) entityData.set(AUTO, false);
        if (job != Alvos.PARADO) {
            authorisedJobs |= 1 << (job - 1);
            preferredTarget[job] = choice;
            preferences[job] = Math.min(16, preferences[job] + 2);
        }
        configureJob(job, choice, job != Alvos.PARADO);
    }

    private void configureJob(int job, int choice, boolean changeHome) {
        stopBreakingAnimation();
        combatPoseTicks = 0;
        switchAfterAttack = false;
        entityData.set(ACTION, ACTION_IDLE);
        entityData.set(JOB, job);
        entityData.set(CHOICE, choice);
        entityData.set(RETURNING, false);
        if (changeHome) home = blockPosition().immutable();
        scanIndex = 0;
        scanCenter = blockPosition().immutable();
        Arrays.fill(scouted, false);
        scoutGoal = null;
        scoutTicks = 0;
        huntMisses = 0;
        targetBlock = null;
        standingBlock = null;
        targetMob = null;
        attackedCurrentTarget = false;
        // No modo IA, conserva por alguns segundos os drops de uma caca anterior.
        if (!isAutonomous()) {
            pickupWindow = 0;
            pickupPosition = null;
        }
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
        // Modo automatico agora descobre categorias SEGURAS com ferramentas equipadas.
        // Categorias adicionadas implicitamente buscam carvao, carvalho e vacas.
        // Monstros NUNCA sao habilitados automaticamente.
        for (int job = Alvos.MINERAR; job <= Alvos.CACAR; job++) {
            int bit = 1 << (job - 1);
            if ((authorisedJobs & bit) == 0 && hasToolFor(job, 0)) {
                authorisedJobs |= bit;
                preferredTarget[job] = 0;
            }
        }
        boolean hasAny = false;
        for (int job = 1; job <= 4; job++) {
            if ((authorisedJobs & (1 << (job - 1))) != 0 && hasToolFor(job, preferredTarget[job])) {
                hasAny = true;
            }
        }
        if (!hasAny) {
            player.displayClientMessage(new StringTextComponent("IA: equipe ferramenta adequada (Shift + clique direito)."), true);
            return;
        }
        entityData.set(AUTO, true);
        home = blockPosition().immutable();
        Arrays.fill(blockedJobUntil, 0);
        nextAutoAttempt = 0;
        // Garante rotacao perceptivel, em vez de repetir a ordem manual indefinidamente.
        lastAutoJob = getJob();
        autonomousActions = 0;
        chooseNextAutonomousJob();
        player.displayClientMessage(new StringTextComponent("IA AUTOMATICA ligada: explorando com ferramentas disponiveis!"), true);
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
            autonomousActions = 0;
            autoTaskDeadline = tickCount + 500;
            configureJob(next, preferredTarget[next], false);
            PlayerEntity owner = ownerNearby();
            if (owner != null) {
                String tipo = next == Alvos.MINERAR ? "minerio" : next == Alvos.MADEIRA ? "madeira"
                        : next == Alvos.CACAR ? "animais" : "monstros";
                owner.displayClientMessage(new StringTextComponent("IA: explorando em busca de " + tipo + "."), true);
            }
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
        if (combatPoseTicks > 0 && --combatPoseTicks == 0 && entityData.get(ACTION) == ACTION_ATTACK) {
            entityData.set(ACTION, ACTION_IDLE);
            if (switchAfterAttack) {
                switchAfterAttack = false;
                // Guarda os drops da vitima antes de sair para outra tarefa.
                if (pickupWindow > 0) collectNearbyDrops();
                completedAutonomousAction();
            }
        }
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
            stopBreakingAnimation();
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
            stopBreakingAnimation();
            getNavigation().stop();
            followOwnerTick();
            return;
        }
        // A IA nao fica eternamente numa categoria sem resultados.
        if (isAutonomous() && tickCount >= autoTaskDeadline) {
            blockedJobUntil[getJob()] = tickCount + 200;
            chooseNextAutonomousJob();
            return;
        }
        // Nunca vagueia infinitamente: explora em torno do ponto da ordem.
        // Os chunks alem do carregamento normal do jogador nao sao ativados.
        if (blockPosition().distSqr(home) > (EXPLORE_RADIUS + 6) * (EXPLORE_RADIUS + 6)) {
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
            // Em vez de ficar procurando para sempre no mesmo lugar, caminhe
            // para o proximo setor e faca uma nova busca ali.
            if (scoutGoal != null) advanceScout();
            else if (tickCount % 4 == 0) scanForBlocks(300);
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
        if (!safeStand(standingBlock) || tool.isEmpty()) {
            rememberFailedTarget(pos); clearTarget(); return;
        }
        BlockState state = level.getBlockState(pos);
        ServerWorld server = (ServerWorld) level;
        if (breakingBlock == null || !breakingBlock.equals(pos)) {
            stopBreakingAnimation();
            float hardness = state.getDestroySpeed(level, pos);
            if (hardness < 0) { rememberFailedTarget(pos); clearTarget(); return; }
            breakingBlock = pos.immutable();
            breakingTicks = 0;
            breakingStage = -1;
            int tier = tool.getItem() instanceof TieredItem ? ((TieredItem) tool.getItem()).getTier().getLevel() : 0;
            // Mais duros levam mais tempo. Cada bloco leva ao menos 24 ticks
            // para as rachaduras serem perceptiveis, sem mineracao instantanea.
            breakingDuration = Math.max(24, Math.min(160,
                    Math.round((22.0F + hardness * 15.0F) / (1.0F + tier * 0.20F))));
            entityData.set(ACTION, ACTION_BREAK);
        }
        getLookControl().setLookAt(pos.getX() + .5D, pos.getY() + .5D, pos.getZ() + .5D);
        if (breakingTicks % 8 == 0) swing(Hand.MAIN_HAND);
        breakingTicks++;
        int stage = Math.min(9, (breakingTicks * 10) / breakingDuration);
        if (stage != breakingStage) {
            breakingStage = stage;
            server.destroyBlockProgress(getId(), pos, stage);
        }
        if (breakingTicks < breakingDuration) return;

        // Recursos continuam vindo APENAS do bloco existente no mundo.
        List<ItemStack> loot = Block.getDrops(state, server, pos, level.getBlockEntity(pos), this, tool);
        boolean broke = level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (broke) {
            // Efeito vanilla de fragmentos e som de quebra, alem das rachaduras graduais.
            server.levelEvent(2001, pos, Block.getId(state));
            for (ItemStack drop : loot) {
                ItemStack remain = store(drop.copy());
                if (!remain.isEmpty()) Block.popResource(level, pos, remain);
            }
            damageTool(slot);
            successes[job] = Math.min(100, successes[job] + 1);
            spendEnergy();
            cooldown = job == Alvos.MINERAR ? 15 : 12;
        }
        clearTarget();
        if (broke) completedAutonomousAction();
    }

    private void stopBreakingAnimation() {
        if (breakingBlock != null && !level.isClientSide) {
            ((ServerWorld) level).destroyBlockProgress(getId(), breakingBlock, -1);
        }
        breakingBlock = null;
        breakingTicks = 0;
        breakingDuration = 0;
        breakingStage = -1;
        if (entityData.get(ACTION) == ACTION_BREAK) entityData.set(ACTION, ACTION_IDLE);
    }

    /** Apos dois trabalhos concluidos, troca de categoria se a IA estiver ligada. */
    private void completedAutonomousAction() {
        if (!isAutonomous() || getJob() == Alvos.PARADO) return;
        if (++autonomousActions >= 2) chooseNextAutonomousJob();
    }

    private void scanForBlocks(int budget) {
        for (int searched = 0; searched < budget; searched++) {
            if (scanIndex >= SEARCH_ORDER.size()) { startNextScoutLeg(); return; }
            BlockPos pos = scanCenter.offset(SEARCH_ORDER.get(scanIndex++));
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

    /** Busca pontos seguros a ~12 blocos de distancia, avancando ate 80 blocos.
     *  Cada novo ponto recebe uma varredura local completa.
     */
    private void startNextScoutLeg() {
        BlockPos here = blockPosition();
        for (int attempt = 0; attempt < 8; attempt++) {
            int bestIndex = -1;
            BlockPos bestSpot = null;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < SCOUT_SPOTS.size(); i++) {
                if (scouted[i]) continue;
                BlockPos offset = SCOUT_SPOTS.get(i);
                BlockPos intended = home.offset(offset);
                if (!level.isLoaded(intended)) continue;
                // Nao tenta atravessar o mapa diretamente: avanca um setor por vez.
                int dx = intended.getX() - here.getX();
                int dz = intended.getZ() - here.getZ();
                int horizontalSq = dx * dx + dz * dz;
                if (horizontalSq <= 16) { scouted[i] = true; continue; }
                if (horizontalSq > EXPLORE_REACH * EXPLORE_REACH) continue;
                BlockPos stand = findScoutStand(intended.getX(), intended.getZ());
                if (stand == null) continue;
                if (Math.abs(stand.getY() - here.getY()) > 7) continue;
                double distance = here.distSqr(stand);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                    bestSpot = stand;
                }
            }
            if (bestIndex == -1) { finishJob(); return; }
            // Tambem evita retornar para locais que o pathfinder considera inacessiveis.
            scouted[bestIndex] = true;
            if (bestSpot != null && getNavigation().moveTo(
                    bestSpot.getX() + 0.5D, bestSpot.getY(), bestSpot.getZ() + 0.5D, 1.03D)) {
                scoutGoal = bestSpot.immutable();
                scoutTicks = 0;
                return;
            }
        }
        // Todos os pontos proximos falharam; nao cria ticks infinitos de pathfinding.
        finishJob();
    }

    /** Procura chao firme no mesmo nivel de jogo (inclui cavernas). */
    @Nullable
    private BlockPos findScoutStand(int x, int z) {
        int baseY = blockPosition().getY();
        for (int offset = 0; offset <= 8; offset++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                if (offset == 0 && sign == 1) continue;
                BlockPos pos = new BlockPos(x, baseY + offset * sign, z);
                if (!level.isLoaded(pos) || !level.isLoaded(pos.above()) || !level.isLoaded(pos.below())) continue;
                if (safeStand(pos)) return pos;
            }
        }
        return null;
    }

    private void advanceScout() {
        if (scoutGoal == null) return;
        double d = distanceToSqr(scoutGoal.getX() + .5D, scoutGoal.getY(), scoutGoal.getZ() + .5D);
        if (d <= 4.0D) {
            scanCenter = blockPosition().immutable();
            scanIndex = 0;
            huntMisses = 0;
            scoutGoal = null;
            scoutTicks = 0;
            getNavigation().stop();
            return;
        }
        scoutTicks++;
        if (scoutTicks > MAX_SCOUT_TICKS) {
            scoutGoal = null;
            scoutTicks = 0;
            scanCenter = blockPosition().immutable();
            scanIndex = 0;
            getNavigation().stop();
            return;
        }
        if (tickCount % 24 == 0 && !getNavigation().moveTo(
                scoutGoal.getX() + .5D, scoutGoal.getY(), scoutGoal.getZ() + .5D, 1.03D)) {
            scoutGoal = null;
            scoutTicks = 0;
            scanCenter = blockPosition().immutable();
            scanIndex = 0;
            getNavigation().stop();
        }
        // A caminhada tambem consome fome; nao e transporte gratis de recursos.
        if (tickCount % 240 == 0) {
            entityData.set(FOOD, Regras.reduzirFome(getFoodLevel(), 1));
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
        if (scoutGoal != null) { advanceScout(); return; }
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
            AxisAlignedBB area = new AxisAlignedBB(blockPosition()).inflate(RANGE, HEIGHT, RANGE);
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
                if (++huntMisses >= 2) { huntMisses = 0; startNextScoutLeg(); }
                return;
            }
            huntMisses = 0;
            unreachableTicks = 0;
            attackedCurrentTarget = false;
        }
        if (targetMob.blockPosition().distSqr(home) > (EXPLORE_RADIUS + 2) * (EXPLORE_RADIUS + 2)
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
        entityData.set(ACTION, ACTION_ATTACK);
        combatPoseTicks = 10;
        swing(Hand.MAIN_HAND);
        boolean killed = false;
        if (targetMob.hurt(DamageSource.mobAttack(this), 2.0F + Math.min(tier, 4) * 0.85F)) {
            // Monstros podem reagir ao ataque. Caçar continua tendo risco real.
            if (targetMob instanceof MonsterEntity) ((MonsterEntity) targetMob).setTarget(this);
            damageTool(SWORD);
            if (!targetMob.isAlive()) {
                successes[getJob()] = Math.min(100, successes[getJob()] + 1);
                killed = true;
            }
            spendEnergy();
            attackedCurrentTarget = true;
        }
        cooldown = 25;
        if (killed) switchAfterAttack = true;
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
        stopBreakingAnimation();
        targetBlock = null;
        standingBlock = null;
        unreachableTicks = 0;
        getNavigation().stop();
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource damage, int looting, boolean hitByPlayer) {
        stopBreakingAnimation();
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
        int[] seen = new int[scouted.length];
        for (int i = 0; i < scouted.length; i++) seen[i] = scouted[i] ? 1 : 0;
        tag.putIntArray("ExplorationSeen", seen);
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
        entityData.set(ACTION, ACTION_IDLE);
        breakingBlock = null;
        breakingTicks = 0;
        combatPoseTicks = 0;
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
        scanCenter = blockPosition().immutable();
        scanIndex = 0;
        scoutGoal = null;
        scoutTicks = 0;
        int[] seen = tag.getIntArray("ExplorationSeen");
        for (int i = 0; i < scouted.length && i < seen.length; i++) scouted[i] = seen[i] != 0;
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

package br.com.minicompanheiros.client;

import br.com.minicompanheiros.CompanheiroEntity;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HeldItemLayer;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.entity.model.PlayerModel;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

/** Modelo vanilla com animacao dedicada para mineracao, madeira e combate. */
public class RenderCompanheiro extends MobRenderer<CompanheiroEntity, PlayerModel<CompanheiroEntity>> {
    private static final ResourceLocation STEVE = new ResourceLocation("minecraft", "textures/entity/steve.png");
    private static final ResourceLocation ALEX = new ResourceLocation("minecraft", "textures/entity/alex.png");
    private final PlayerModel<CompanheiroEntity> normal = new AnimatedModel(false);
    private final PlayerModel<CompanheiroEntity> slim = new AnimatedModel(true);

    public RenderCompanheiro(EntityRendererManager manager) {
        super(manager, new AnimatedModel(false), 0.24F);
        // Espada, picareta e machado continuam visiveis na mao.
        addLayer(new HeldItemLayer<>(this));
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
            matrix.scale(0.63F, 0.63F, 0.63F);
            super.render(helper, yaw, partialTicks, matrix, buffer, light);
        } finally {
            matrix.popPose();
        }
    }

    private static final class AnimatedModel extends PlayerModel<CompanheiroEntity> {
        private AnimatedModel(boolean slim) { super(0.0F, slim); }

        @Override
        public void setupAnim(CompanheiroEntity helper, float limbSwing, float limbSwingAmount,
                              float ageInTicks, float headYaw, float headPitch) {
            super.setupAnim(helper, limbSwing, limbSwingAmount, ageInTicks, headYaw, headPitch);
            if (helper.isBreakingBlockAnimation()) {
                // Golpe para baixo, repetido, como um jogador minerando ou cortando lenha.
                this.rightArm.xRot = -1.5F + 0.60F * MathHelper.sin(ageInTicks * 1.45F);
                this.rightArm.yRot = -0.15F;
            } else if (helper.isAttackingAnimation()) {
                // Ataque rapido com a espada (sinc. do servidor para clientes).
                this.rightArm.xRot = -1.2F + 0.7F * MathHelper.sin(ageInTicks * 2.1F);
                this.rightArm.yRot = -0.30F;
            }
            // A manga acompanha o braco, evitando desencaixe visual.
            this.rightSleeve.xRot = this.rightArm.xRot;
            this.rightSleeve.yRot = this.rightArm.yRot;
            this.rightSleeve.zRot = this.rightArm.zRot;
        }
    }
}

MINI STEVE & MINI ALEX - IA + ANIMACOES (V0.5)
Minecraft 1.16.5 | Forge 36.1.0 | OptiFine HD U G8: COMPATIBILIDADE A TESTAR

ATUALIZACAO MINIMA DO MOD QUE VOCE JA COMPILOU NO GITHUB:

1) Em seu GitHub, abra:
   src/main/java/br/com/minicompanheiros/CompanheiroEntity.java
   Clique no lapis (editar), apague todo o conteudo e cole o texto do
   CompanheiroEntity.java NOVO que esta neste pacote. Commit changes.

2) Faca a mesma coisa no caminho:
   src/main/java/br/com/minicompanheiros/client/RenderCompanheiro.java
   usando o RenderCompanheiro.java NOVO. Commit changes.

3) Actions -> Compilar Mini Steve e Mini Alex 1.16.5 -> Run workflow.
   Se ficar VERDE, abra a execucao e baixe o Artifacts do .jar.
   Se ficar VERMELHO, copie as primeiras linhas com 'error:' e envie ao ChatGPT.

4) Remova o .jar ANTIGO da pasta mods. Coloque somente o .jar NOVO.
   Teste primeiro em COPIA do mundo, sem OptiFine, depois com OptiFine.

TESTE COMPORTAMENTO:
- Equipe o mini com picareta (Shift+clique direito) e machado.
- IA LIGADA: deve alternar minerar carvao, cortar carvalho e cacas de vacas
  (se houver espada), usando apenas categorias para as quais tem ferramentas.
- O menu manual ainda permite escolher outro minerio, madeira ou animal.
- Monstros continuam so com ordem explicita; a IA nao habilita monstros sozinha.
- Ao encostar no alvo, ele deve mover o braco por varios ticks.
- O bloco deve ficar rachado (10 estagios) antes de desaparecer.
- Se interromper a ordem, as rachaduras devem desaparecer.

OBS: Mantivemos o nome do arquivo JAR da versao 0.3.0 para evitar editar
outros arquivos do seu projeto. O CODIGO e V0.5.
NAO E UM JAR PRE-COMPILADO. A compilacao Forge precisa passar no GitHub.
