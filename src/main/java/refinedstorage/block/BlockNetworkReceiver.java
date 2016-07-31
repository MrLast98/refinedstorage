package refinedstorage.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import refinedstorage.RefinedStorage;
import refinedstorage.RefinedStorageGui;
import refinedstorage.tile.TileNetworkReceiver;

public class BlockNetworkReceiver extends BlockNode {
    public BlockNetworkReceiver() {
        super("network_receiver");
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, ItemStack heldItem, EnumFacing side, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            player.openGui(RefinedStorage.INSTANCE, RefinedStorageGui.NETWORK_RECEIVER, world, pos.getX(), pos.getY(), pos.getZ());
        }

        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileNetworkReceiver();
    }

    @Override
    public EnumPlacementType getPlacementType() {
        return null;
    }

    @Override
    public boolean hasConnectivityState() {
        return true;
    }
}
