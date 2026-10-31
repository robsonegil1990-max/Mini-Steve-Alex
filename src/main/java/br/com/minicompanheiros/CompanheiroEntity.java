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
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkHooks;
import net.minecraftforge.fml.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
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
    // Survival 1.16.5: 4.5 blocks for blocks; 3 blocks for melee.
    private static final double BLOCK_REACH = 4.5D;
    private static final double MELEE_REACH = 3.0D;
    private static final int DIAMOND_CHOICE = 3;
    private static final int DIAMOND_TUNNEL_Y = 11;
    private static final int MAX_TUNNEL_STEPS = 200;
    private static final int MAX_TREE_LOGS = 320;
    private static final int TREE_RADIUS = 8;
    private static final int TREE_HEIGHT = 38;
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
    // State machine: safe two-block-high stairs down to Y=11, then branch tunnels.
    @Nullable private BlockPos tunnelNextFoot;
    @Nullable private Direction tunnelDirection;
    private int tunnelSteps;
    private int tunnelMoveTicks;
    private boolean tunnelDescending;
    private int tunnelFailedDirections;
    private int tunnelSegmentSteps;
    private int tunnelSegmentLength = 8;
    private int tunnelTurns;
    // Caminho que o proprio mini abriu: permite voltar pela escada em vez de
    // tentar atravessar a montanha quando a tarefa terminar.
    private final List<BlockPos> tunnelTrail = new ArrayList<>();
    private BlockPos lastReturnProgress = BlockPos.ZERO;
    private int returnStuckTicks;
    private int lastTreeWarning = -9999;
    private BlockPos lastScoutPosition = BlockPos.ZERO;
    private int scoutStuckChecks;
    private int autonomousActions;
    private int autoTaskDeadline;
    private boolean switchAfterAttack;
    private int nextAutoAttempt;
    private int lastAutoJob;
    // Objetivos persistentes: nao muda de trabalho a cada dois blocos.
    private int autoGoalStarted;
    private int lastNeedMessage;
    // Ameaça de autodefesa nao altera ordens do jogador.
    @Nullable private MonsterEntity defensiveThreat;
    // Detector geral de travamento, sem teleportar ou atravessar paredes.
    private BlockPos lastTravelPosition = BlockPos.ZERO;
    private int travelStuckChecks;
    // Evita vasculhar monstros do mundo todo a cada tick.
    private int nextDefenseScan;
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
        tunnelNextFoot = null;
        tunnelDirection = null;
        tunnelSteps = 0;
        tunnelMoveTicks = 0;
        tunnelDescending = false;
        tunnelFailedDirections = 0;
        tunnelSegmentSteps = 0;
        tunnelSegmentLength = 8;
        tunnelTurns = 0;
        if (changeHome) tunnelTrail.clear();
        returnStuckTicks = 0;
        scoutStuckChecks = 0;
        lastTravelPosition = blockPosition().immutable();
        travelStuckChecks = 0;
        defensiveThreat = null;
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
        autoGoalStarted = tickCount;
        lastNeedMessage = -1000;
        nextAutoAttempt = 0;
        // Garante rotacao perceptivel, em vez de repetir a ordem manual indefinidamente.
        lastAutoJob = getJob();
        autonomousActions = 0;
        chooseNextAutonomousJob();
        player.displayClientMessage(new StringTextComponent("IA AUTOMATICA ligada: explorando com ferramentas disponiveis!"), true);
    }

    private void chooseNextAutonomousJob() {
        // Se o mineiro esta subterraneo, regressa pela escada ANTES de trocar
        // para arvores/animais. Do contrario ele ficaria perdido sob a terra.
        if (tunnelTrail.size() > 1) {
            configureJob(Alvos.PARADO, 0, false);
            entityData.set(RETURNING, true);
            nextAutoAttempt = tickCount + 40;
            return;
        }
        boolean[] availableTools = new boolean[5];
        for (int job = 1; job <= 4; job++) {
            availableTools[job] = hasToolFor(job, preferredTarget[job]);
        }
        int next = Cerebro.escolherTarefa(authorisedJobs, availableTools, blockedJobUntil,
                tickCount, lastAutoJob, preferences, successes);
        // Um jogador busca comida antes de continuar trabalhando com fome.
        // A rotina continua respeitando categorias e ferramentas autorizadas.
        if (getFoodLevel() <= 12 && !hasFoodInBag()
                && canAutoChoose(Alvos.CACAR, availableTools)) {
            next = Alvos.CACAR;
        } else if (countLogsInBag() < 12 && canAutoChoose(Alvos.MADEIRA, availableTools)
                && lastAutoJob != Alvos.MADEIRA) {
            next = Alvos.MADEIRA;
        }
        if (next == 0) {
            configureJob(Alvos.PARADO, 0, false);
            entityData.set(RETURNING, true);
            nextAutoAttempt = tickCount + 200;
        } else {
            lastAutoJob = next;
            autonomousActions = 0;
            autoGoalStarted = tickCount;
            // Mantem o objetivo por tempo suficiente para parecer uma expedicao de verdade.
            // Diamante continua exigindo tempo extra para cavar a descida.
            autoTaskDeadline = tickCount + (next == Alvos.MINERAR && preferredTarget[next] == DIAMOND_CHOICE
                    ? 9600 : 3200);
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
            } else if (player.isShiftKeyDown() && held.getItem() == Items.TORCH) {
                supplyTorches(player, held);
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

    /** O dono pode abastecer a mochila com tochas reais (Shift + clique direito). */
    private void supplyTorches(PlayerEntity player, ItemStack stack) {
        ItemStack offered = stack.copy();
        ItemStack leftover = store(offered);
        int accepted = stack.getCount() - leftover.getCount();
        if (!player.abilities.instabuild) stack.shrink(accepted);
        player.displayClientMessage(new StringTextComponent(accepted > 0
                ? "Mini: recebi " + accepted + " tocha(s) para iluminar a mina."
                : "Mini: mochila cheia. Recolha os itens antes de me dar tochas."), true);
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
        // O mini nao espera ate ficar prestes a morrer de fome para comer.
        if (getFoodLevel() <= 12) eatFromOwnBag();
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
                escapeDangerTick();
                return;
            }
        }
        // Nao deixa a IA ignorar um zumbi que esteja realmente atacando o ajudante.
        // Defender-se nao autoriza sair caçando monstros indiscriminadamente.
        if (selfDefenseTick()) return;
        if (isAutonomous() && tickCount % 20 == 0) checkTravelStuck();
        if (getJob() == Alvos.PARADO) {
            // Sair da mina precede o proximo ciclo automatico de trabalho.
            if (!tunnelTrail.isEmpty()) {
                followOwnerTick();
                return;
            }
            if (isAutonomous() && tickCount >= nextAutoAttempt && getFoodLevel() >= 9 && !isFull()) {
                chooseNextAutonomousJob();
            }
            if (getJob() == Alvos.PARADO && (isFollowing() || isReturning())) followOwnerTick();
            return;
        }
        // Prioridade humana: comida, saude e ferramentas antes de continuar produzindo.
        if (isAutonomous() && getFoodLevel() <= 4 && !hasFoodInBag()) {
            pauseAutoForSupplies("estou com fome; preciso de alimento.");
            return;
        }
        if (isAutonomous() && toolAlmostBroken()) {
            pauseAutoForSupplies("minha ferramenta esta quase quebrando.");
            return;
        }
        if (isAutonomous() && !hasRequiredTool()) {
            pauseAutoForSupplies("preciso de uma ferramenta para continuar.");
            return;
        }
        int plan = Cerebro.decidir(getHealth(), getMaxHealth(), getFoodLevel(), false,
                hasFoodInBag(), isFull(), hasRequiredTool());
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
        // Horizontal limit: a downward staircase should not consume radius in Y.
        // Does not force-load chunks outside the owner's normal render distance.
        double dxHome = getX() - home.getX(), dzHome = getZ() - home.getZ();
        if (dxHome * dxHome + dzHome * dzHome
                > (EXPLORE_RADIUS + 6) * (EXPLORE_RADIUS + 6)) {
            if (tickCount % 24 == 0) getNavigation().moveTo(home.getX() + .5D, home.getY(), home.getZ() + .5D, 1.0D);
            return;
        }
        if (getJob() == Alvos.CACAR || getJob() == Alvos.MONSTROS) huntTick();
        else gatherTick();
    }

    private boolean canAutoChoose(int job, boolean[] availableTools) {
        return job > 0 && job < availableTools.length && availableTools[job]
                && (authorisedJobs & (1 << (job - 1))) != 0
                && tickCount >= blockedJobUntil[job];
    }

    private boolean hasFoodInBag() {
        for (ItemStack stack : storage) {
            if (!stack.isEmpty() && stack.getItem().isEdible()
                    && stack.getItem().getFoodProperties() != null
                    && stack.getItem().getFoodProperties().getNutrition() >= 2
                    && stack.getItem() != Items.ROTTEN_FLESH
                    && stack.getItem() != Items.POISONOUS_POTATO
                    && stack.getItem() != Items.SPIDER_EYE
                    && stack.getItem() != Items.PUFFERFISH
                    && stack.getItem() != Items.CHICKEN) return true;
        }
        return false;
    }

    private int countLogsInBag() {
        int count = 0;
        for (ItemStack stack : storage) {
            if (stack.isEmpty()) continue;
            for (Block log : Alvos.MADEIRAS) {
                if (stack.getItem() == log.asItem()) { count += stack.getCount(); break; }
            }
        }
        return count;
    }

    private boolean toolAlmostBroken() {
        int job = getJob();
        if (job == Alvos.PARADO) return false;
        ItemStack tool = tools.get(job == Alvos.MINERAR ? PICK : job == Alvos.MADEIRA ? AXE : SWORD);
        return !tool.isEmpty() && tool.isDamageableItem()
                && tool.getMaxDamage() - tool.getDamageValue() <= 3;
    }

    private void pauseAutoForSupplies(String reason) {
        stopBreakingAnimation();
        configureJob(Alvos.PARADO, 0, false);
        entityData.set(RETURNING, true);
        nextAutoAttempt = tickCount + 200;
        if (tickCount - lastNeedMessage >= 200) {
            lastNeedMessage = tickCount;
            PlayerEntity owner = ownerNearby();
            if (owner != null) owner.displayClientMessage(new StringTextComponent("Mini: " + reason), true);
        }
    }

    /** Usa apenas alimento fisicamente presente na mochila e nao come itens perigosos. */
    private boolean eatFromOwnBag() {
        if (getFoodLevel() > 12) return false;
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

    /** Retira-se do perigo real em vez de apenas ficar olhando para o dono. */
    private void escapeDangerTick() {
        MonsterEntity enemy = nearestThreat(9.0D, false);
        if (enemy != null && tickCount % 16 == 0) {
            double awayX = getX() - enemy.getX();
            double awayZ = getZ() - enemy.getZ();
            double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (len > .001D) {
                int x = (int) Math.round(getX() + awayX * 6 / len);
                int z = (int) Math.round(getZ() + awayZ * 6 / len);
                BlockPos destination = findScoutStand(x, z);
                if (destination != null && safeStand(destination)) {
                    getNavigation().moveTo(destination.getX() + .5D,
                            destination.getY(), destination.getZ() + .5D, 1.18D);
                    return;
                }
            }
        }
        followOwnerTick();
    }

    @Nullable
    private MonsterEntity nearestThreat(double radius, boolean onlyAttacking) {
        AxisAlignedBB area = getBoundingBox().inflate(radius, 3.0D, radius);
        MonsterEntity best = null;
        double bestDist = radius * radius;
        for (MonsterEntity hostile : level.getEntitiesOfClass(MonsterEntity.class, area,
                mob -> mob.isAlive() && !mob.isNoAi())) {
            double d = distanceToSqr(hostile);
            if (d >= bestDist || !canSee(hostile)) continue;
            boolean dangerousToMini = hostile.getTarget() == this || d <= 7.0D;
            if (onlyAttacking && !dangerousToMini) continue;
            best = hostile;
            bestDist = d;
        }
        return best;
    }

    /** Defende-se de monstros proximos, sem mudar o alvo escolhido pelo dono. */
    private boolean selfDefenseTick() {
        if (getFoodLevel() <= 0 || tools.get(SWORD).isEmpty()) {
            // Sem espada um jogador prudente foge, nao enfrenta zumbis de mao vazia.
            if (tickCount % 12 == 0 && nearestThreat(4.5D, true) != null) {
                escapeDangerTick();
                return true;
            }
            return false;
        }
        if (defensiveThreat != null && (!defensiveThreat.isAlive()
                || !level.isLoaded(defensiveThreat.blockPosition())
                || distanceToSqr(defensiveThreat) > 80.0D
                || (defensiveThreat.getTarget() != this && distanceToSqr(defensiveThreat) > 12.0D))) {
            defensiveThreat = null;
        }
        if (tickCount >= nextDefenseScan) {
            nextDefenseScan = tickCount + 12;
            MonsterEntity found = nearestThreat(5.0D, true);
            if (found != null) defensiveThreat = found;
        }
        if (defensiveThreat == null) return false;
        stopBreakingAnimation();
        MonsterEntity enemy = defensiveThreat;
        getLookControl().setLookAt(enemy, 25.0F, 25.0F);
        if (distanceToSqr(enemy) > MELEE_REACH * MELEE_REACH || !canSee(enemy)) {
            if (tickCount % 14 == 0) getNavigation().moveTo(enemy, 1.10D);
            return true;
        }
        getNavigation().stop();
        if (cooldown > 0) return true;
        ItemStack sword = tools.get(SWORD);
        if (!(sword.getItem() instanceof TieredItem)) return false;
        int tier = ((TieredItem) sword.getItem()).getTier().getLevel();
        entityData.set(ACTION, ACTION_ATTACK);
        combatPoseTicks = 10;
        swing(Hand.MAIN_HAND);
        if (enemy.hurt(DamageSource.mobAttack(this), 2.0F + Math.min(tier, 4) * .85F)) {
            damageTool(SWORD);
            spendEnergy();
        }
        cooldown = 25;
        if (!enemy.isAlive()) defensiveThreat = null;
        return true;
    }

    /** Recupera o caminho se uma rota normal ficar fisicamente bloqueada. */
    private void checkTravelStuck() {
        if (getNavigation().isDone()) {
            lastTravelPosition = blockPosition().immutable();
            travelStuckChecks = 0;
            return;
        }
        if (blockPosition().distSqr(lastTravelPosition) < 2.0D) travelStuckChecks++;
        else travelStuckChecks = 0;
        lastTravelPosition = blockPosition().immutable();
        if (travelStuckChecks < 4) return;
        travelStuckChecks = 0;
        getNavigation().stop();
        if (targetBlock != null) {
            rememberFailedTarget(targetBlock);
            clearTarget();
        } else if (scoutGoal != null) {
            scoutGoal = null;
            scanIndex = 0;
        } else if (tunnelNextFoot != null) {
            rotateTunnel();
        }
        if (safeStand(blockPosition())) getJumpControl().jump();
    }

    private void followOwnerTick() {
        // A subida pode ocorrer mesmo se o dono estiver longe; apenas chunks
        // ja carregados sao utilizados, sem force-load de terreno.
        if (!tunnelTrail.isEmpty() && followExcavatedRoute()) return;
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

    /** Regressa pelos degraus REAIS, sem teletransporte e sem blocos fantasmas. */
    private boolean followExcavatedRoute() {
        while (tunnelTrail.size() > 1) {
            BlockPos previous = tunnelTrail.get(tunnelTrail.size() - 2);
            double dist = distanceToSqr(previous.getX() + .5D, previous.getY(), previous.getZ() + .5D);
            if (dist < 1.15D) {
                tunnelTrail.remove(tunnelTrail.size() - 1);
                returnStuckTicks = 0;
                continue;
            }
            if (!safeStand(previous)) {
                // Alguem pode ter tapado a escada. Avisa e tenta rota alternativa
                // vanilla, em vez de ficar eternamente batendo na parede.
                returnStuckTicks++;
                if (returnStuckTicks == 120) {
                    PlayerEntity owner = ownerNearby();
                    if (owner != null) owner.displayClientMessage(new StringTextComponent(
                            "Mini: meu caminho de volta foi bloqueado. Abra a escada para me ajudar!"), true);
                }
                if (returnStuckTicks > 120 && tickCount % 40 == 0) {
                    PlayerEntity owner = ownerNearby();
                    if (owner != null) getNavigation().moveTo(owner, 1.05D);
                }
                return true;
            }
            if (tickCount % 12 == 0) getNavigation().moveTo(
                    previous.getX() + .5D, previous.getY(), previous.getZ() + .5D, 1.10D);
            if (tickCount % 20 == 0) {
                if (blockPosition().distSqr(lastReturnProgress) < 1.0D) returnStuckTicks += 20;
                else returnStuckTicks = 0;
                lastReturnProgress = blockPosition().immutable();
            }
            // Escadas estreitas sao um caso em que a navegacao vanilla falha
            // ocasionalmente mesmo com os dois blocos livres.
            if (returnStuckTicks > 30 && dist < 6.0D) {
                getMoveControl().setWantedPosition(previous.getX() + .5D,
                        previous.getY(), previous.getZ() + .5D, 1.10D);
                if (previous.getY() > blockPosition().getY()) getJumpControl().jump();
            }
            return true;
        }
        tunnelTrail.clear();
        returnStuckTicks = 0;
        return false;
    }

    private boolean hasRequiredTool() {
        return hasToolFor(getJob(), getChoice());
    }

    private boolean hasToolFor(int job, int choice) {
        if (!Alvos.valid(job, choice)) return false;
        ItemStack tool = tools.get(job == Alvos.MINERAR ? PICK : job == Alvos.MADEIRA ? AXE : SWORD);
        if (tool.isEmpty()) return false;
        // Equipamento no fim da durabilidade nao deve iniciar expedicoes longas.
        if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 3) return false;
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
            // The player asked for a diamond: open a REAL mine, not x-ray scanning.
            if (getJob() == Alvos.MINERAR && getChoice() == DIAMOND_CHOICE
                    && (!isAutonomous() || preferredTarget[Alvos.MINERAR] == DIAMOND_CHOICE)) {
                if (tickCount % 12 == 0) scanVisibleDiamond();
                if (targetBlock == null) excavateDiamondTick();
                return;
            }
            // Forests and surface ores continue exploring loaded sectors.
            if (scoutGoal != null) advanceScout();
            else if (tickCount % 2 == 0) scanForBlocks(700);
            return;
        }
        BlockPos pos = targetBlock;
        if (standingBlock == null || !level.isLoaded(pos) || !isDesiredBlock(pos)) {
            clearTarget(); return;
        }
        if (!canReachBlock(pos)) {
            if (distanceToSqr(standingBlock.getX() + .5D,
                    standingBlock.getY(), standingBlock.getZ() + .5D) > 1.2D) {
                if (tickCount % 16 == 0 && !getNavigation().moveTo(standingBlock.getX() + .5D,
                        standingBlock.getY(), standingBlock.getZ() + .5D, 1.05D)) unreachableTicks += 16;
                if (++unreachableTicks > 160) {
                    rememberFailedTarget(pos);
                    clearTarget();
                }
            } else {
                // Never dig a target through a wall just because it is nearby.
                rememberFailedTarget(pos);
                clearTarget();
            }
            return;
        }
        if (!safeStand(blockPosition())) { rememberFailedTarget(pos); clearTarget(); return; }
        getNavigation().stop();
        int job = getJob();
        int slot = job == Alvos.MINERAR ? PICK : AXE;
        int result = workOnBlock(pos, slot, false);
        if (result < 0) {
            rememberFailedTarget(pos);
            clearTarget();
        } else if (result > 0) {
            successes[job] = Math.min(100, successes[job] + 1);
            clearTarget();
            completedAutonomousAction();
        }
    }

    /** Return -1 when unsafe, 0 while mining, +1 when a real block breaks. */
    private int workOnBlock(BlockPos pos, int slot, boolean tunnel) {
        if (cooldown > 0) return 0;
        if (!level.isLoaded(pos) || level.isEmptyBlock(pos)) return -1;
        BlockState state = level.getBlockState(pos);
        ItemStack tool = tools.get(slot);
        if (tool.isEmpty() || !canReachBlock(pos)) return -1;
        if (state.getDestroySpeed(level, pos) < 0) return -1;
        if (tunnel && !canTunnelBreak(state, pos, tool)) return -1;
        if (breakingBlock == null || !breakingBlock.equals(pos)) {
            stopBreakingAnimation();
            float hardness = state.getDestroySpeed(level, pos);
            int tier = tool.getItem() instanceof TieredItem
                    ? ((TieredItem) tool.getItem()).getTier().getLevel() : 0;
            breakingBlock = pos.immutable();
            breakingTicks = 0;
            breakingStage = -1;
            breakingDuration = Math.max(24, Math.min(160,
                    Math.round((22.0F + hardness * 15.0F) / (1.0F + tier * 0.20F))));
            entityData.set(ACTION, ACTION_BREAK);
        }
        ServerWorld server = (ServerWorld) level;
        getLookControl().setLookAt(pos.getX() + .5D, pos.getY() + .5D, pos.getZ() + .5D);
        if (breakingTicks % 8 == 0) swing(Hand.MAIN_HAND);
        breakingTicks++;
        int stage = Math.min(9, (breakingTicks * 10) / breakingDuration);
        if (stage != breakingStage) {
            breakingStage = stage;
            server.destroyBlockProgress(getId(), pos, stage);
        }
        if (breakingTicks < breakingDuration) return 0;
        if (slot == AXE && getJob() == Alvos.MADEIRA && !tunnel) {
            // Tree-felling: chacha a base, depois derruba os outros troncos
            // conectados. Nao deixa copa suspensa nem cria madeira artificial.
            List<BlockPos> tree = collectNaturalTree(pos);
            if (tree == null || !canFinishTree(tool, tree.size())) {
                if (tickCount - lastTreeWarning > 200) {
                    lastTreeWarning = tickCount;
                    PlayerEntity owner = ownerNearby();
                    if (owner != null) owner.displayClientMessage(new StringTextComponent(
                            "Mini: arvore muito grande, sem machado suficiente ou sem base natural."), true);
                }
                stopBreakingAnimation();
                return -1;
            }
            int chopped = 0;
            for (BlockPos log : tree) {
                if (tools.get(AXE).isEmpty() || !breakRealBlock(log, AXE)) break;
                chopped++;
            }
            cooldown = 20;
            finishCracksIfReady();
            return chopped == tree.size() ? 1 : -1;
        }
        // Mineros e tuneis seguem a mesma regra de drops reais e desgaste.
        boolean broke = breakRealBlock(pos, slot);
        if (broke) cooldown = tunnel ? 5 : slot == PICK ? 15 : 12;
        finishCracksIfReady();
        return broke ? 1 : -1;
    }

    /** Rompe o bloco do mundo, respeitando a loot table e 1 desgaste real. */
    private boolean breakRealBlock(BlockPos pos, int slot) {
        if (!level.isLoaded(pos) || level.isEmptyBlock(pos)) return false;
        ItemStack tool = tools.get(slot);
        if (tool.isEmpty()) return false;
        BlockState state = level.getBlockState(pos);
        if (state.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null) return false;
        ServerWorld server = (ServerWorld) level;
        List<ItemStack> loot = Block.getDrops(state, server, pos, null, this, tool);
        if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) return false;
        server.levelEvent(2001, pos, Block.getId(state));
        for (ItemStack drop : loot) {
            ItemStack remaining = store(drop.copy());
            if (!remaining.isEmpty()) Block.popResource(level, pos, remaining);
        }
        damageTool(slot);
        spendEnergy();
        return true;
    }

    /** Nao aceita casas feitas de madeira: exige troncos da especie, folhas e solo natural. */
    @Nullable
    private List<BlockPos> collectNaturalTree(BlockPos start) {
        if (!level.isLoaded(start)) return null;
        Block species = level.getBlockState(start).getBlock();
        if (species != Alvos.MADEIRAS[getChoice()]) return null;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<BlockPos> logs = new ArrayList<>();
        queue.add(start);
        boolean naturalRoot = false;
        boolean hasCanopy = false;
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst();
            if (!visited.add(at.asLong())) continue;
            // Se um tronco contiguo sai do limite, aborta a arvore inteira:
            // nunca corta so metade por ultrapassar o limite da busca.
            if (Math.abs(at.getX() - start.getX()) > TREE_RADIUS
                    || Math.abs(at.getZ() - start.getZ()) > TREE_RADIUS
                    || Math.abs(at.getY() - start.getY()) > TREE_HEIGHT) {
                if (level.isLoaded(at) && level.getBlockState(at).getBlock() == species) return null;
                continue;
            }
            // Perto de chunk descarregado, recusa a derrubada para nao deixar
            // a outra metade da arvore pendurada quando voltar a carregar.
            if (!level.isLoaded(at)) return null;
            if (level.getBlockState(at).getBlock() != species) continue;
            if (level.getBlockEntity(at) != null) return null;
            logs.add(at.immutable());
            if (logs.size() > MAX_TREE_LOGS) return null;
            BlockPos under = at.below();
            if (level.isLoaded(under) && isNaturalTreeSoil(level.getBlockState(under).getBlock())) naturalRoot = true;
            if (!hasCanopy && folhagemNoNivel(at)) hasCanopy = true;
            // Diagonais permitem cortar galhos tortos de acacia e arvores grandes.
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) queue.add(at.offset(dx, dy, dz));
                    }
                }
            }
        }
        if (!naturalRoot || !hasCanopy || logs.isEmpty()) return null;
        // Corte de cima para baixo; nao gera pilhas flutuantes no meio da queda.
        logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        return logs;
    }

    private boolean isNaturalTreeSoil(Block block) {
        return block == Blocks.GRASS_BLOCK || block == Blocks.DIRT || block == Blocks.PODZOL
                || block == Blocks.COARSE_DIRT || block == Blocks.MYCELIUM
                || block == Blocks.CRIMSON_NYLIUM || block == Blocks.WARPED_NYLIUM;
    }

    /** Evita quebrar so metade da arvore quando o machado acabaria no meio. */
    private boolean canFinishTree(ItemStack axe, int logCount) {
        return !axe.isEmpty() && (!axe.isDamageableItem()
                || axe.getMaxDamage() - axe.getDamageValue() >= logCount);
    }

    /** Avoid clearing the visual cracks before the last hit lands. */
    private void finishCracksIfReady() {
        if (breakingTicks < breakingDuration) return;
        stopBreakingAnimation();
    }

    /** Reach from the helper's eye, never through solid blocks. */
    private boolean canReachBlock(BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        Vector3d eyes = getEyePosition(1.0F);
        Vector3d center = new Vector3d(pos.getX() + .5D, pos.getY() + .5D, pos.getZ() + .5D);
        if (eyes.distanceToSqr(center) > BLOCK_REACH * BLOCK_REACH) return false;
        RayTraceResult hit = level.clip(new RayTraceContext(eyes, center,
                RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, this));
        return hit.getType() == RayTraceResult.Type.BLOCK
                && ((BlockRayTraceResult) hit).getBlockPos().equals(pos);
    }

    /** Checks only actually VISIBLE diamonds within reach, not buried ores. */
    private void scanVisibleDiamond() {
        BlockPos origin = blockPosition();
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(pos) || isTemporarilyBlocked(pos)) continue;
                    if (level.getBlockState(pos).getBlock() != Blocks.DIAMOND_ORE) continue;
                    if (!canReachBlock(pos)) continue;
                    targetBlock = pos.immutable();
                    standingBlock = origin.immutable();
                    unreachableTicks = 0;
                    getNavigation().stop();
                    return;
                }
            }
        }
    }

    /** Dig only natural terrain; do not destroy player structures or block entities. */
    private boolean canTunnelBreak(BlockState state, BlockPos pos, ItemStack pickaxe) {
        if (level.getBlockEntity(pos) != null) return false;
        if (state.requiresCorrectToolForDrops() && !pickaxe.isCorrectToolForDrops(state)) return false;
        Block block = state.getBlock();
        if (block == Blocks.STONE || block == Blocks.GRANITE || block == Blocks.DIORITE
                || block == Blocks.ANDESITE || block == Blocks.DIRT || block == Blocks.GRASS_BLOCK
                || block == Blocks.COARSE_DIRT || block == Blocks.PODZOL
                || block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) return true;
        // The tier check above also protects diamond/gold/ancient debris.
        for (Block ore : Alvos.MINERIOS) if (block == ore) return true;
        return false;
    }

    /** A descida exige tres blocos livres: altura do degrau DE CIMA + a do degrau de baixo. */
    private BlockPos[] tunnelClearance(BlockPos foot) {
        if (foot.getY() < blockPosition().getY()
                || (tunnelDescending && foot.equals(tunnelNextFoot))) {
            return new BlockPos[]{foot.above(2), foot.above(), foot};
        }
        return new BlockPos[]{foot.above(), foot};
    }

    /** Nao abrir escadas em lava, blocos de player, teto de areia ou sem chao solido. */
    private boolean safeTunnelPlan(BlockPos foot) {
        if (foot.getY() < DIAMOND_TUNNEL_Y || foot.getY() > 250) return false;
        BlockPos floor = foot.below();
        BlockPos head = foot.above();
        if (!level.isLoaded(floor) || !level.isLoaded(head)) return false;
        BlockState floorState = level.getBlockState(floor);
        if (level.isEmptyBlock(floor) || dangerous(floorState.getBlock())
                || !floorState.getFluidState().isEmpty()
                || !floorState.isFaceSturdy(level, floor, Direction.UP)) return false;
        BlockPos[] corridor = tunnelClearance(foot);
        BlockPos highest = corridor[0];
        if (!level.isLoaded(highest.above())) return false;
        Block overhead = level.getBlockState(highest.above()).getBlock();
        if (overhead == Blocks.SAND || overhead == Blocks.RED_SAND || overhead == Blocks.GRAVEL) return false;
        for (BlockPos part : corridor) {
            if (!level.isLoaded(part)) return false;
            BlockState state = level.getBlockState(part);
            if (dangerous(state.getBlock()) || !state.getFluidState().isEmpty()) return false;
            if (!passable(part) && !canTunnelBreak(state, part, tools.get(PICK))) return false;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = part.relative(dir);
                if (!level.isLoaded(neighbor) || !level.getFluidState(neighbor).isEmpty()
                        || dangerous(level.getBlockState(neighbor).getBlock())) return false;
            }
        }
        return true;
    }

    private void rotateTunnel() {
        stopBreakingAnimation();
        getNavigation().stop();
        tunnelNextFoot = null;
        tunnelMoveTicks = 0;
        tunnelDescending = false;
        tunnelDirection = tunnelDirection == null ? Direction.NORTH : tunnelDirection.getClockWise();
        if (++tunnelFailedDirections >= 8) {
            // Cannot open a safe route. Return instead of mining in a lethal spot.
            finishJob();
        }
    }

    /** Walk down one block at a time, clearing a 2-high staircase. */
    private void excavateDiamondTick() {
        if (isFull() || tools.get(PICK).isEmpty()) { finishJob(); return; }
        if (tunnelSteps >= MAX_TUNNEL_STEPS) { finishJob(); return; }
        if (tunnelTrail.isEmpty()) tunnelTrail.add(blockPosition().immutable());
        if (tunnelDirection == null) {
            tunnelDirection = getDirection();
            if (tunnelDirection.getAxis() == Direction.Axis.Y) tunnelDirection = Direction.NORTH;
        }
        if (tunnelNextFoot == null) {
            BlockPos current = blockPosition();
            if (current.getY() <= DIAMOND_TUNNEL_Y && tunnelSegmentSteps >= tunnelSegmentLength) {
                // Spiral branches cover new Y=11 areas without looping the same square.
                tunnelDirection = tunnelDirection.getClockWise();
                tunnelSegmentSteps = 0;
                if (++tunnelTurns % 2 == 0) tunnelSegmentLength += 5;
            }
            BlockPos next = current.relative(tunnelDirection);
            if (current.getY() > DIAMOND_TUNNEL_Y) next = next.below();
            double dx = next.getX() - home.getX(), dz = next.getZ() - home.getZ();
            if (dx * dx + dz * dz >= (EXPLORE_RADIUS - 4) * (EXPLORE_RADIUS - 4)
                    || !safeTunnelPlan(next)) {
                rotateTunnel();
                return;
            }
            tunnelNextFoot = next.immutable();
            tunnelDescending = next.getY() < current.getY();
            tunnelMoveTicks = 0;
        }
        BlockPos foot = tunnelNextFoot;
        if (!safeTunnelPlan(foot)) { rotateTunnel(); return; }
        // Em escada, remover TAMBEM o bloco acima da cabeca da posicao superior.
        // Planta sem colisao nao precisa ser minerada, e nao bloqueia a passagem.
        for (BlockPos part : tunnelClearance(foot)) {
            if (!passable(part)) {
                int broken = workOnBlock(part, PICK, true);
                if (broken < 0) rotateTunnel();
                return;
            }
        }
        if (!safeStand(foot)) { rotateTunnel(); return; }
        double dist = distanceToSqr(foot.getX() + .5D, foot.getY(), foot.getZ() + .5D);
        if (dist <= 0.65D) {
            tunnelSteps++;
            tunnelFailedDirections = 0;
            if (tunnelTrail.isEmpty() || !tunnelTrail.get(tunnelTrail.size() - 1).equals(foot)) {
                tunnelTrail.add(foot.immutable());
            }
            if (foot.getY() <= DIAMOND_TUNNEL_Y) tunnelSegmentSteps++;
            // Ilumina o proprio tunel, se o jogador entregou tochas reais.
            // O toque e no degrau anterior para nao interferir na navegacao.
            if (tunnelSteps % 10 == 0) lightTunnelFromBag();
            tunnelNextFoot = null;
            tunnelMoveTicks = 0;
            tunnelDescending = false;
            getNavigation().stop();
            return;
        }
        if (++tunnelMoveTicks > 90) { rotateTunnel(); return; }
        if (tickCount % 10 == 0 || tunnelMoveTicks == 1) {
            getNavigation().moveTo(foot.getX() + .5D, foot.getY(), foot.getZ() + .5D, 1.12D);
        }
        // Passagem aberta, mas pathfinder nao compreende um degrau descendente:
        // comando de movimento curto, sujeito as colisoes normais do Minecraft.
        if (tunnelMoveTicks > 18 && dist < 7.0D && safeStand(foot)) {
            getMoveControl().setWantedPosition(foot.getX() + .5D,
                    foot.getY(), foot.getZ() + .5D, 1.08D);
        }
    }

    /** Ilumina passagem sem criar itens: tira uma tocha real da mochila. */
    private void lightTunnelFromBag() {
        if (tunnelTrail.size() < 2) return;
        BlockPos place = tunnelTrail.get(tunnelTrail.size() - 2);
        if (!level.isLoaded(place) || !level.isEmptyBlock(place)) return;
        BlockState torch = Blocks.TORCH.defaultBlockState();
        if (!torch.canSurvive(level, place)) return;
        for (int i = 0; i < STORAGE_SIZE; i++) {
            ItemStack stack = storage.get(i);
            if (stack.isEmpty() || stack.getItem() != Items.TORCH) continue;
            if (level.setBlock(place, torch, 3)) {
                stack.shrink(1);
                if (stack.isEmpty()) storage.set(i, ItemStack.EMPTY);
            }
            return;
        }
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
        // Um objetivo humano exige continuidade: nao abandonar uma arvore/mina
        // para mudar de profissao apos apenas dois blocos quebrados.
        int quota = getJob() == Alvos.MADEIRA ? 3
                : getJob() == Alvos.CACAR ? 3 : getJob() == Alvos.MINERAR ? 12 : 4;
        if (++autonomousActions >= quota && tickCount - autoGoalStarted >= 500) {
            chooseNextAutonomousJob();
        }
    }

    private void scanForBlocks(int budget) {
        for (int searched = 0; searched < budget; searched++) {
            if (scanIndex >= SEARCH_ORDER.size()) { startNextScoutLeg(); return; }
            BlockPos pos = scanCenter.offset(SEARCH_ORDER.get(scanIndex++));
            if (!level.isLoaded(pos) || isTemporarilyBlocked(pos) || !isDesiredBlock(pos)) continue;
            BlockPos stand = findStand(pos);
            if (stand == null) continue;
            // No modo autonomo, identifica a especie ou minerio local em vez
            // de ignorar carvalho porque a configuracao antiga dizia betula.
            if (isAutonomous()) {
                int discovered = resourceIndex(level.getBlockState(pos).getBlock(), getJob());
                if (discovered >= 0 && discovered != getChoice()) entityData.set(CHOICE, discovered);
            }
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
                lastScoutPosition = here.immutable();
                scoutStuckChecks = 0;
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
        if (scoutTicks % 20 == 0) {
            if (blockPosition().distSqr(lastScoutPosition) <= 1.0D) scoutStuckChecks++;
            else scoutStuckChecks = 0;
            lastScoutPosition = blockPosition().immutable();
        }
        if (scoutTicks > MAX_SCOUT_TICKS || scoutStuckChecks >= 4) {
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

    private int resourceIndex(Block block, int job) {
        if (job == Alvos.MINERAR) {
            for (int i = 0; i < Alvos.MINERIOS.length; i++) {
                if (Alvos.MINERIOS[i] == block && hasToolFor(Alvos.MINERAR, i)) return i;
            }
        } else if (job == Alvos.MADEIRA) {
            for (int i = 0; i < Alvos.MADEIRAS.length; i++) {
                if (Alvos.MADEIRAS[i] == block) return i;
            }
        }
        return -1;
    }

    private boolean isDesiredBlock(BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        if (getJob() == Alvos.MINERAR) return isAutonomous()
                ? resourceIndex(block, Alvos.MINERAR) >= 0
                : block == Alvos.MINERIOS[getChoice()];
        if (getJob() == Alvos.MADEIRA) return (isAutonomous()
                ? resourceIndex(block, Alvos.MADEIRA) >= 0
                : block == Alvos.MADEIRAS[getChoice()]) && hasLeavesNearby(pos);
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
        if (safeStand(blockPosition()) && canReachBlock(target)) return blockPosition().immutable();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = target.relative(direction);
            if (!level.isLoaded(pos) || !level.isLoaded(pos.above()) || !level.isLoaded(pos.below())) continue;
            if (safeStand(pos)) return pos;
        }
        return null;
    }

    /** Ar, capim e flores sao livres; pedra, liquidos e espinhos nao. */
    private boolean passable(BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return !dangerous(state.getBlock()) && state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty();
    }

    private boolean safeStand(BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isLoaded(pos.above()) || !level.isLoaded(pos.below())) return false;
        boolean clear = passable(pos) && passable(pos.above());
        BlockPos floorPos = pos.below();
        BlockState floorState = level.getBlockState(floorPos);
        boolean floor = !level.isEmptyBlock(floorPos) && !dangerous(floorState.getBlock())
                && floorState.getFluidState().isEmpty()
                && floorState.isFaceSturdy(level, floorPos, Direction.UP);
        boolean hazard = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos around = pos.relative(dir);
            if (!level.isLoaded(around) || dangerous(level.getBlockState(around).getBlock())
                    || !level.getFluidState(around).isEmpty()) {
                hazard = true;
                break;
            }
        }
        return Cerebro.podePassar(floor, clear, hazard);
    }

    private boolean dangerous(Block block) {
        return block == Blocks.LAVA || block == Blocks.WATER || block == Blocks.FIRE || block == Blocks.SOUL_FIRE
                || block == Blocks.CACTUS || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.COBWEB || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.WITHER_ROSE;
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
            if (tickCount % 25 != 0) return;
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
        if (distanceToSqr(targetMob) > MELEE_REACH * MELEE_REACH || !canSee(targetMob)) {
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
        // Com nove pilhas diferentes ja nao cabe um DECIMO tipo de loot.
        // Volta ao dono antes de deixar itens valiosos pelo chao.
        for (ItemStack item : storage) if (item.isEmpty()) return false;
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
        tag.putInt("AutoGoalAge", Math.max(0, tickCount - autoGoalStarted));
        tag.putIntArray("PreferredTarget", preferredTarget);
        tag.putIntArray("Preferences", preferences);
        tag.putIntArray("Successes", successes);
        tag.putInt("WorkActions", workActions);
        tag.putInt("TunnelSteps", tunnelSteps);
        tag.putInt("TunnelDirection", tunnelDirection == null ? -1 : tunnelDirection.get3DDataValue());
        tag.putInt("TunnelSegmentSteps", tunnelSegmentSteps);
        tag.putInt("TunnelSegmentLength", tunnelSegmentLength);
        tag.putInt("TunnelTurns", tunnelTurns);
        long[] trailPositions = new long[tunnelTrail.size()];
        for (int i = 0; i < tunnelTrail.size(); i++) trailPositions[i] = tunnelTrail.get(i).asLong();
        tag.putLongArray("TunnelTrail", trailPositions);
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
        autoGoalStarted = tickCount - Math.min(9600, Math.max(0, tag.getInt("AutoGoalAge")));
        lastNeedMessage = -1000;
        defensiveThreat = null;
        lastTravelPosition = blockPosition().immutable();
        travelStuckChecks = 0;
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
        tunnelSteps = Math.max(0, Math.min(MAX_TUNNEL_STEPS, tag.getInt("TunnelSteps")));
        int directionOrdinal = tag.contains("TunnelDirection") ? tag.getInt("TunnelDirection") : -1;
        tunnelDirection = directionOrdinal < 0 || directionOrdinal > 5
                ? null : Direction.from3DDataValue(directionOrdinal);
        tunnelNextFoot = null;
        tunnelMoveTicks = 0;
        tunnelDescending = false;
        tunnelSegmentSteps = Math.max(0, tag.getInt("TunnelSegmentSteps"));
        tunnelSegmentLength = Math.max(8, tag.getInt("TunnelSegmentLength"));
        tunnelTurns = Math.max(0, tag.getInt("TunnelTurns"));
        tunnelTrail.clear();
        long[] restoredTrail = tag.getLongArray("TunnelTrail");
        for (int i = 0; i < restoredTrail.length && i <= MAX_TUNNEL_STEPS; i++) {
            tunnelTrail.add(BlockPos.of(restoredTrail[i]));
        }
        returnStuckTicks = 0;
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
