package com.flashsignal.mobile;

import org.json.JSONException;
import org.json.JSONObject;

final class Models {
    private Models() {}

    static final class MatchInfo {
        String id = "";
        String home = "";
        String away = "";
        String league = "";
        String url = "";
        int minute = 0;
        int homeScore = 0;
        int awayScore = 0;
        int homeRed = 0;
        int awayRed = 0;
        boolean live = true;
        long updatedAt = System.currentTimeMillis();

        static MatchInfo fromJson(JSONObject o) {
            MatchInfo m = new MatchInfo();
            m.id = o.optString("id", "");
            m.home = o.optString("home", "");
            m.away = o.optString("away", "");
            m.league = o.optString("league", "");
            m.url = o.optString("url", "");
            m.minute = o.optInt("minute", 0);
            m.homeScore = o.optInt("homeScore", 0);
            m.awayScore = o.optInt("awayScore", 0);
            m.homeRed = o.optInt("homeRed", 0);
            m.awayRed = o.optInt("awayRed", 0);
            m.live = o.optBoolean("live", true);
            m.updatedAt = System.currentTimeMillis();
            return m;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("home", home)
                    .put("away", away)
                    .put("league", league)
                    .put("url", url)
                    .put("minute", minute)
                    .put("homeScore", homeScore)
                    .put("awayScore", awayScore)
                    .put("homeRed", homeRed)
                    .put("awayRed", awayRed)
                    .put("live", live)
                    .put("updatedAt", updatedAt);
        }
    }

    static final class StatInfo {
        String matchId = "";
        Integer homeSot = null;
        Integer awaySot = null;
        Integer homeBig = null;
        Integer awayBig = null;
        String status = "queued";
        int attempts = 0;
        long updatedAt = 0L;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject()
                    .put("matchId", matchId)
                    .put("status", status)
                    .put("attempts", attempts)
                    .put("updatedAt", updatedAt);
            o.put("homeSot", homeSot == null ? JSONObject.NULL : homeSot);
            o.put("awaySot", awaySot == null ? JSONObject.NULL : awaySot);
            o.put("homeBig", homeBig == null ? JSONObject.NULL : homeBig);
            o.put("awayBig", awayBig == null ? JSONObject.NULL : awayBig);
            return o;
        }
    }

    static final class Signal {
        String key = "";
        String rule = "";
        String matchId = "";
        long createdAt = System.currentTimeMillis();
        Integer threshold = null;
        int delta5m = 0;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject()
                    .put("key", key)
                    .put("rule", rule)
                    .put("matchId", matchId)
                    .put("createdAt", createdAt)
                    .put("delta5m", delta5m);
            o.put("threshold", threshold == null ? JSONObject.NULL : threshold);
            return o;
        }
    }

    static final class ShotPoint {
        final long at;
        final int total;
        ShotPoint(long at, int total) {
            this.at = at;
            this.total = total;
        }
    }
}
