package com.osuserverlist.bjar.commands;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.account.DonorService;
import com.osuserverlist.bjar.modules.main.Commands.BanchoCommand;
import com.osuserverlist.bjar.modules.main.Commands.BanchoCommandHandler;
import com.osuserverlist.bjar.modules.main.Commands.CommandCategory;
import com.osuserverlist.bjar.modules.main.Commands.Session;
import com.osuserverlist.bjar.modules.util.Validation;

/**
 * Supporter status inside the game.
 *
 * <p>Supporter itself is handed out by the admin panel through {@code donor_end}.
 * The perks that come with it — the username change and the custom badge — are
 * managed on the website's settings page, because the badge needs a picture and
 * the chat is a poor place to hand one over. All that is left in game is looking
 * up the status and being pointed at that page.</p>
 */
public class DonorCommands extends BanchoCommandHandler {

    @BanchoCommand(
            name = "!donor",
            category = CommandCategory.GENERAL,
            description = "Shows your supporter status.")
    public void donor(Player sender, Session session, String[] args) {
        String settingsUrl = "https://" + App.server.enviromentConfig.getDomain() + "/settings";

        if (!DonorService.isDonor(sender)) {
            session.sendAnswer("You are not a supporter. Supporter unlocks a username change and a "
                    + "custom badge, both on [" + settingsUrl + " your settings page]"
                    + (DonorService.directRequiresDonor() ? ", plus osu!direct." : "."));
            return;
        }

        int remaining = DonorService.remainingSeconds(sender);

        String perks = "Your username change and custom badge live on ["
                + settingsUrl + " your settings page]"
                + (DonorService.directRequiresDonor() ? ", and osu!direct is unlocked." : ".");

        if (remaining <= 0) {
            session.sendAnswer("You are a permanent supporter. " + perks);
            return;
        }

        session.sendAnswer(String.format("Supporter active for another %s. %s",
                Validation.formatDuration(remaining), perks));
    }
}
