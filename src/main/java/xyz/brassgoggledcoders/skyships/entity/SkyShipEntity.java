package xyz.brassgoggledcoders.skyships.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.LilyPadBlock;
import net.minecraft.entity.*;
import net.minecraft.entity.item.BoatEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootParameterSets;
import net.minecraft.loot.LootParameters;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.shapes.IBooleanFunction;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkHooks;
import xyz.brassgoggledcoders.skyships.SkyShips;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;

public class SkyShipEntity extends Entity {
    private static final DataParameter<Integer> DATA_ID_HURT = EntityDataManager.defineId(SkyShipEntity.class, DataSerializers.INT);
    private static final DataParameter<Integer> DATA_ID_HURT_DIR = EntityDataManager.defineId(SkyShipEntity.class, DataSerializers.INT);
    private static final DataParameter<Float> DATA_ID_DAMAGE = EntityDataManager.defineId(SkyShipEntity.class, DataSerializers.FLOAT);
    private static final DataParameter<Boolean> DATA_ID_PADDLE_LEFT = EntityDataManager.defineId(SkyShipEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> DATA_ID_PADDLE_RIGHT = EntityDataManager.defineId(SkyShipEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> DATA_ID_BUBBLE_TIME = EntityDataManager.defineId(SkyShipEntity.class, DataSerializers.INT);
    private static final DataParameter<Integer> DATA_ID_VERTICAL = EntityDataManager.defineId(SkyShipEntity.class, DataSerializers.INT);

    private final float[] paddlePositions = new float[2];
    private float outOfControlTicks;
    private float deltaRotation;

    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;
    private double lerpXRot;

    private boolean inputLeft;
    private boolean inputRight;
    private boolean inputUp;
    private boolean inputDown;
    private int inputVertical;

    private double waterLevel;
    private float landFriction;

    private BoatEntity.Status status;
    private BoatEntity.Status oldStatus;
    private double lastYd;

    public SkyShipEntity(EntityType<?> type, World level) {
        super(type, level);
    }

    @Override
    protected boolean isMovementNoisy() {
        return false;
    }

    @Override
    @ParametersAreNonnullByDefault
    protected float getEyeHeight(Pose pPos, EntitySize entitySize) {
        return entitySize.height;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_ID_HURT, 0);
        this.entityData.define(DATA_ID_HURT_DIR, 1);
        this.entityData.define(DATA_ID_DAMAGE, 0.0F);
        this.entityData.define(DATA_ID_PADDLE_LEFT, false);
        this.entityData.define(DATA_ID_PADDLE_RIGHT, false);
        this.entityData.define(DATA_ID_BUBBLE_TIME, 0);
        this.entityData.define(DATA_ID_VERTICAL, 0);
    }

    @Override
    public boolean canCollideWith(@Nonnull Entity pEntity) {
        return canVehicleCollide(this, pEntity);
    }

    public static boolean canVehicleCollide(Entity boat, Entity other) {
        return (other.canBeCollidedWith() || other.isPushable()) && !boat.isPassengerOfSameVehicle(other);
    }

    @Override
    @Nonnull
    @ParametersAreNonnullByDefault
    public ActionResultType interact(PlayerEntity pPlayer, Hand pHand) {
        if (pPlayer.isSecondaryUseActive()) {
            return ActionResultType.PASS;
        } else if (this.outOfControlTicks < 60.0F) {
            if (!this.level.isClientSide) {
                return pPlayer.startRiding(this) ? ActionResultType.CONSUME : ActionResultType.PASS;
            } else {
                return ActionResultType.SUCCESS;
            }
        } else {
            return ActionResultType.PASS;
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    @Nonnull
    @ParametersAreNonnullByDefault
    protected Vector3d getRelativePortalPosition(Direction.Axis axis, TeleportationRepositioner.Result result) {
        return LivingEntity.resetForwardDirectionOfRelativePortalPosition(super.getRelativePortalPosition(axis, result));
    }

    @Override
    public double getPassengersRidingOffset() {
        return -0.1D;
    }

    @Override
    public boolean hurt(@Nonnull DamageSource pSource, float pAmount) {
        if (this.isInvulnerableTo(pSource)) {
            return false;
        } else if (!this.level.isClientSide && this.isAlive()) {
            this.setHurtDir(-this.getHurtDir());
            this.setHurtTime(10);
            this.setDamage(this.getDamage() + pAmount * 10.0F);
            this.markHurt();
            boolean flag = pSource.getEntity() instanceof PlayerEntity && ((PlayerEntity) pSource.getEntity()).abilities.instabuild;
            if (flag || this.getDamage() > 40.0F) {
                if (!flag && this.level.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
                    this.spawnLoot(pSource);
                }

                this.remove();
            }

            return true;
        } else {
            return true;
        }
    }

    private void spawnLoot(DamageSource pSource) {
        if (this.level instanceof ServerWorld) {
            ServerWorld serverLevel = (ServerWorld) this.level;

            LootContext lootContext = new LootContext.Builder(serverLevel)
                    .withRandom(this.random)
                    .withParameter(LootParameters.THIS_ENTITY, this)
                    .withParameter(LootParameters.DAMAGE_SOURCE, pSource)
                    .withParameter(LootParameters.ORIGIN, this.position())
                    .withOptionalParameter(LootParameters.KILLER_ENTITY, pSource.getEntity())
                    .withOptionalParameter(LootParameters.DIRECT_KILLER_ENTITY, pSource.getDirectEntity())
                    .create(LootParameterSets.ENTITY);

            serverLevel.getServer()
                    .getLootTables()
                    .get(Objects.requireNonNull(this.getType().getRegistryName()))
                    .getRandomItems(lootContext)
                    .forEach(this::spawnAtLocation);
        }
    }

    @Override
    public void push(@Nonnull Entity pEntity) {
        if (pEntity instanceof BoatEntity) {
            if (pEntity.getBoundingBox().minY < this.getBoundingBox().maxY) {
                super.push(pEntity);
            }
        } else if (pEntity.getBoundingBox().minY <= this.getBoundingBox().minY) {
            super.push(pEntity);
        }
    }

    @Override
    public void animateHurt() {
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.setDamage(this.getDamage() * 11.0F);
    }

    @Override
    public boolean isPickable() {
        return this.isAlive();
    }

    @Override
    public void lerpTo(double pX, double pY, double pZ, float pYaw, float pPitch, int pPosRotationIncrements, boolean pTeleport) {
        this.lerpX = pX;
        this.lerpY = pY;
        this.lerpZ = pZ;
        this.lerpYRot = pYaw;
        this.lerpXRot = pPitch;
        this.lerpSteps = 10;
    }

    @Override
    public void tick() {
        this.oldStatus = this.status;
        this.status = this.getStatus();
        if (this.status != BoatEntity.Status.UNDER_WATER && this.status != BoatEntity.Status.UNDER_FLOWING_WATER) {
            this.outOfControlTicks = 0.0F;
        } else {
            ++this.outOfControlTicks;
        }

        if (!this.level.isClientSide && this.outOfControlTicks >= 60.0F) {
            this.ejectPassengers();
        }

        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        if (this.getDamage() > 0.0F) {
            this.setDamage(this.getDamage() - 1.0F);
        }

        super.tick();
        this.tickLerp();
        if (this.isControlledByLocalInstance()) {
            if (this.getPassengers().isEmpty() || !(this.getPassengers().get(0) instanceof PlayerEntity)) {
                this.setPaddleState(false, false, 0);
            }

            this.floatBoat();
            if (this.level.isClientSide) {
                this.controlBoat();
                SkyShips.networkHandler.updateSkyShipControl(this.getPaddleState(0), this.getPaddleState(1), this.inputVertical);
            }

            this.move(MoverType.SELF, this.getDeltaMovement());
        } else {
            this.setDeltaMovement(Vector3d.ZERO);
        }

        for (int i = 0; i <= 1; ++i) {
            if (this.getPaddleState(i)) {
                if (!this.isSilent() && (double) (this.paddlePositions[i] % ((float) Math.PI * 2F)) <= (double) ((float) Math.PI / 4F) && ((double) this.paddlePositions[i] + (double) ((float) Math.PI / 8F)) % (double) ((float) Math.PI * 2F) >= (double) ((float) Math.PI / 4F)) {
                    SoundEvent soundevent = this.getPaddleSound();
                    if (soundevent != null) {
                        Vector3d vector3d = this.getViewVector(1.0F);
                        double d0 = i == 1 ? -vector3d.z : vector3d.z;
                        double d1 = i == 1 ? vector3d.x : -vector3d.x;
                        this.level.playSound(null, this.getX() + d0, this.getY(), this.getZ() + d1, soundevent, this.getSoundSource(), 1.0F, 0.8F + 0.4F * this.random.nextFloat());
                    }
                }

                this.paddlePositions[i] = (float) ((double) this.paddlePositions[i] + (double) ((float) Math.PI / 8F));
            } else {
                this.paddlePositions[i] = 0.0F;
            }
        }

        this.checkInsideBlocks();
        List<Entity> list = this.level.getEntities(this, this.getBoundingBox().inflate(0.2F, -0.01F, 0.2F), EntityPredicates.pushableBy(this));
        if (!list.isEmpty()) {
            boolean flag = !this.level.isClientSide && !(this.getControllingPassenger() instanceof PlayerEntity);

            for (Entity entity : list) {
                if (!entity.hasPassenger(this)) {
                    if (flag && this.getPassengers().size() < 2 && !entity.isPassenger() &&
                            entity.getBbWidth() < this.getBbWidth() && entity instanceof LivingEntity &&
                            !(entity instanceof PlayerEntity)) {
                        entity.startRiding(this);
                    } else {
                        this.push(entity);
                    }
                }
            }
        }

    }

    private void floatBoat() {
        double d1 = this.entityData.get(DATA_ID_VERTICAL) > 0 ? 0.1D : this.isNoGravity() ? 0.0D : (double) -0.0004F;
        double d2 = 0.0D;
        float invFriction = 0.05F;
        if (this.oldStatus == BoatEntity.Status.IN_AIR && this.status != BoatEntity.Status.IN_AIR && this.status != BoatEntity.Status.ON_LAND) {
            this.waterLevel = this.getY(1.0D);
            this.setPos(this.getX(), (double) (this.getWaterLevelAbove() - this.getBbHeight()) + 0.101D, this.getZ());
            this.setDeltaMovement(this.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
            this.lastYd = 0.0D;
            this.status = BoatEntity.Status.IN_WATER;
        } else {
            if (this.status == BoatEntity.Status.IN_WATER) {
                d2 = (this.waterLevel - this.getY()) / (double) this.getBbHeight();
                invFriction = 0.9F;
            } else if (this.status == BoatEntity.Status.UNDER_FLOWING_WATER) {
                d1 = -7.0E-4D;
                invFriction = 0.9F;
            } else if (this.status == BoatEntity.Status.UNDER_WATER) {
                d2 = 0.01F;
                invFriction = 0.45F;
            } else if (this.status == BoatEntity.Status.IN_AIR) {
                invFriction = 0.01F;
            } else if (this.status == BoatEntity.Status.ON_LAND) {
                invFriction = this.landFriction;
                if (this.getControllingPassenger() instanceof PlayerEntity) {
                    this.landFriction /= 2.0F;
                }
            }

            Vector3d vector3d = this.getDeltaMovement();
            this.setDeltaMovement(vector3d.x * (double) invFriction, vector3d.y + d1, vector3d.z * (double) invFriction);
            this.deltaRotation *= invFriction;
            if (d2 > 0.0D) {
                Vector3d vector3d1 = this.getDeltaMovement();
                this.setDeltaMovement(vector3d1.x, (vector3d1.y + d2 * 0.06153846016296973D) * 0.75D, vector3d1.z);
            }
        }

    }

    public float getWaterLevelAbove() {
        AxisAlignedBB axisalignedbb = this.getBoundingBox();
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.maxY);
        int l = MathHelper.ceil(axisalignedbb.maxY - this.lastYd);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        BlockPos.Mutable blockpos$mutable = new BlockPos.Mutable();

        label39:
        for (int k1 = k; k1 < l; ++k1) {
            float f = 0.0F;

            for (int l1 = i; l1 < j; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    blockpos$mutable.set(l1, k1, i2);
                    FluidState fluidstate = this.level.getFluidState(blockpos$mutable);
                    if (fluidstate.is(FluidTags.WATER)) {
                        f = Math.max(f, fluidstate.getHeight(this.level, blockpos$mutable));
                    }

                    if (f >= 1.0F) {
                        continue label39;
                    }
                }
            }

            if (f < 1.0F) {
                return (float) blockpos$mutable.getY() + f;
            }
        }

        return (float) (l + 1);
    }


    protected BoatEntity.Status getStatus() {
        BoatEntity.Status status = this.isUnderwater();
        if (status != null) {
            this.waterLevel = this.getBoundingBox().maxY;
            return status;
        } else if (this.checkInWater()) {
            return BoatEntity.Status.IN_WATER;
        } else {
            float f = this.getGroundFriction();
            if (f > 0.0F) {
                this.landFriction = f;
                return BoatEntity.Status.ON_LAND;
            } else {
                return BoatEntity.Status.IN_AIR;
            }
        }
    }

    public float getGroundFriction() {
        AxisAlignedBB axisalignedbb = this.getBoundingBox();
        AxisAlignedBB axisalignedbb1 = new AxisAlignedBB(axisalignedbb.minX, axisalignedbb.minY - 0.001D, axisalignedbb.minZ, axisalignedbb.maxX, axisalignedbb.minY, axisalignedbb.maxZ);
        int i = MathHelper.floor(axisalignedbb1.minX) - 1;
        int j = MathHelper.ceil(axisalignedbb1.maxX) + 1;
        int k = MathHelper.floor(axisalignedbb1.minY) - 1;
        int l = MathHelper.ceil(axisalignedbb1.maxY) + 1;
        int i1 = MathHelper.floor(axisalignedbb1.minZ) - 1;
        int j1 = MathHelper.ceil(axisalignedbb1.maxZ) + 1;
        VoxelShape voxelshape = VoxelShapes.create(axisalignedbb1);
        float f = 0.0F;
        int k1 = 0;
        BlockPos.Mutable blockpos$mutable = new BlockPos.Mutable();

        for (int l1 = i; l1 < j; ++l1) {
            for (int i2 = i1; i2 < j1; ++i2) {
                int j2 = (l1 != i && l1 != j - 1 ? 0 : 1) + (i2 != i1 && i2 != j1 - 1 ? 0 : 1);
                if (j2 != 2) {
                    for (int k2 = k; k2 < l; ++k2) {
                        if (j2 <= 0 || k2 != k && k2 != l - 1) {
                            blockpos$mutable.set(l1, k2, i2);
                            BlockState blockstate = this.level.getBlockState(blockpos$mutable);
                            if (!(blockstate.getBlock() instanceof LilyPadBlock) &&
                                    VoxelShapes.joinIsNotEmpty(blockstate.getCollisionShape(this.level, blockpos$mutable).move(l1, k2, i2), voxelshape, IBooleanFunction.AND)) {
                                f += blockstate.getSlipperiness(this.level, blockpos$mutable, this);
                                ++k1;
                            }
                        }
                    }
                }
            }
        }

        return f / (float) k1;
    }

    private boolean checkInWater() {
        AxisAlignedBB axisalignedbb = this.getBoundingBox();
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.minY);
        int l = MathHelper.ceil(axisalignedbb.minY + 0.001D);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        boolean flag = false;
        this.waterLevel = Double.MIN_VALUE;
        BlockPos.Mutable mutableBlockPos = new BlockPos.Mutable();

        for (int k1 = i; k1 < j; ++k1) {
            for (int l1 = k; l1 < l; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    mutableBlockPos.set(k1, l1, i2);
                    FluidState fluidstate = this.level.getFluidState(mutableBlockPos);
                    if (fluidstate.is(FluidTags.WATER)) {
                        float f = (float) l1 + fluidstate.getHeight(this.level, mutableBlockPos);
                        this.waterLevel = Math.max(f, this.waterLevel);
                        flag |= axisalignedbb.minY < (double) f;
                    }
                }
            }
        }

        return flag;
    }

    @Nullable
    private BoatEntity.Status isUnderwater() {
        AxisAlignedBB axisalignedbb = this.getBoundingBox();
        double d0 = axisalignedbb.maxY + 0.001D;
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.maxY);
        int l = MathHelper.ceil(d0);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        boolean flag = false;
        BlockPos.Mutable blockpos$mutable = new BlockPos.Mutable();

        for (int k1 = i; k1 < j; ++k1) {
            for (int l1 = k; l1 < l; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    blockpos$mutable.set(k1, l1, i2);
                    FluidState fluidstate = this.level.getFluidState(blockpos$mutable);
                    if (fluidstate.is(FluidTags.WATER) && d0 < (double) ((float) blockpos$mutable.getY() + fluidstate.getHeight(this.level, blockpos$mutable))) {
                        if (!fluidstate.isSource()) {
                            return BoatEntity.Status.UNDER_FLOWING_WATER;
                        }

                        flag = true;
                    }
                }
            }
        }

        return flag ? BoatEntity.Status.UNDER_WATER : null;
    }

    protected SoundEvent getPaddleSound() {
        return null;
    }

    private void tickLerp() {
        if (this.isControlledByLocalInstance()) {
            this.lerpSteps = 0;
            this.setPacketCoordinates(this.getX(), this.getY(), this.getZ());
        }

        if (this.lerpSteps > 0) {
            double d0 = this.getX() + (this.lerpX - this.getX()) / (double) this.lerpSteps;
            double d1 = this.getY() + (this.lerpY - this.getY()) / (double) this.lerpSteps;
            double d2 = this.getZ() + (this.lerpZ - this.getZ()) / (double) this.lerpSteps;
            double d3 = MathHelper.wrapDegrees(this.lerpYRot - (double) this.yRot);
            this.yRot = (float) ((double) this.yRot + d3 / (double) this.lerpSteps);
            this.xRot = (float) ((double) this.xRot + (this.lerpXRot - (double) this.xRot) / (double) this.lerpSteps);
            --this.lerpSteps;
            this.setPos(d0, d1, d2);
            this.setRot(this.yRot, this.xRot);
        }
    }

    private void controlBoat() {
        if (this.isVehicle()) {
            float f = 0.0F;
            if (this.inputLeft) {
                --this.deltaRotation;
            }

            if (this.inputRight) {
                ++this.deltaRotation;
            }

            if (this.inputRight != this.inputLeft && !this.inputUp && !this.inputDown) {
                f += 0.005F;
            }

            this.yRot += this.deltaRotation;
            if (this.inputUp) {
                f += 0.04F;
            }

            if (this.inputDown) {
                f -= 0.005F;
            }

            Vector3d vector3d = this.getDeltaMovement();
            this.setDeltaMovement(new Vector3d(
                    vector3d.x + MathHelper.sin(-this.yRot * ((float) Math.PI / 180F)) * f,
                    this.inputVertical > 0 ? Math.min(0.5F, vector3d.y + 0.1F) : 0F,
                    vector3d.z + MathHelper.cos(this.yRot * ((float) Math.PI / 180F)) * f)
            );
            this.setPaddleState(this.inputRight && !this.inputLeft || this.inputUp, this.inputLeft && !this.inputRight || this.inputUp, inputVertical);
        }
    }

    @Override
    public void positionRider(@Nonnull Entity pPassenger) {
        if (this.hasPassenger(pPassenger)) {
            float f = 0.0F;
            float f1 = (float) ((!this.isAlive() ? (double) 0.01F : this.getPassengersRidingOffset()) + pPassenger.getMyRidingOffset());
            if (this.getPassengers().size() > 1) {
                int i = this.getPassengers().indexOf(pPassenger);
                if (i == 0) {
                    f = 0.2F;
                } else {
                    f = -0.6F;
                }

                if (pPassenger instanceof AnimalEntity) {
                    f = (float) ((double) f + 0.2D);
                }
            }

            Vector3d vector3d = (new Vector3d(f, 0.0D, 0.0D)).yRot(-this.yRot * ((float) Math.PI / 180F) - ((float) Math.PI / 2F));
            pPassenger.setPos(this.getX() + vector3d.x, this.getY() + (double) f1, this.getZ() + vector3d.z);
            pPassenger.yRot += this.deltaRotation;
            pPassenger.setYHeadRot(pPassenger.getYHeadRot() + this.deltaRotation);
            this.clampRotation(pPassenger);
            if (pPassenger instanceof AnimalEntity && this.getPassengers().size() > 1) {
                int j = pPassenger.getId() % 2 == 0 ? 90 : 270;
                pPassenger.setYBodyRot(((AnimalEntity) pPassenger).yBodyRot + (float) j);
                pPassenger.setYHeadRot(pPassenger.getYHeadRot() + (float) j);
            }

        }
    }

    public float getRowingTime(int pSide, float pLimbSwing) {
        return this.getPaddleState(pSide) ? (float) MathHelper.clampedLerp((double) this.paddlePositions[pSide] - (double) ((float) Math.PI / 8F), this.paddlePositions[pSide], pLimbSwing) : 0.0F;
    }

    @Override
    public void onPassengerTurned(@Nonnull Entity pEntityToUpdate) {
        this.clampRotation(pEntityToUpdate);
    }

    protected void clampRotation(Entity pEntityToUpdate) {
        pEntityToUpdate.setYBodyRot(this.yRot);
        float f = MathHelper.wrapDegrees(pEntityToUpdate.yRot - this.yRot);
        float f1 = MathHelper.clamp(f, -105.0F, 105.0F);
        pEntityToUpdate.yRotO += f1 - f;
        pEntityToUpdate.yRot += f1 - f;
        pEntityToUpdate.setYHeadRot(pEntityToUpdate.yRot);
    }

    public void setPaddleState(boolean pLeft, boolean pRight, int vertical) {
        this.entityData.set(DATA_ID_PADDLE_LEFT, pLeft);
        this.entityData.set(DATA_ID_PADDLE_RIGHT, pRight);
        this.entityData.set(DATA_ID_VERTICAL, vertical);
    }

    @Override
    protected void readAdditionalSaveData(@Nonnull CompoundNBT pCompound) {

    }

    @Override
    protected void addAdditionalSaveData(@Nonnull CompoundNBT pCompound) {

    }

    @Override
    @Nonnull
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    protected void addPassenger(@Nonnull Entity passenger) {
        super.addPassenger(passenger);
        if (passenger instanceof PlayerEntity && ((PlayerEntity) passenger).isLocalPlayer()) {
            passenger.yRotO = this.yRot;
            passenger.yRot = this.yRot;
            passenger.setYHeadRot(this.yRot);
        }
        if (this.isControlledByLocalInstance() && this.lerpSteps > 0) {
            this.lerpSteps = 0;
            this.absMoveTo(this.lerpX, this.lerpY, this.lerpZ, (float) this.lerpYRot, (float) this.lerpXRot);
        }
    }

    @Override
    protected boolean canAddPassenger(@Nonnull Entity pPassenger) {
        return this.getPassengers().size() < 2;
    }

    @Nullable
    public Entity getControllingPassenger() {
        List<Entity> list = this.getPassengers();
        return list.isEmpty() ? null : list.get(0);
    }

    public void setInput(boolean pLeftInputDown, boolean pRightInputDown, boolean pForwardInputDown, boolean pBackInputDown, int vertical) {
        this.inputLeft = pLeftInputDown;
        this.inputRight = pRightInputDown;
        this.inputUp = pForwardInputDown;
        this.inputDown = pBackInputDown;
        this.inputVertical = vertical;
    }

    public boolean getPaddleState(int pSide) {
        return this.entityData.get(pSide == 0 ? DATA_ID_PADDLE_LEFT : DATA_ID_PADDLE_RIGHT) && this.getControllingPassenger() != null;
    }

    public void setDamage(float pDamageTaken) {
        this.entityData.set(DATA_ID_DAMAGE, pDamageTaken);
    }

    public float getDamage() {
        return this.entityData.get(DATA_ID_DAMAGE);
    }

    public void setHurtTime(int pTimeSinceHit) {
        this.entityData.set(DATA_ID_HURT, pTimeSinceHit);
    }

    public int getHurtTime() {
        return this.entityData.get(DATA_ID_HURT);
    }

    public void setHurtDir(int pForwardDirection) {
        this.entityData.set(DATA_ID_HURT_DIR, pForwardDirection);
    }

    public int getHurtDir() {
        return this.entityData.get(DATA_ID_HURT_DIR);
    }
}
