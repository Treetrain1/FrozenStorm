package net.frozenblock.frozenstorm.entity;

import com.google.common.collect.ImmutableList;
import net.frozenblock.frozenstorm.registry.RegisterItems;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.entity.feature.SkinOverlayOwner;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.ai.control.FlightMoveControl;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;
import net.minecraft.world.explosion.Explosion;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

public class FrozenStormEntity extends HostileEntity implements SkinOverlayOwner, RangedAttackMob {

    private static final TrackedData<Integer> TRACKED_ENTITY_ID_1 = DataTracker.registerData(FrozenStormEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> TRACKED_ENTITY_ID_2 = DataTracker.registerData(FrozenStormEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> TRACKED_ENTITY_ID_3 = DataTracker.registerData(FrozenStormEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final List<TrackedData<Integer>> TRACKED_ENTITY_IDS = ImmutableList.of(TRACKED_ENTITY_ID_1, TRACKED_ENTITY_ID_2, TRACKED_ENTITY_ID_3);
    private static final TrackedData<Integer> INVUL_TIMER = DataTracker.registerData(FrozenStormEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final int DEFAULT_INVUL_TIMER = 220;
    private final float[] sideHeadPitches = new float[2];
    private final float[] sideHeadYaws = new float[2];
    private final float[] prevSideHeadPitches = new float[2];
    private final float[] prevSideHeadYaws = new float[2];
    private final int[] skullCooldowns = new int[2];
    private final int[] chargedSkullCooldowns = new int[2];
    private int blockBreakingCooldown;
    private final ServerBossBar bossBar = (ServerBossBar)new ServerBossBar(this.getDisplayName(), BossBar.Color.PURPLE, BossBar.Style.PROGRESS)
            .setDarkenSky(true);
    private static final Predicate<LivingEntity> CAN_ATTACK_PREDICATE = entity -> entity.getGroup() != EntityGroup.UNDEAD && entity.isMobOrPlayer();
    private static final TargetPredicate HEAD_TARGET_PREDICATE = TargetPredicate.createAttackable().setBaseMaxDistance(20.0).setPredicate(CAN_ATTACK_PREDICATE);

    public FrozenStormEntity(EntityType<? extends FrozenStormEntity> entityType, World world) {
        super(entityType, world);
        this.moveControl = new FlightMoveControl(this, 10, false);
        this.setHealth(this.getMaxHealth());
        this.experiencePoints = 50;
    }

    @Override
    protected EntityNavigation createNavigation(World world) {
        BirdNavigation birdNavigation = new BirdNavigation(this, world);
        birdNavigation.setCanPathThroughDoors(false);
        birdNavigation.setCanSwim(true);
        birdNavigation.setCanEnterOpenDoors(true);
        return birdNavigation;
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new FrozenStormEntity.DescendAtHalfHealthGoal());
        this.goalSelector.add(2, new ProjectileAttackGoal(this, 1.0, 40, 20.0F));
        this.goalSelector.add(5, new FlyGoal(this, 1.0));
        this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(7, new LookAroundGoal(this));
        this.targetSelector.add(1, new RevengeGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 0, false, false, CAN_ATTACK_PREDICATE));
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(TRACKED_ENTITY_ID_1, 0);
        this.dataTracker.startTracking(TRACKED_ENTITY_ID_2, 0);
        this.dataTracker.startTracking(TRACKED_ENTITY_ID_3, 0);
        this.dataTracker.startTracking(INVUL_TIMER, 0);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Invul", this.getInvulnerableTimer());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.setInvulTimer(nbt.getInt("Invul"));
        if (this.hasCustomName()) {
            this.bossBar.setName(this.getDisplayName());
        }

    }

    @Override
    public void setCustomName(@Nullable Text name) {
        super.setCustomName(name);
        this.bossBar.setName(this.getDisplayName());
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENTITY_WITHER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_WITHER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_WITHER_DEATH;
    }

    @Override
    public void tickMovement() {
        Vec3d vec3d = this.getVelocity().multiply(1.0, 0.6, 1.0);
        if (!this.world.isClient && this.getTrackedEntityId(0) > 0) {
            Entity entity = this.world.getEntityById(this.getTrackedEntityId(0));
            if (entity != null) {
                double d = vec3d.y;
                if (this.getY() < entity.getY() || !this.shouldRenderOverlay() && this.getY() < entity.getY() + 5.0) {
                    d = Math.max(0.0, d);
                    d += 0.3 - d * 0.6F;
                }

                vec3d = new Vec3d(vec3d.x, d, vec3d.z);
                Vec3d vec3d2 = new Vec3d(entity.getX() - this.getX(), 0.0, entity.getZ() - this.getZ());
                if (vec3d2.horizontalLengthSquared() > 9.0) {
                    Vec3d vec3d3 = vec3d2.normalize();
                    vec3d = vec3d.add(vec3d3.x * 0.3 - vec3d.x * 0.6, 0.0, vec3d3.z * 0.3 - vec3d.z * 0.6);
                }
            }
        }

        this.setVelocity(vec3d);
        if (vec3d.horizontalLengthSquared() > 0.05) {
            this.setYaw((float)MathHelper.atan2(vec3d.z, vec3d.x) * 180.0F / (float)Math.PI - 90.0F);
        }

        super.tickMovement();

        for(int i = 0; i < 2; ++i) {
            this.prevSideHeadYaws[i] = this.sideHeadYaws[i];
            this.prevSideHeadPitches[i] = this.sideHeadPitches[i];
        }

        for(int i = 0; i < 2; ++i) {
            int j = this.getTrackedEntityId(i + 1);
            Entity entity2 = null;
            if (j > 0) {
                entity2 = this.world.getEntityById(j);
            }

            if (entity2 != null) {
                double e = this.getHeadX(i + 1);
                double f = this.getHeadY(i + 1);
                double g = this.getHeadZ(i + 1);
                double h = entity2.getX() - e;
                double k = entity2.getEyeY() - f;
                double l = entity2.getZ() - g;
                double m = Math.sqrt(h * h + l * l);
                float n = (float)(MathHelper.atan2(l, h) * 180.0F / (float)Math.PI) - 90.0F;
                float o = (float)(-(MathHelper.atan2(k, m) * 180.0F / (float)Math.PI));
                this.sideHeadPitches[i] = this.getNextAngle(this.sideHeadPitches[i], o, 40.0F);
                this.sideHeadYaws[i] = this.getNextAngle(this.sideHeadYaws[i], n, 10.0F);
            } else {
                this.sideHeadYaws[i] = this.getNextAngle(this.sideHeadYaws[i], this.bodyYaw, 10.0F);
            }
        }

        boolean bl = this.shouldRenderOverlay();

        for(int j = 0; j < 3; ++j) {
            double p = this.getHeadX(j);
            double q = this.getHeadY(j);
            double r = this.getHeadZ(j);
            this.world
                    .addParticle(
                            ParticleTypes.SMOKE, p + this.random.nextGaussian() * 0.3F, q + this.random.nextGaussian() * 0.3F, r + this.random.nextGaussian() * 0.3F, 0.0, 0.0, 0.0
                    );
            if (bl && this.world.random.nextInt(4) == 0) {
                this.world
                        .addParticle(
                                ParticleTypes.ENTITY_EFFECT,
                                p + this.random.nextGaussian() * 0.3F,
                                q + this.random.nextGaussian() * 0.3F,
                                r + this.random.nextGaussian() * 0.3F,
                                0.7F,
                                0.7F,
                                0.5
                        );
            }
        }

        if (this.getInvulnerableTimer() > 0) {
            for(int j = 0; j < 3; ++j) {
                this.world
                        .addParticle(
                                ParticleTypes.ENTITY_EFFECT,
                                this.getX() + this.random.nextGaussian(),
                                this.getY() + (double)(this.random.nextFloat() * 3.3F),
                                this.getZ() + this.random.nextGaussian(),
                                0.7F,
                                0.7F,
                                0.9F
                        );
            }
        }

    }

    @Override
    protected void mobTick() {
        if (this.getInvulnerableTimer() > 0) {
            int i = this.getInvulnerableTimer() - 1;
            this.bossBar.setPercent(1.0F - (float)i / 220.0F);
            if (i <= 0) {
                Explosion.DestructionType destructionType = this.world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)
                        ? Explosion.DestructionType.DESTROY
                        : Explosion.DestructionType.NONE;
                this.world.createExplosion(this, this.getX(), this.getEyeY(), this.getZ(), 7.0F, false, destructionType);
                if (!this.isSilent()) {
                    this.world.syncGlobalEvent(WorldEvents.WITHER_SPAWNS, this.getBlockPos(), 0);
                }
            }

            this.setInvulTimer(i);
            if (this.age % 10 == 0) {
                this.heal(10.0F);
            }

        } else {
            super.mobTick();

            for(int i = 1; i < 3; ++i) {
                if (this.age >= this.skullCooldowns[i - 1]) {
                    this.skullCooldowns[i - 1] = this.age + 10 + this.random.nextInt(10);
                    if ((this.world.getDifficulty() == Difficulty.NORMAL || this.world.getDifficulty() == Difficulty.HARD) && this.chargedSkullCooldowns[i - 1]++ > 15) {
                        float f = 10.0F;
                        float g = 5.0F;
                        double d = MathHelper.nextDouble(this.random, this.getX() - f, this.getX() + f);
                        double e = MathHelper.nextDouble(this.random, this.getY() - g, this.getY() + g);
                        double h = MathHelper.nextDouble(this.random, this.getZ() - f, this.getZ() + g);
                        this.shootSkullAt(i + 1, d, e, h, true);
                        this.chargedSkullCooldowns[i - 1] = 0;
                    }

                    int j = this.getTrackedEntityId(i);
                    if (j > 0) {
                        LivingEntity livingEntity = (LivingEntity)this.world.getEntityById(j);
                        if (livingEntity != null && this.canTarget(livingEntity) && !(this.squaredDistanceTo(livingEntity) > 900.0) && this.canSee(livingEntity)) {
                            this.shootSkullAt(i + 1, livingEntity);
                            this.skullCooldowns[i - 1] = this.age + 40 + this.random.nextInt(20);
                            this.chargedSkullCooldowns[i - 1] = 0;
                        } else {
                            this.setTrackedEntityId(i, 0);
                        }
                    } else {
                        List<LivingEntity> list = this.world.getTargets(LivingEntity.class, HEAD_TARGET_PREDICATE, this, this.getBoundingBox().expand(20.0, 8.0, 20.0));
                        if (!list.isEmpty()) {
                            LivingEntity livingEntity2 = list.get(this.random.nextInt(list.size()));
                            this.setTrackedEntityId(i, livingEntity2.getId());
                        }
                    }
                }
            }

            if (this.getTarget() != null) {
                this.setTrackedEntityId(0, this.getTarget().getId());
            } else {
                this.setTrackedEntityId(0, 0);
            }

            if (this.blockBreakingCooldown > 0) {
                --this.blockBreakingCooldown;
                if (this.blockBreakingCooldown == 0 && this.world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
                    int i = MathHelper.floor(this.getY());
                    int j = MathHelper.floor(this.getX());
                    int k = MathHelper.floor(this.getZ());
                    boolean bl = false;

                    for(int l = -1; l <= 1; ++l) {
                        for(int m = -1; m <= 1; ++m) {
                            for(int n = 0; n <= 3; ++n) {
                                int o = j + l;
                                int p = i + n;
                                int q = k + m;
                                BlockPos blockPos = new BlockPos(o, p, q);
                                BlockState blockState = this.world.getBlockState(blockPos);
                                if (canDestroy(blockState)) {
                                    bl = this.world.breakBlock(blockPos, true, this) || bl;
                                }
                            }
                        }
                    }

                    if (bl) {
                        this.world.syncWorldEvent(null, WorldEvents.WITHER_BREAKS_BLOCK, this.getBlockPos(), 0);
                    }
                }
            }

            if (this.age % 20 == 0) {
                this.heal(1.0F);
            }

            this.bossBar.setPercent(this.getHealth() / this.getMaxHealth());
        }
    }

    public static boolean canDestroy(BlockState block) {
        return !block.isAir() && !block.isIn(BlockTags.WITHER_IMMUNE);
    }

    public void onSummoned() {
        this.setInvulTimer(220);
        this.bossBar.setPercent(0.0F);
        this.setHealth(this.getMaxHealth() / 3.0F);
    }

    @Override
    public void slowMovement(BlockState state, Vec3d multiplier) {
    }

    @Override
    public void onStartedTrackingBy(ServerPlayerEntity player) {
        super.onStartedTrackingBy(player);
        this.bossBar.addPlayer(player);
    }

    @Override
    public void onStoppedTrackingBy(ServerPlayerEntity player) {
        super.onStoppedTrackingBy(player);
        this.bossBar.removePlayer(player);
    }

    private double getHeadX(int headIndex) {
        if (headIndex <= 0) {
            return this.getX();
        } else {
            float f = (this.bodyYaw + (float)(180 * (headIndex - 1))) * (float) (Math.PI / 180.0);
            float g = MathHelper.cos(f);
            return this.getX() + (double)g * 1.3;
        }
    }

    private double getHeadY(int headIndex) {
        return headIndex <= 0 ? this.getY() + 3.0 : this.getY() + 2.2;
    }

    private double getHeadZ(int headIndex) {
        if (headIndex <= 0) {
            return this.getZ();
        } else {
            float f = (this.bodyYaw + (float)(180 * (headIndex - 1))) * (float) (Math.PI / 180.0);
            float g = MathHelper.sin(f);
            return this.getZ() + (double)g * 1.3;
        }
    }

    private float getNextAngle(float prevAngle, float desiredAngle, float maxDifference) {
        float f = MathHelper.wrapDegrees(desiredAngle - prevAngle);
        if (f > maxDifference) {
            f = maxDifference;
        }

        if (f < -maxDifference) {
            f = -maxDifference;
        }

        return prevAngle + f;
    }

    private void shootSkullAt(int headIndex, LivingEntity target) {
        this.shootSkullAt(
                headIndex, target.getX(), target.getY() + (double)target.getStandingEyeHeight() * 0.5, target.getZ(), headIndex == 0 && this.random.nextFloat() < 0.001F
        );
    }

    private void shootSkullAt(int headIndex, double targetX, double targetY, double targetZ, boolean charged) {
        if (!this.isSilent()) {
            this.world.syncWorldEvent(null, WorldEvents.WITHER_SHOOTS, this.getBlockPos(), 0);
        }

        double d = this.getHeadX(headIndex);
        double e = this.getHeadY(headIndex);
        double f = this.getHeadZ(headIndex);
        double g = targetX - d;
        double h = targetY - e;
        double i = targetZ - f;
        WitherSkullEntity witherSkullEntity = new WitherSkullEntity(this.world, this, g, h, i);
        witherSkullEntity.setOwner(this);
        if (charged) {
            witherSkullEntity.setCharged(true);
        }

        witherSkullEntity.setPos(d, e, f);
        this.world.spawnEntity(witherSkullEntity);
    }

    @Override
    public void attack(LivingEntity target, float pullProgress) {
        this.shootSkullAt(0, target);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        } else if (source == DamageSource.DROWN || source.getAttacker() instanceof FrozenStormEntity) {
            return false;
        } else if (this.getInvulnerableTimer() > 0 && source != DamageSource.OUT_OF_WORLD) {
            return false;
        } else {
            if (this.shouldRenderOverlay()) {
                Entity entity = source.getSource();
                if (entity instanceof PersistentProjectileEntity) {
                    return false;
                }
            }

            Entity entity = source.getAttacker();
            if (entity != null && !(entity instanceof PlayerEntity) && entity instanceof LivingEntity && ((LivingEntity)entity).getGroup() == this.getGroup()) {
                return false;
            } else {
                if (this.blockBreakingCooldown <= 0) {
                    this.blockBreakingCooldown = 20;
                }

                for(int i = 0; i < this.chargedSkullCooldowns.length; ++i) {
                    this.chargedSkullCooldowns[i] += 3;
                }

                return super.damage(source, amount);
            }
        }
    }

    @Override
    protected void dropEquipment(DamageSource source, int lootingMultiplier, boolean allowDrops) {
        super.dropEquipment(source, lootingMultiplier, allowDrops);
        ItemEntity itemEntity = this.dropItem(RegisterItems.FROZEN_NETHER_STAR);
        if (itemEntity != null) {
            itemEntity.setCovetedItem();
        }

    }

    @Override
    public void checkDespawn() {
        if (this.world.getDifficulty() == Difficulty.PEACEFUL && this.isDisallowedInPeaceful()) {
            this.discard();
        } else {
            this.despawnCounter = 0;
        }
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    public boolean addStatusEffect(StatusEffectInstance effect, @Nullable Entity source) {
        return false;
    }

    public static DefaultAttributeContainer.Builder createWitherAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 300.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.6F)
                .add(EntityAttributes.GENERIC_FLYING_SPEED, 0.6F)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0)
                .add(EntityAttributes.GENERIC_ARMOR, 4.0);
    }

    public float getHeadYaw(int headIndex) {
        return this.sideHeadYaws[headIndex];
    }

    public float getHeadPitch(int headIndex) {
        return this.sideHeadPitches[headIndex];
    }

    public int getInvulnerableTimer() {
        return this.dataTracker.get(INVUL_TIMER);
    }

    public void setInvulTimer(int ticks) {
        this.dataTracker.set(INVUL_TIMER, ticks);
    }

    public int getTrackedEntityId(int headIndex) {
        return this.dataTracker.get(TRACKED_ENTITY_IDS.get(headIndex));
    }

    public void setTrackedEntityId(int headIndex, int id) {
        this.dataTracker.set(TRACKED_ENTITY_IDS.get(headIndex), id);
    }

    @Override
    public boolean shouldRenderOverlay() {
        return this.getHealth() <= this.getMaxHealth() / 2.0F;
    }

    @Override
    public EntityGroup getGroup() {
        return EntityGroup.UNDEAD;
    }

    @Override
    protected boolean canStartRiding(Entity entity) {
        return false;
    }

    @Override
    public boolean canUsePortals() {
        return false;
    }

    @Override
    public boolean canHaveStatusEffect(StatusEffectInstance effect) {
        return effect.getEffectType() != StatusEffects.WITHER && super.canHaveStatusEffect(effect);
    }

    class DescendAtHalfHealthGoal extends Goal {
        public DescendAtHalfHealthGoal() {
            this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.JUMP, Goal.Control.LOOK));
        }

        @Override
        public boolean canStart() {
            return FrozenStormEntity.this.getInvulnerableTimer() > 0;
        }
    }
}
