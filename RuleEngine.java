package com.flashsignal.mobile;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class RuleEngine {
    private RuleEngine() {}

    static final int[] RULE1_MINUTES = {15, 20, 25, 30};

    private static final String[][] WATCHED = {
            {"lask", "lask linz"},
            {"salzburg", "rb salzburg", "red bull salzburg", "fc salzburg"},
            {"sturm graz", "sk sturm graz"},
            {"royale union sg", "royale union saint gilloise", "union saint gilloise", "union sg"},
            {"club brugge kv", "club brugge", "brugge"},
            {"genk", "krc genk"},
            {"hajduk split", "hajduk"},
            {"din zagreb", "dinamo zagreb", "gnk dinamo zagreb"},
            {"varazdin", "varaždin"},
            {"fc copenhagen", "copenhagen", "fc kopenhagen", "kobenhavn", "københavn"},
            {"arsenal"}, {"manchester city", "man city"},
            {"brighton", "brighton hove albion"},
            {"manchester utd", "manchester united", "man utd"},
            {"aston villa"}, {"southampton"},
            {"west ham", "west ham united"},
            {"wolves", "wolverhampton", "wolverhampton wanderers"},
            {"stoke", "stoke city"},
            {"monaco", "as monaco"}, {"rennes", "stade rennais"},
            {"lyon", "olympique lyon"},
            {"psg", "paris sg", "paris saint germain", "paris saint-germain"},
            {"marseille", "olympique marseille"},
            {"dortmund", "borussia dortmund"},
            {"bayern munich", "bayern munchen", "bayern münchen"},
            {"bayer leverkusen", "leverkusen"},
            {"eintracht frankfurt", "frankfurt"},
            {"stuttgart", "vfb stuttgart"},
            {"freiburg", "sc freiburg"},
            {"st etienne", "saint etienne", "saint-etienne"},
            {"ferencvaros", "ferencváros", "ferencvarosi"},
            {"puskas academy", "puskas akademia", "puskás akadémia"},
            {"gyor", "győr", "eto gyor", "eto fc gyor"},
            {"as roma", "roma"}, {"inter", "inter milan", "internazionale"},
            {"como", "como 1907"}, {"ac milan", "milan"}, {"juventus"}, {"napoli"},
            {"ajax", "ajax amsterdam"}, {"psv", "psv eindhoven"}, {"az alkmaar"},
            {"molde", "molde fk"}, {"viking", "viking fk"},
            {"bodo glimt", "bodø glimt", "bodo/glimt", "fk bodo glimt"},
            {"brann", "sk brann"},
            {"legia", "legia warsaw", "legia warszawa"},
            {"lech poznan", "lech poznań"},
            {"fc porto", "porto"}, {"benfica", "sl benfica"},
            {"sporting cp", "sporting lisbon", "sporting"},
            {"braga", "sc braga"}, {"estrela", "estrela amadora", "estrela da amadora"},
            {"univ craiova", "universitatea craiova", "u craiova"},
            {"fcsb", "steaua bucharest"},
            {"u cluj", "universitatea cluj", "universitatea cluj-napoca"},
            {"rangers", "rangers fc"}, {"celtic", "celtic fc"},
            {"hearts", "heart of midlothian"},
            {"barcelona", "fc barcelona"}, {"real madrid"},
            {"betis", "real betis"}, {"atl madrid", "atletico madrid", "atlético madrid"},
            {"alaves", "deportivo alaves", "alavés"}, {"rayo vallecano", "rayo"},
            {"ath bilbao", "athletic bilbao", "athletic club"},
            {"real sociedad"}, {"villarreal"}, {"osasuna"},
            {"vaduz", "fc vaduz"},
            {"galatasaray"}, {"besiktas", "beşiktaş"}, {"amedspor", "amed sk", "amed sport"},
            {"fenerbahce", "fenerbahçe"},
            {"basaksehir", "başakşehir", "istanbul basaksehir", "istanbul başakşehir"},
            {"goztepe", "göztepe"},
            {"shakhtar donetsk", "shakthar donetsk", "shakhtar"},
            {"flamengo", "cr flamengo"}, {"internacional", "sc internacional"},
            {"vasco", "vasco da gama", "cr vasco da gama"}
    };

    private static final String[][] LEAGUES = {
            {"germany", "bundesliga"}, {"germany", "2 bundesliga"},
            {"austria", "bundesliga"}, {"belgium", "jupiler pro league"},
            {"belgium", "first division a"}, {"brazil", "serie a"},
            {"czech republic", "chance liga"}, {"czechia", "chance liga"},
            {"denmark", "superliga"}, {"france", "ligue 1"}, {"france", "ligue 2"},
            {"croatia", "hnl"}, {"england", "premier league"},
            {"england", "championship"}, {"england", "national league"},
            {"scotland", "premiership"}, {"spain", "laliga"}, {"spain", "la liga"},
            {"italy", "serie a"}, {"japan", "j1 league"}, {"japan", "j2 league"},
            {"hungary", "nb i"}, {"mexico", "liga mx"}, {"norway", "eliteserien"},
            {"portugal", "liga portugal"}, {"romania", "superliga"},
            {"turkey", "super lig"}, {"turkiye", "super lig"}, {"turkiye", "super lig"}
    };

    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return n;
    }

    static boolean isWatchedTeam(String name) {
        String n = norm(name);
        if (n.isEmpty()) return false;
        for (String[] group : WATCHED) {
            for (String alias : group) {
                if (n.equals(norm(alias))) return true;
            }
        }
        return false;
    }

    static boolean isWatchedMatch(Models.MatchInfo m) {
        return m != null && (isWatchedTeam(m.home) || isWatchedTeam(m.away));
    }

    static boolean watchedTeamLosing(Models.MatchInfo m) {
        if (m == null || m.homeScore == m.awayScore) return false;
        return (isWatchedTeam(m.home) && m.homeScore < m.awayScore)
                || (isWatchedTeam(m.away) && m.awayScore < m.homeScore);
    }

    static boolean inRule2League(String league) {
        String n = norm(league);
        if (n.isEmpty()) return false;
        for (String[] parts : LEAGUES) {
            boolean all = true;
            for (String p : parts) {
                if (!n.contains(norm(p))) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    static boolean isRule1Minute(int minute) {
        for (int m : RULE1_MINUTES) if (m == minute) return true;
        return false;
    }

    static String prettyRule(String rule) {
        switch (rule) {
            case "ALL_15_30": return "Tüm maçlar · 15–30 dk · 0-0";
            case "ALL_60_82": return "Tüm maçlar · 60–82 dk · 0-0";
            case "DRAW_15_30": return "15–30 dk · Berabere";
            case "LOSING_15_30": return "15–30 dk · Takipli takım geride";
            case "DRAW_60_82": return "60–82 dk · Berabere";
            case "LOSING_60_82": return "60–82 dk · Takipli takım geride";
            case "CHECKPOINT": return "Takipli takım · 0-0 kontrolü";
            case "RULE2": return "Kural 2 · ev sahibi 0-0 · ≥2 İS";
            case "SHOT_BURST": return "Son 5 dk · +4 İsabetli şut";
            default: return rule;
        }
    }
}
