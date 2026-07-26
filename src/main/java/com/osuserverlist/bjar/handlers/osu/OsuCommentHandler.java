package com.osuserverlist.bjar.handlers.osu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.database.CommentEntity;
import com.osuserverlist.bjar.models.database.CommentEntity.TargetType;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.CommentRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * In-game comments that scroll across the screen while a replay plays.
 *
 * <p>One route serves both directions. {@code a=get} returns every comment the
 * client should draw over the replay it is about to watch, and {@code a=post}
 * stores a new one.</p>
 *
 * <p>The listing format is tab separated: {@code time\ttarget\tformat\ttext},
 * where the format field carries the badge colour. Nominators are drawn in the
 * bat colour and donors get their own, which is why the privilege bits are read
 * per comment author rather than trusted from the client.</p>
 */
@Host("osu.")
@Path("/web/osu-comment.php")
@HttpMethod("POST")
public class OsuCommentHandler implements Handler {

    private static final int MAX_COMMENT_LENGTH = 80;

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Player player = OsuWebAuth.authenticate(ctx.formParam("u"), ctx.formParam("p"));

        if (player == null) {
            ctx.status(401).result("");
            return;
        }

        String action = ctx.formParam("a");

        int mapId = intParam(ctx, "b");
        int mapSetId = intParam(ctx, "s");
        int scoreId = intParam(ctx, "r");

        if ("get".equals(action)) {
            listComments(ctx, scoreId, mapId, mapSetId);
            return;
        }

        if ("post".equals(action)) {
            createComment(ctx, player, scoreId, mapId, mapSetId);
            return;
        }

        ctx.status(400).result("");
    }

    private void listComments(Context ctx, int scoreId, int mapId, int mapSetId) {
        List<CommentEntity> comments = CommentRepository.findForReplay(scoreId, mapId, mapSetId);

        List<String> lines = new ArrayList<>(comments.size());

        for (CommentEntity comment : comments) {
            int authorPrivileges = privilegesOfAuthor(comment.getUserid());

            String format;

            if (Privileges.has(authorPrivileges, Privileges.NOMINATOR)) {
                format = "bat";
            } else if (Privileges.has(authorPrivileges, Privileges.SUPPORTER)
                    || Privileges.has(authorPrivileges, Privileges.PREMIUM)) {
                format = "supporter";
            } else {
                format = "";
            }

            if (comment.getColour() != null && !comment.getColour().isBlank()) {
                format += "|" + comment.getColour();
            }

            lines.add(String.join("\t",
                    String.valueOf(comment.getTime()),
                    comment.getTargetType().name(),
                    format,
                    comment.getComment()));
        }

        ctx.status(200).result(String.join("\n", lines));
    }

    private void createComment(Context ctx, Player player, int scoreId, int mapId, int mapSetId) {
        TargetType target = parseTarget(ctx.formParam("target"));

        String text = ctx.formParam("comment");
        Integer startTime = parseInteger(ctx.formParam("starttime"));

        if (target == null || startTime == null || text == null || text.isBlank()) {
            ctx.status(400).result("");
            return;
        }

        if (text.length() > MAX_COMMENT_LENGTH) {
            text = text.substring(0, MAX_COMMENT_LENGTH);
        }

        int targetId = switch (target) {
            case song -> mapSetId;
            case map -> mapId;
            case replay -> scoreId;
        };

        if (targetId <= 0) {
            ctx.status(400).result("");
            return;
        }

        CommentEntity comment = new CommentEntity();
        comment.setTargetType(target);
        comment.setTargetId(targetId);
        comment.setUserid(player.getId());
        comment.setTime(Math.max(startTime, 0));
        comment.setComment(text);
        comment.setColour(sanitiseColour(ctx.formParam("f"), player));

        CommentRepository.save(comment);

        // The client ignores the body of a successful post.
        ctx.status(200).result("");
    }

    /**
     * Coloured comments are a donor perk, so the colour is dropped rather than
     * rejected when an ordinary player sends one.
     */
    private String sanitiseColour(String colour, Player player) {
        if (colour == null || colour.isBlank()) {
            return null;
        }

        String trimmed = colour.trim().toLowerCase(Locale.ROOT);

        if (trimmed.length() != 6 || !trimmed.matches("[0-9a-f]{6}")) {
            return null;
        }

        int privileges = OsuWebAuth.privilegesOf(player);

        boolean donor = Privileges.has(privileges, Privileges.SUPPORTER)
                || Privileges.has(privileges, Privileges.PREMIUM);

        return donor ? trimmed : null;
    }

    private int privilegesOfAuthor(Integer userId) {
        if (userId == null) {
            return 0;
        }

        var author = com.osuserverlist.bjar.repos.UserRepository.findById(userId);

        if (author == null || author.getPrivileges() == null) {
            return 0;
        }

        return author.getPrivileges();
    }

    private TargetType parseTarget(String raw) {
        if (raw == null) {
            return null;
        }

        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "song" -> TargetType.song;
            case "map" -> TargetType.map;
            case "replay" -> TargetType.replay;
            default -> null;
        };
    }

    private int intParam(Context ctx, String name) {
        Integer value = parseInteger(ctx.formParam(name));
        return value == null ? 0 : value;
    }

    private Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
