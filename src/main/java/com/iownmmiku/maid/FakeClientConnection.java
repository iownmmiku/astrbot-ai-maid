package com.iownmmiku.maid;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.KeepAliveC2SPacket;
import net.minecraft.network.packet.s2c.play.KeepAliveS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;

import javax.crypto.Cipher;

/**
 * 假连接：只为了让 ServerPlayerEntity 有个网络处理器，一个字节都不往网络发。
 * KeepAlive 必须当场回确认，否则 15 秒后会被服务器当超时踢掉。
 */
public class FakeClientConnection extends ClientConnection {
    private volatile boolean open = true;

    public FakeClientConnection() {
        super(NetworkSide.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet) {
        inspect(packet);
    }

    @Override
    public void send(Packet<?> packet, PacketCallbacks callbacks) {
        inspect(packet);
    }

    private void inspect(Packet<?> packet) {
        if (packet instanceof KeepAliveS2CPacket ka
                && getPacketListener() instanceof ServerPlayNetworkHandler handler) {
            handler.onKeepAlive(new KeepAliveC2SPacket(ka.getId()));
        }
    }

    @Override
    public void tick() {
        // 不处理任何队列
    }

    @Override
    public void disconnect(Text reason) {
        this.open = false;
        AiMaidMod.LOGGER.info("[AI-Maid] fake connection closed: {}", reason.getString());
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public void setupEncryption(Cipher decryptionCipher, Cipher encryptionCipher) {
    }

    @Override
    public void setCompressionThreshold(int threshold, boolean rejectsBadPackets) {
    }
}
