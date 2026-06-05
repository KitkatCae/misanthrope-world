package exp.CCnewmods.misanthrope_world.crackrender.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class CrackNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("misanthrope_core", "crack_sync"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(
                id++,
                CrackSyncPacket.class,
                CrackSyncPacket::encode,
                CrackSyncPacket::decode,
                CrackSyncPacket::handle
        );
    }
}
