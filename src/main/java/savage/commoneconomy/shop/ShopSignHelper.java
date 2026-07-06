package savage.commoneconomy.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.WallSignBlock;

/**
 * Helper for shop sign operations.
 */
public class ShopSignHelper {

    public static void updateSign(net.minecraft.server.level.ServerLevel world, BlockPos pos, Shop shop) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SignBlockEntity sign) {
            String action = shop.isBuying() ? "收购" : "出售";
            Component itemNameComponent = shop.getItem().getHoverName();

            String priceText = savage.commoneconomy.EconomyManager.getInstance().format(shop.getPrice());
            int stock = ShopStockCalculator.calculateStock(world, shop);
            
            String stockText;
            if (stock == -1) {
                stockText = "库存: ∞";
            } else {
                stockText = (shop.isBuying() ? "余量: " : "库存: ") + stock;
                shop.setStock(stock);
            }

            Component header = shop.isAdmin() ? 
                    Component.literal("§4[管理员商店]") : 
                    Component.literal("§1" + shop.getOwnerName());

            sign.setText(sign.getFrontText()
                .setMessage(0, header)
                .setMessage(1, itemNameComponent)
                .setMessage(2, Component.literal("§0" + action + ": " + priceText))
                .setMessage(3, Component.literal("§0" + stockText)), true);
            
            world.sendBlockUpdated(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    public static boolean placeSign(Level world, BlockPos chestPos, Shop shop, Direction playerFacing) {
        Direction[] prioritizedDirections;
        if (playerFacing != null && playerFacing.getAxis().isHorizontal()) {
            Direction preferredSide = playerFacing.getOpposite();
            prioritizedDirections = new Direction[]{preferredSide, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        } else {
            prioritizedDirections = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        }

        for (Direction direction : prioritizedDirections) {
            BlockPos signPos = chestPos.relative(direction);
            BlockState signState = world.getBlockState(signPos);

            if (signState.isAir() || signState.canBeReplaced()) {
                BlockState wallSign = getWallSignForDirection(direction);
                if (wallSign != null) {
                    world.setBlock(signPos, wallSign, 3);
                    if (world instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                        updateSign(serverLevel, signPos, shop);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockState getWallSignForDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.NORTH);
            case SOUTH -> net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH);
            case EAST -> net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST);
            case WEST -> net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.WEST);
            default -> null;
        };
    }

    public static BlockPos findSignForChest(Level world, BlockPos chestPos) {
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos signPos = chestPos.relative(direction);
            if (world.getBlockState(signPos).getBlock() instanceof WallSignBlock) {
                return signPos;
            }
        }
        return null;
    }

    public static BlockPos getAttachedChest(Level world, BlockPos signPos) {
        BlockState state = world.getBlockState(signPos);
        if (state.getBlock() instanceof WallSignBlock) {
            Direction dir = state.getValue(WallSignBlock.FACING).getOpposite();
            return signPos.relative(dir);
        }
        return signPos.below();
    }
}
