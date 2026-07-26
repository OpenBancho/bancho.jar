package com.osuserverlist.bjar.modules.main;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.SendMessagePacket;

import io.github.classgraph.ClassGraph;
import lombok.AllArgsConstructor;

public class Commands {
    private static Logger logger = LoggerFactory.getLogger(BanchoCommandHandler.class);
    private static Map<String, CommandInfo> commandMap = new HashMap<>();

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    public static @interface BanchoCommand {
        String name();
        CommandCategory category();
        boolean isHidden() default false;
        String description() default "";
        Privileges requiredPrivileges() default Privileges.UNRESTRICTED;
    }

    @AllArgsConstructor
    public static class Session {
        public Server server;
        private Set<Player> recievers;
        private String target;

        public void sendAnswer(String message) {
            for (Player player : recievers) {
                player.sendPacket(new SendMessagePacket(server.botPlayer.getUsername(), message, target,
                        server.botPlayer.getId()));
            }
        }

        /**
         * Sends a message as a private message from BanchoBot to a single player,
         * regardless of the channel the command was invoked in.
         */
        public void sendAnswerAsDm(Player recipient, String message) {
            recipient.sendPacket(new SendMessagePacket(server.botPlayer.getUsername(), message,
                    server.botPlayer.getUsername(), server.botPlayer.getId()));
        }
    }

    public static class BanchoCommandHandler {
        protected static Logger logger = LoggerFactory.getLogger(BanchoCommandHandler.class);

        public void handle(Player sender, Session session, String[] args) { }
    }

    public static void processCommand(Player sender, String commandLine, String target, Set<Player> recievers) {
        if (!(commandLine.startsWith("!") || commandLine.startsWith("/"))) {
            return;
        }

        String[] command = commandLine.split(" ");
        String commandName = command[0].toLowerCase();

        Server server = App.server;
        CommandInfo commandInfo = Commands.getCommand(commandName);
        Session session = new Session(server, recievers, target);

        if (commandInfo == null) {
            session.sendAnswer("Unknown command: " + commandName + " use !help for a list of commands");
            return;
        }

        if (sender.getServerPrivileges() < commandInfo.requiredPrivileges && commandInfo.requiredPrivileges != 0) {
            session.sendAnswer("You don't have permission to use this command.");
            return;
        }

        String[] args = new String[command.length - 1];
        System.arraycopy(command, 1, args, 0, args.length);

        commandInfo.handler.handle(sender, session, args);
    }

    public static void processNp(Player sender, String message) {
        Pattern pattern = Pattern.compile("beatmapsets/(\\d+)#/(\\d+)");
        Matcher matcher = pattern.matcher(message);

        if (matcher.find()) {
            String beatmapId = matcher.group(2);
            String beatmapSetId = matcher.group(1);
            sender.setLastNpBeatmapId(Long.parseLong(beatmapId));
            sender.setLastNpBeatmapSetId(Long.parseLong(beatmapSetId));
        }
    }

    public static void registerAnnotatedHandlers(String packageName) {
        try (var scan = new ClassGraph()
                .enableClassInfo()
                .enableAnnotationInfo()
                .acceptPackages(packageName)
                .scan()) {
            scan.getAllClasses().forEach(classInfo -> {
                Class<?> handlerClass = classInfo.loadClass();

                BanchoCommand classAnnotation = handlerClass.getAnnotation(BanchoCommand.class);
                boolean hasMethodCommands = false;

                for (Method method : handlerClass.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(BanchoCommand.class)) {
                        hasMethodCommands = true;
                        break;
                    }
                }

                Object handlerInstance = null;

                if (classAnnotation != null || hasMethodCommands) {
                    try {
                        handlerInstance = handlerClass.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        logger.error("Error occurred while instantiating command handler for class: {}",
                                handlerClass.getName(), e);
                        return;
                    }
                }

                if (classAnnotation != null) {
                    registerCommand(classAnnotation, (BanchoCommandHandler) handlerInstance);
                }

                for (Method method : handlerClass.getDeclaredMethods()) {
                    BanchoCommand methodAnnotation = method.getAnnotation(BanchoCommand.class);

                    if (methodAnnotation == null) {
                        continue;
                    }

                    registerCommand(methodAnnotation, createMethodHandler(handlerInstance, method));
                }
            });
        }
    }

    private static void registerCommand(BanchoCommand commandAnnotation, BanchoCommandHandler handler) {
        String commandName = commandAnnotation.name();

        CommandInfo commandInfo = new CommandInfo(
                commandName,
                commandAnnotation.category(),
                commandAnnotation.isHidden(),
                commandAnnotation.description(),
                commandAnnotation.requiredPrivileges().getValue(),
                handler);

        commandMap.put(commandName, commandInfo);
    }

    private static BanchoCommandHandler createMethodHandler(Object handlerInstance, Method method) {
        method.setAccessible(true);

        BanchoCommandHandler handler = new BanchoCommandHandler() {
            @Override
            public void handle(
                    Player sender,
                    Session session,
                    String[] args) {
                try {
                    Object target = Modifier.isStatic(method.getModifiers()) ? null : handlerInstance;
                    method.invoke(target, sender, session, args);
                } catch (Exception e) {
                    logger.error(
                            "Error occurred while invoking command method {}#{}",
                            method.getDeclaringClass().getName(),
                            method.getName(),
                            e);
                }
            }
        };

        return handler;
    }

    public static void finalizeCommandRegistration() {
        logger.info("Loaded <{}> commands", commandMap.size());
    }

    public static Collection<CommandInfo> getAllCommands() {
        return commandMap.values();
    }

    public static CommandInfo getCommand(String name) {
        return commandMap.get(name);
    }

    @AllArgsConstructor
    public static class CommandInfo {
        public String name;
        public CommandCategory category;
        public boolean isHidden;
        public String description;
        public int requiredPrivileges;
        public BanchoCommandHandler handler;
    }

    public static enum CommandCategory {
        GENERAL,
        ADMINISTRATION,
        MODERATION,
        NOMINATION,
        FUN,
        MUSIC,
        MISC
    }

}
