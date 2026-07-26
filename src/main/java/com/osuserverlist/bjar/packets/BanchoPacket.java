package com.osuserverlist.bjar.packets;

import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class BanchoPacket {
    public int id;
    public boolean compressed;
    public ClientPackets type;
}
