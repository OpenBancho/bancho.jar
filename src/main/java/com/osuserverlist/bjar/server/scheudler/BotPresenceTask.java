package com.osuserverlist.bjar.server.scheudler;

import java.util.Random;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.ConfigModels.PresenceConfiguration;
import com.osuserverlist.bjar.models.ConfigModels.PresenceConfiguration.PresenceInfo;

public class BotPresenceTask implements Runnable {

    private final PresenceConfiguration config = PresenceConfiguration.load();

    @Override
    public void run() {
        Server server = App.server;

        Random random = new Random();
        PresenceInfo presenceInfo = config.getPresenceInfos().get(random.nextInt(config.getPresenceInfos().size()));
        server.botPlayer.setAction((byte)presenceInfo.getActionStatus().id);
        server.botPlayer.setActionText(presenceInfo.getDetails());
    }

}
