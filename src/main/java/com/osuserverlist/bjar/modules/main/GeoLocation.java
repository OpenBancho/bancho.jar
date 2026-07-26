package com.osuserverlist.bjar.modules.main;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.Location;

import lombok.Value;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GeoLocation {
    private static Logger logger = LoggerFactory.getLogger(GeoLocation.class);
    private static GeoProvider provider;

    public static interface GeoProvider {
        public GeoResponse getCountryCode(String ip);
    }

    @Value
    public static class GeoResponse {
        private int countryId;
        private float latitude;
        private float longitude;
    }

    public static enum ProviderType {
        IPAPI,
        MAXMIND
    }

    public static GeoProvider getProvider() {
        if(provider == null) {
            logger.error("Geolocation provider is not available");
        }
        return provider;
    }

    public static void loadProviderFromString(String provider){
        switch (provider.toUpperCase()) {
            case "IPAPI":
                GeoLocation.provider = new CachingGeoProvider(new IPAPIProvider());
                break;
            case "MAXMIND":
                GeoLocation.provider = new CachingGeoProvider(new MaxMindProvider());
                break;
            default:
                throw new IllegalArgumentException("Unknown provider: " + provider);
        }
    }

    /**
     * Decorator that adds a caching layer on top of any {@link GeoProvider}.
     * Individual providers no longer need to implement their own caching.
     */
    public static class CachingGeoProvider implements GeoProvider {

        private final LoadingCache<String, GeoResponse> cache;

        public CachingGeoProvider(GeoProvider delegate) {
            this(delegate, 24, TimeUnit.HOURS, 50_000);
        }

        public CachingGeoProvider(GeoProvider delegate, long expireAfterWrite, TimeUnit unit, long maximumSize) {
            this.cache = Caffeine.newBuilder()
                    .expireAfterWrite(expireAfterWrite, unit)
                    .maximumSize(maximumSize)
                    .build(delegate::getCountryCode);
        }

        @Override
        public GeoResponse getCountryCode(String ip) {
            return cache.get(ip);
        }
    }

    public static class IPAPIProvider implements GeoProvider {
        public final String URL = "http://ip-api.com/json/%ip%?fields=status,message,countryCode,lat,lon";
        private final static OkHttpClient client = new OkHttpClient();

        @Override
        public GeoResponse getCountryCode(String ip) {
            return fetch(ip);
        }

        private GeoResponse fetch(String ip) {
            String url = URL.replace("%ip%", ip);
            Request request = new Request.Builder().url(url).build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String responseBody = response.body().string();
                JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

                if ("success".equals(json.get("status").getAsString())) {
                    return new GeoResponse(
                            Country.getIndexByCode(json.get("countryCode").getAsString()),
                            json.get("lat").getAsFloat(),
                            json.get("lon").getAsFloat()
                    );
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to fetch country code", e);
            }
            return null;
        }
    }

    public static class MaxMindProvider implements GeoProvider {

        private static final String DB_URL =
                "https://github.com/Skiddle-ID/geoip2-mirror/releases/download/20260605/GeoLite2-City.mmdb";

        // Where the mmdb file is cached on disk between runs.
        private static final File DB_FILE = new File("data/", "GeoLite2-City.mmdb");

        private final DatabaseReader reader;

        public MaxMindProvider() {
            try {
                ensureDatabaseDownloaded();
                this.reader = new DatabaseReader.Builder(DB_FILE).build();
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize MaxMind database", e);
            }
        }

        private static final OkHttpClient HTTP = new OkHttpClient();

        private static void ensureDatabaseDownloaded() throws IOException {
            if (DB_FILE.exists() && DB_FILE.length() > 0) return;

            logger.info("Downloading GeoLite2-City.mmdb...");

            File tmp = File.createTempFile("GeoLite2-City", ".part", DB_FILE.getParentFile());

            try (Response response = HTTP.newCall(new Request.Builder().url(DB_URL).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Download failed: HTTP " + response.code());
                }

                Files.copy(response.body().byteStream(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp.toPath(), DB_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                tmp.delete();
            }

            logger.info("GeoLite2-City.mmdb downloaded.");
        }

        @Override
        public GeoResponse getCountryCode(String ip) {
            return fetch(ip);
        }

        private GeoResponse fetch(String ip) {
            try {
                InetAddress address = InetAddress.getByName(ip);
                CityResponse response = reader.city(address);

                String isoCode = response.country().isoCode();
                if (isoCode == null) {
                    return null;
                }

                Location location = response.location();
                float lat = location.latitude() != null
                        ? location.latitude().floatValue() : 0f;
                float lon = location.longitude() != null
                        ? location.longitude().floatValue() : 0f;

                return new GeoResponse(Country.getIndexByCode(isoCode), lat, lon);
            } catch (Exception e) {
                logger.warn("Failed to resolve geolocation for " + ip, e);
                return null;
            }
        }
    }

    public static enum Country {
        XX("Unknown"),
        OC("Oceania"),
        EU("Europe"),
        AD("Andorra"),
        AE("United Arab Emirates"),
        AF("Afghanistan"),
        AG("Antigua and Barbuda"),
        AI("Anguilla"),
        AL("Albania"),
        AM("Armenia"),
        AN("Netherlands Antilles"),
        AO("Angola"),
        AQ("Antarctica"),
        AR("Argentina"),
        AS("American Samoa"),
        AT("Austria"),
        AU("Australia"),
        AW("Aruba"),
        AZ("Azerbaijan"),
        BA("Bosnia and Herzegovina"),
        BB("Barbados"),
        BD("Bangladesh"),
        BE("Belgium"),
        BF("Burkina Faso"),
        BG("Bulgaria"),
        BH("Bahrain"),
        BI("Burundi"),
        BJ("Benin"),
        BM("Bermuda"),
        BN("Brunei Darussalam"),
        BO("Bolivia"),
        BR("Brazil"),
        BS("The Bahamas"),
        BT("Bhutan"),
        BV("Bouvet Island"),
        BW("Botswana"),
        BY("Belarus"),
        BZ("Belize"),
        CA("Canada"),
        CC("Cocos (Keeling) Islands"),
        CD("Democratic Republic of the Congo"),
        CF("Central African Republic"),
        CG("Republic of the Congo"),
        CH("Switzerland"),
        CI("Côte d'Ivoire"),
        CK("Cook Islands"),
        CL("Chile"),
        CM("Cameroon"),
        CN("China"),
        CO("Colombia"),
        CR("Costa Rica"),
        CU("Cuba"),
        CV("Cape Verde"),
        CX("Christmas Island"),
        CY("Cyprus"),
        CZ("Czech Republic"),
        DE("Germany"),
        DJ("Djibouti"),
        DK("Denmark"),
        DM("Dominica"),
        DO("Dominican Republic"),
        DZ("Algeria"),
        EC("Ecuador"),
        EE("Estonia"),
        EG("Egypt"),
        EH("Western Sahara"),
        ER("Eritrea"),
        ES("Spain"),
        ET("Ethiopia"),
        FI("Finland"),
        FJ("Fiji"),
        FK("Falkland Islands (Malvinas)"),
        FM("Micronesia, Federated States of Micronesia"),
        FO("Faroe Islands"),
        FR("France"),
        FX("France, Metropolitan"),
        GA("Gabon"),
        GB("United Kingdom"),
        GD("Grenada"),
        GE("Georgia"),
        GF("French Guiana"),
        GH("Ghana"),
        GI("Gibraltar"),
        GL("Greenland"),
        GM("Gambia"),
        GN("Guinea"),
        GP("Guadeloupe"),
        GQ("Equatorial Guinea"),
        GR("Greece"),
        GS("South Georgia and the South Sandwich Islands"),
        GT("Guatemala"),
        GU("Guam"),
        GW("Guinea-Bissau"),
        GY("Guyana"),
        HK("Hong Kong"),
        HM("Heard Island and McDonald Islands"),
        HN("Honduras"),
        HR("Croatia"),
        HT("Haiti"),
        HU("Hungary"),
        ID("Indonesia"),
        IE("Ireland"),
        IL("Israel"),
        IN("India"),
        IO("British Indian Ocean Territory"),
        IQ("Iraq"),
        IR("Iran, Islamic Republic of Iran"),
        IS("Iceland"),
        IT("Italy"),
        JM("Jamaica"),
        JO("Jordan"),
        JP("Japan"),
        KE("Kenya"),
        KG("Kyrgyzstan"),
        KH("Cambodia"),
        KI("Kiribati"),
        KM("Comoros"),
        KN("Saint Kitts and Nevis"),
        KP("Korea, Democratic People's Republic of Korea"),
        KR("Korea, Republic of Korea"),
        KW("Kuwait"),
        KY("Cayman Islands"),
        KZ("Kazakhstan"),
        LA("Lao People's Democratic Republic"),
        LB("Lebanon"),
        LC("Saint Lucia"),
        LI("Liechtenstein"),
        LK("Sri Lanka"),
        LR("Liberia"),
        LS("Lesotho"),
        LT("Lithuania"),
        LU("Luxembourg"),
        LV("Latvia"),
        LY("Libyan Arab Jamahiriya"),
        MA("Morocco"),
        MC("Monaco"),
        MD("Moldova, Republic of Moldova"),
        MG("Madagascar"),
        MH("Marshall Islands"),
        MK("Macedonia, the Former Yugoslav Republic of Macedonia"),
        ML("Mali"),
        MM("Myanmar"),
        MN("Mongolia"),
        MO("Macau"),
        MP("Northern Mariana Islands"),
        MQ("Martinique"),
        MR("Mauritania"),
        MS("Montserrat"),
        MT("Malta"),
        MU("Mauritius"),
        MV("Maldives"),
        MW("Malawi"),
        MX("Mexico"),
        MY("Malaysia"),
        MZ("Mozambique"),
        NA("Namibia"),
        NC("New Caledonia"),
        NE("Niger"),
        NF("Norfolk Island"),
        NG("Nigeria"),
        NI("Nicaragua"),
        NL("Netherlands"),
        NO("Norway"),
        NP("Nepal"),
        NR("Nauru"),
        NU("Niue"),
        NZ("New Zealand"),
        OM("Oman"),
        PA("Panama"),
        PE("Peru"),
        PF("French Polynesia"),
        PG("Papua New Guinea"),
        PH("Philippines"),
        PK("Pakistan"),
        PL("Poland"),
        PM("Saint Pierre and Miquelon"),
        PN("Pitcairn"),
        PR("Puerto Rico"),
        PS("Palestinian Territory, Occupied"),
        PT("Portugal"),
        PW("Palau"),
        PY("Paraguay"),
        QA("Qatar"),
        RE("Réunion"),
        RO("Romania"),
        RU("Russian Federation"),
        RW("Rwanda"),
        SA("Saudi Arabia"),
        SB("Solomon Islands"),
        SC("Seychelles"),
        SD("Sudan"),
        SE("Sweden"),
        SG("Singapore"),
        SH("Saint Helena, Ascension and Tristan da Cunha"),
        SI("Slovenia"),
        SJ("Svalbard and Jan Mayen"),
        SK("Slovakia"),
        SL("Sierra Leone"),
        SM("San Marino"),
        SN("Senegal"),
        SO("Somalia"),
        SR("Suriname"),
        ST("Sao Tome and Principe"),
        SV("El Salvador"),
        SY("Syrian Arab Republic"),
        SZ("Eswatini"),
        TC("Turks and Caicos Islands"),
        TD("Chad"),
        TF("French Southern Territories"),
        TG("Togo"),
        TH("Thailand"),
        TJ("Tajikistan"),
        TK("Tokelau"),
        TM("Turkmenistan"),
        TN("Tunisia"),
        TO("Tonga"),
        TL("Timor-Leste"),
        TR("Turkey"),
        TT("Trinidad and Tobago"),
        TV("Tuvalu"),
        TW("Taiwan"),
        TZ("Tanzania"),
        UA("Ukraine"),
        UG("Uganda"),
        UM("United States Minor Outlying Islands"),
        US("United States"),
        UY("Uruguay"),
        UZ("Uzbekistan"),
        VA("Holy See"),
        VC("Saint Vincent"),
        VE("Venezuela"),
        VG("Virgin Islands, British"),
        VI("Virgin Islands, U.S."),
        VN("Vietnam"),
        VU("Vanuatu"),
        WF("Wallis and Futuna"),
        WS("Samoa"),
        YE("Yemen"),
        YT("Mayotte"),
        RS("Serbia"),
        ZA("South Africa"),
        ZM("Zambia"),
        ME("Montenegro"),
        ZW("Zimbabwe"),
        A1("Unknown"),
        A2("Satellite Provider"),
        O1("Other"),
        AX("Aland Islands"),
        GG("Guernsey"),
        IM("Isle of Man"),
        JE("Jersey"),
        BL("St. Barthelemy"),
        MF("Saint Martin");

        private final String name;

        Country(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static int getIndexByCode(String code) {
            Country[] values = Country.values();
            for (int i = 0; i < values.length; i++) {
                if (values[i].name().equalsIgnoreCase(code)) {
                    return i;
                }
            }
            return -1;
        }

        public static Country getById(int id) {
            Country[] values = Country.values();

            if (id < 0 || id >= values.length) {
                return Country.XX; // or null
            }

            return values[id];
        }
    }

}
