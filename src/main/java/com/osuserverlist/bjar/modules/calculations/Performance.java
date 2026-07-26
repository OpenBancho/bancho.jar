package com.osuserverlist.bjar.modules.calculations;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import com.osuserverlist.bjar.models.essentials.Score;
import com.osuserverlist.bjar.models.osu.Mods;

import io.github.nanamochi.osu_native.wrapper.attributes.difficulty.DifficultyAttributes;
import io.github.nanamochi.osu_native.wrapper.attributes.performance.PerformanceAttributes;
import io.github.nanamochi.osu_native.wrapper.factories.DifficultyCalculatorFactory;
import io.github.nanamochi.osu_native.wrapper.factories.PerformanceCalculatorFactory;
import io.github.nanamochi.osu_native.wrapper.objects.Beatmap;
import io.github.nanamochi.osu_native.wrapper.objects.Mod;
import io.github.nanamochi.osu_native.wrapper.objects.ModsCollection;
import io.github.nanamochi.osu_native.wrapper.objects.Ruleset;
import io.github.nanamochi.osu_native.wrapper.objects.ScoreInfo;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Performance {
    private IPerformanceCalculator calculator;

    /**
     * Loads the calculator implementation from an env-style string.
     *
     * Accepted formats:
     *   "OSU_NATIVE"
     *   "WEB_SERVICE@host:port"        (assumes http)
     *   "WEB_SERVICE@https://host:port" (explicit scheme also fine)
     */
    public void loadCalculatorFromString(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Performance calculator provider string is empty");
        }

        String[] parts = provider.split("@", 2);
        String type = parts[0].trim().toUpperCase();
        String address = parts.length > 1 ? parts[1].trim() : null;

        switch (type) {
            case "OSU_NATIVE":
                calculator = new OsuNativePerformanceCalculator();
                break;
            case "WEB_SERVICE":
                if (address == null || address.isBlank()) {
                    throw new IllegalArgumentException(
                            "WEB_SERVICE provider requires an address, e.g. WEB_SERVICE@localhost:8080");
                }
                calculator = new WebServicePerformanceCalculator(address);
                break;
            default:
                throw new IllegalArgumentException("Unknown performance calculator provider: " + provider);
        }
    }

    /**
     * Performance points for a play.
     *
     * <p>Returns zero when the .osu file could not be obtained. Every calculator
     * behind this method parses the file first and would fail on empty input, so
     * the check belongs here rather than in each caller.</p>
     */
    public double calculate(Score score, byte[] mapData) {
        if (calculator == null) {
            throw new IllegalStateException("Performance calculator has not been loaded");
        }

        if (mapData == null || mapData.length == 0) {
            return 0.0;
        }

        return calculator.calculate(score, mapData);
    }

    public String getCalculatorClassName() {
        if (calculator == null) {
            return null;
        }
        return calculator.getClass().getSimpleName();
    }

    public static interface IPerformanceCalculator {
        public double calculate(Score score, byte[] mapData);
    }

    public class OsuNativePerformanceCalculator implements IPerformanceCalculator {

        @Override
        public double calculate(Score s, byte[] mapData) {
            Beatmap beatmap = Beatmap.fromBytes(mapData);
            Ruleset ruleset = Ruleset.fromId(s.getMode());
            var ppCalculator = PerformanceCalculatorFactory.create(Ruleset.fromId(s.getMode()));
            var diffCalculator = DifficultyCalculatorFactory.create(ruleset, beatmap);

            ScoreInfo scoreInfo = new ScoreInfo();
            scoreInfo.setAccuracy(s.getAccuracy());
            scoreInfo.setCountMiss(s.getNmiss());
            scoreInfo.setMaxCombo(s.getMax_combo());
            scoreInfo.setCountGreat(s.getN300());
            scoreInfo.setCountOk(s.getN100());
            scoreInfo.setCountMeh(s.getN50());
            scoreInfo.setCountPerfect(s.getNgeki());
            scoreInfo.setCountGood(s.getNkatu());

            ModsCollection mods = ModsCollection.create();

            for (String mod : Mods.convertMods(s.getMods()))
                mods.add(Mod.create(mod));

            mods.add(Mod.create("CL"));

            DifficultyAttributes difficultyAttributes = diffCalculator.calculate(mods);
            PerformanceAttributes attributes = ppCalculator.calculate(ruleset, beatmap, mods, scoreInfo,
                    difficultyAttributes);

            beatmap.close();
            return attributes.getTotal();
        }
    }

    public class WebServicePerformanceCalculator implements IPerformanceCalculator {

        // Shared, reused across calls — OkHttpClient is expensive to create per request
        private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        private static final MediaType OSU_FILE = MediaType.parse("application/octet-stream");

        private final String endpoint;

        public WebServicePerformanceCalculator(String address) {
            // Allow either "host:port" (defaults to http) or a full scheme.
            String base = address.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
                    ? address
                    : "http://" + address;

            // Strip any trailing slash, then append the calculation route.
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            this.endpoint = base + "/calculate";
        }

        @Override
        public double calculate(Score s, byte[] mapData) {
            JSONObject scorePayload = new JSONObject()
                    .put("mode", s.getMode())
                    .put("mods", s.getMods())
                    .put("accuracy", s.getAccuracy())
                    .put("countMiss", s.getNmiss())
                    .put("maxCombo", s.getMax_combo())
                    .put("count300", s.getN300())
                    .put("count100", s.getN100())
                    .put("count50", s.getN50())
                    .put("countGeki", s.getNgeki())
                    .put("countKatu", s.getNkatu());

            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("score", null,
                            RequestBody.create(scorePayload.toString(), JSON))
                    .addFormDataPart("beatmap", "map.osu",
                            RequestBody.create(mapData, OSU_FILE))
                    .build();

            Request request = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException(
                            "PP service returned HTTP " + response.code() + " from " + endpoint);
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                if (responseBody.isBlank()) {
                    throw new RuntimeException("PP service returned an empty response from " + endpoint);
                }

                JSONObject json = new JSONObject(responseBody);
                if (!json.has("pp")) {
                    throw new RuntimeException("PP service response missing 'pp' field: " + responseBody);
                }
                return json.getDouble("pp");

            } catch (IOException e) {
                throw new RuntimeException("Failed to reach PP service at " + endpoint, e);
            }
        }
    }

    public enum PerformanceCalculatorProvider {
        WEB_SERVICE,
        OSU_NATIVE;
    }
}