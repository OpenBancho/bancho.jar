package com.osuserverlist.bjar.commands;

import java.util.List;

import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.MapRequestEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.models.osu.RankedStatus;
import com.osuserverlist.bjar.modules.main.Commands.BanchoCommand;
import com.osuserverlist.bjar.modules.main.Commands.BanchoCommandHandler;
import com.osuserverlist.bjar.modules.main.Commands.CommandCategory;
import com.osuserverlist.bjar.modules.main.Commands.Session;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.MapRequestRepository;

public class NominationCommands extends BanchoCommandHandler {

    @BanchoCommand(name = "!rank", category = CommandCategory.NOMINATION, description = "Ranks or unranks the currently selected beatmap or beatmap set.", requiredPrivileges = Privileges.NOMINATOR)
    public void rankMapCommand(Player sender, Session session, String[] args) {
        if (args.length == 0) {
            session.sendAnswer("Usage: !rank <set/map> <rank/unrank/love>");
            return;
        }

        String type = args[0].toLowerCase();
        if (!type.equals("set") && !type.equals("map")) {
            session.sendAnswer("Invalid type. Use 'set' or 'map'.");
            return;
        }

        boolean isSet = type.equals("set");

        String rankTypeArg = args.length > 1 ? args[1].toLowerCase() : "rank";
        RankType rankType = RankType.fromName(rankTypeArg);
        if (rankType == null) {
            session.sendAnswer("Invalid rank type. Use 'rank', 'unrank' or 'love'.");
            return;
        }

        long targetId = isSet
                ? sender.getLastNpBeatmapSetId()
                : sender.getLastNpBeatmapId();

        if (targetId == 0) {
            session.sendAnswer("No beatmap " + (isSet ? "set" : "")
                    + " selected. Please select a beatmap "
                    + (isSet ? "set " : "") + "first.");
            return;
        }

        if (isSet) {
            BeatmapRepository.updateStatusBySetId(targetId, rankType.getValue(), true);
        } else {
            BeatmapRepository.updateStatusById(targetId, rankType.getValue(), true);
        }

        MapRequestRepository.closeRequest(targetId, sender.getId());

        logger.info("Player {} changed status of {} {} to {}",
                sender,
                isSet ? "set" : "map",
                targetId,
                rankTypeArg);

        session.sendAnswer("Beatmap " + (isSet ? "set" : "map") + " has been " + rankTypeArg + "ed.");
    }

    @BanchoCommand(name = "!requests", category = CommandCategory.NOMINATION, description = "View and manage pending nomination requests.", requiredPrivileges = Privileges.NOMINATOR)
    public void requestsCommand(Player sender, Session session, String[] args) {

        List<MapRequestEntity> requests = MapRequestRepository.findActive();

        if (args.length == 0) {
            listPendingRequests(session, requests);
            return;
        }

        if (args.length != 2) {
            session.sendAnswer("Usage: !requests <approve|deny> <index>");
            return;
        }

        handleRequestAction(sender, session, requests, args[0].toLowerCase(), args[1]);

    }

    private void listPendingRequests(Session session, List<MapRequestEntity> requests) {
        if (requests.isEmpty()) {
            session.sendAnswer("No pending nomination requests.");
            return;
        }

        for (int i = 0; i < requests.size(); i++) {
            BeatmapEntity beatmap = session.server.osuAPIHandler.getBeatmapById(requests.get(i).getMapId());

            session.sendAnswer("[" + i + "] " + beatmap.toEmbed());
        }

        session.sendAnswer("Use !requests approve <index> or !requests deny <index>.");
    }

    private void handleRequestAction(Player sender,
            Session session,
            List<MapRequestEntity> requests,
            String action,
            String indexArg) {

        int index;

        try {
            index = Integer.parseInt(indexArg);
        } catch (NumberFormatException e) {
            session.sendAnswer("Invalid request index.");
            return;
        }

        if (index < 0 || index >= requests.size()) {
            session.sendAnswer("Request index out of range.");
            return;
        }

        if (!action.equals("approve") && !action.equals("deny")) {
            session.sendAnswer("Unknown action. Use approve or deny.");
            return;
        }

        MapRequestRepository.closeRequest(
                requests.get(index).getMapId(),
                sender.getId());

        session.sendAnswer((action.equals("approve") ? "Approved" : "Denied")
                + " request #" + index + ".");
    }

    @BanchoCommand(name = "!request", category = CommandCategory.GENERAL, description = "Request a beatmap to be ranked.")
    public void requestCommand(Player sender, Session session, String[] args) {

        if (sender.getLastNpBeatmapId() == 0) {
            session.sendAnswer("Please /np a beatmap first to use this command.");
            return;
        }

        BeatmapEntity beatmap = session.server.osuAPIHandler.getBeatmapById(sender.getLastNpBeatmapId());

        if (RankedStatus.getById(beatmap.getStatus()) == RankedStatus.Ranked) {
            session.sendAnswer("This beatmap is already ranked.");
            return;
        }

        if (MapRequestRepository.hasActiveRequest(beatmap.getId().intValue())) {
            session.sendAnswer("This beatmap has already been requested.");
            return;
        }

        MapRequestRepository.create(
                beatmap.getId().intValue(),
                sender.getId());

        session.sendAnswer(
                "Your request for the beatmap '" + beatmap.getTitle() + "' has been submitted successfully.");

        logger.info(
                "Player {} requested beatmap {} ({})",
                sender.getUsername(),
                beatmap.getId(),
                beatmap.getTitle());

    }

    enum RankType {
        RANK(1),
        UNRANK(-2),
        LOVE(4);

        private final int value;

        RankType(int value) {
            this.value = value;
        }

        public static RankType fromName(String name) {
            for (RankType type : values()) {
                if (type.name().equalsIgnoreCase(name)) {
                    return type;
                }
            }
            return null;
        }

        public int getValue() {
            return value;
        }
    }
}
