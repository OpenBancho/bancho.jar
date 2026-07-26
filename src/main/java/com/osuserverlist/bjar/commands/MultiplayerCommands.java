package com.osuserverlist.bjar.commands;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.main.Commands.BanchoCommand;
import com.osuserverlist.bjar.modules.main.Commands.BanchoCommandHandler;
import com.osuserverlist.bjar.modules.main.Commands.CommandCategory;
import com.osuserverlist.bjar.modules.main.Commands.Session;
import com.osuserverlist.bjar.packets.server.MultiplayerServerPackets.MatchTransferHostPacket;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.GetAttentionPacket;

@BanchoCommand(
        name = "!mp",
        category = CommandCategory.GENERAL,
        description = "Multiplayer commands.",
        isHidden = true
)
public class MultiplayerCommands extends BanchoCommandHandler {

    public record MultiplayerCommandInfo(String name, String description, Privileges requiredPrivileges) {}

    private record MultiplayerCommandEntry(String name, Method method, String description,
                                            Privileges requiredPrivileges) {}

    private static final Map<String, MultiplayerCommandEntry> COMMANDS = Arrays
            .stream(MultiplayerCommands.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(MultiplayerCommand.class))
            .collect(Collectors.toMap(
                    method -> method.getAnnotation(MultiplayerCommand.class).name().toLowerCase(),
                    method -> {
                        MultiplayerCommand annotation = method.getAnnotation(MultiplayerCommand.class);
                        return new MultiplayerCommandEntry(annotation.name(), method, annotation.description(),
                                annotation.requiredPrivileges());
                    }));

    /** Returns every registered !mp sub-command, sorted alphabetically by name. */
    public static List<MultiplayerCommandInfo> getCommands() {
        return COMMANDS.values().stream()
                .map(entry -> new MultiplayerCommandInfo(entry.name(), entry.description(), entry.requiredPrivileges()))
                .sorted(Comparator.comparing(MultiplayerCommandInfo::name))
                .toList();
    }

    @Override
    public void handle(Player sender, Session session, String[] args) {
        if (args.length == 0) {
            session.sendAnswer("No command specified.");
            return;
        }

        Match match = sender.getMatch();
        if (match == null) {
            session.sendAnswer("You are not in a match.");
            return;
        }

        MultiplayerCommandEntry commandEntry = COMMANDS.get(args[0].toLowerCase());
        if (commandEntry == null) {
            session.sendAnswer("Unknown command: !mp " + args[0]);
            return;
        }

        if (sender.getServerPrivileges() < commandEntry.requiredPrivileges().value) {
            session.sendAnswer("You do not have permission to use this command.");
            return;
        }

        String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);
        try {
            commandEntry.method().invoke(this, sender, session, remainingArgs, match);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to execute multiplayer command: " + args[0], e);
        }
    }

    @MultiplayerCommand(
            name = "start",
            description = "Starts the match if you are the host."
    )
    public void start(Player sender, Session session, String[] args, Match match) {
        // TODO: Handle multiplayer commands
    }

    @MultiplayerCommand(
            name = "randpw",
            description = "Changes the match password."
    )
    public void randpw(Player sender, Session session, String[] args, Match match) {
        String randomPassword = UUID.randomUUID().toString();
        match.setRoomPassword(randomPassword);
        match.enqueUpdate();
        session.sendAnswer("Match password changed to: " + randomPassword);
    }

    @MultiplayerCommand(
            name = "host",
            description = "Transfers host to another player."
    )
    public void host(Player sender, Session session, String[] args, Match match) {
        if (args.length == 0) {
            session.sendAnswer("Usage: !mp host <player>");
            return;
        }

        String targetPlayerName = args[0];
        Player targetPlayer = findPlayerInMatch(match, targetPlayerName);
        if (targetPlayer == null) {
            session.sendAnswer("Player not found in the match.");
            return;
        }

        match.setHostId(targetPlayer.getId());
        targetPlayer.sendPacket(new MatchTransferHostPacket());
        match.enqueUpdate();
        session.sendAnswer("Host transferred to: " + targetPlayerName);
    }

    @MultiplayerCommand(
            name = "forcejoin",
            description = "Forces a player to join the match.",
            requiredPrivileges = Privileges.ADMINISTRATOR
    )
    public void forcejoin(Player sender, Session session, String[] args, Match match) {
        if (args.length == 0) {
            session.sendAnswer("Usage: !mp forcejoin <player>");
            return;
        }

        String targetPlayerName = args[0];
        Player targetPlayer = session.server.playerManager.getByFilter(p -> p.getUsername().equalsIgnoreCase(targetPlayerName));

        if (targetPlayer == null) {
            session.sendAnswer("Player not found.");
            return;
        }

        session.server.matchManager.joinMatch(match, targetPlayer);
        targetPlayer.sendPacket(new GetAttentionPacket());
        session.sendAnswer("Player " + targetPlayerName + " has been forced to join the match.");
    }

    private Player findPlayerInMatch(Match match, String username) {
        return match.getPlayers().stream()
                .filter(p -> p.getUsername().equalsIgnoreCase(username))
                .findFirst()
                .orElse(null);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    private @interface MultiplayerCommand {
        String name();
        String description();
        Privileges requiredPrivileges() default Privileges.NONE;
    }
}
