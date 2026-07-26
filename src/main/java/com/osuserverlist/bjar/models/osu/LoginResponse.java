package com.osuserverlist.bjar.models.osu;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.javalin.http.Context;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@ToString
public class LoginResponse {
    private boolean success = false;

    @Setter
    private String uuid;

    private String ip;

    private String username;
    private String passwordMd5;

    private String buildName;
    private String utcOffset;
    private boolean displayCityLocation;
    private boolean friendOnlyDms;

    private String executeableNameHash;
    private String networkInterfacesHash;
    private String registryKeyHash;
    private String diskDriveHash;

    public static LoginResponse parse(Context ctx) {
        LoginResponse response = new LoginResponse();
        String[] body = ctx.body().split("\n");

        if (body.length < 3) {
            return null;
        }

        response.uuid = UUID.randomUUID().toString();
        response.username = body[0];
        response.passwordMd5 = body[1];
        response.ip = ctx.header("X-Real-IP");

        String[] clientInfo = body[2].split("\\|");

        if (clientInfo.length < 5) {
            return null;
        }

        response.buildName = clientInfo[0];
        response.utcOffset = clientInfo[1];
        response.displayCityLocation = Integer.parseInt(clientInfo[2]) == 1;
        response.friendOnlyDms = Integer.parseInt(clientInfo[4]) == 1;

        String[] clientHashes = clientInfo[3].split(":");
        if (clientHashes.length < 5) {
            return null;
        }

        response.executeableNameHash = clientHashes[0];
        response.networkInterfacesHash = clientHashes[1];
        response.registryKeyHash = clientHashes[2];
        response.diskDriveHash = clientHashes[3];
        response.success = true;

        return response;
    }

    public static LocalDate parseOsuVersionDate(String buildName) {
        if (buildName == null) {
            return LocalDate.now();
        }

        Matcher matcher = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(buildName);
        if (!matcher.find()) {
            return LocalDate.now();
        }

        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException ex) {
            return LocalDate.now();
        }
    }
}
