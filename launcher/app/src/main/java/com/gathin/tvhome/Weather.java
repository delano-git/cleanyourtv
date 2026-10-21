package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Current weather and a three-day forecast from Open-Meteo (no API key). The place comes from config.xml
 * (weather_lat / weather_lon / weather_city) or, when those are empty, from the TV's public IP address
 * (ipwho.is); either way the result is cached so a restart or a lost connection still shows the last reading.
 */
final class Weather {
    static final long MAX_AGE_MS = 30 * 60 * 1000L;
    private static final String PREFS = "weather";

    static final class Day {
        String date;       // yyyy-MM-dd
        int code;
        int high, low;
    }

    String city = "";
    int temp;
    int code;
    boolean isDay = true;
    boolean fahrenheit;
    long fetchedAt;
    final Day[] days = new Day[3];

    // ---- cache -----------------------------------------------------------------------------------

    static Weather cached(Context ctx) {
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", null);
        if (raw == null) return null;
        try {
            return parse(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private static Weather parse(JSONObject o) throws Exception {
        Weather w = new Weather();
        w.city = o.getString("city");
        w.temp = o.getInt("temp");
        w.code = o.getInt("code");
        w.isDay = o.getBoolean("day");
        w.fahrenheit = o.getBoolean("f");
        w.fetchedAt = o.getLong("at");
        JSONArray a = o.getJSONArray("days");
        for (int i = 0; i < 3; i++) {
            JSONObject d = a.getJSONObject(i);
            Day day = new Day();
            day.date = d.getString("date");
            day.code = d.getInt("code");
            day.high = d.getInt("hi");
            day.low = d.getInt("lo");
            w.days[i] = day;
        }
        return w;
    }

    private JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("city", city).put("temp", temp).put("code", code).put("day", isDay).put("f", fahrenheit).put("at", fetchedAt);
        JSONArray a = new JSONArray();
        for (Day d : days) a.put(new JSONObject().put("date", d.date).put("code", d.code).put("hi", d.high).put("lo", d.low));
        o.put("days", a);
        return o;
    }

    // ---- network (call off the main thread) ---------------------------------------------------------

    /** Fetches fresh data and caches it; null when the network or the services fail. */
    static Weather fetch(Context ctx, double cfgLat, double cfgLon, String cfgCity, boolean fahrenheit) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            double lat = cfgLat, lon = cfgLon;
            String city = cfgCity;
            boolean configured = !Double.isNaN(cfgLat) && !Double.isNaN(cfgLon);
            if (!configured) {
                if (sp.contains("ip_lat")) {   // the IP place is looked up once per day
                    lat = Double.longBitsToDouble(sp.getLong("ip_lat", 0));
                    lon = Double.longBitsToDouble(sp.getLong("ip_lon", 0));
                    city = sp.getString("ip_city", "");
                }
                if (!sp.contains("ip_lat") || System.currentTimeMillis() - sp.getLong("ip_at", 0) > 24 * 3600 * 1000L) {
                    try {
                        JSONObject g = new JSONObject(get("https://ipwho.is/?fields=success,city,latitude,longitude"));
                        if (g.optBoolean("success", false)) {
                            lat = g.getDouble("latitude");
                            lon = g.getDouble("longitude");
                            city = g.optString("city", "");
                            sp.edit().putLong("ip_lat", Double.doubleToLongBits(lat)).putLong("ip_lon", Double.doubleToLongBits(lon))
                                    .putString("ip_city", city).putLong("ip_at", System.currentTimeMillis()).apply();
                        }
                    } catch (Exception ignored) {
                        // keep the previous IP place, if there is one
                    }
                }
                if (!sp.contains("ip_lat") && Double.isNaN(lat)) return null;
            }
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                            + "&forecast_days=4&timezone=auto&temperature_unit=%s",
                    lat, lon, fahrenheit ? "fahrenheit" : "celsius");
            JSONObject r = new JSONObject(get(url));
            JSONObject cur = r.getJSONObject("current");
            JSONObject daily = r.getJSONObject("daily");
            Weather w = new Weather();
            w.city = city == null ? "" : city;
            w.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            w.code = cur.getInt("weather_code");
            w.isDay = cur.getInt("is_day") == 1;
            w.fahrenheit = fahrenheit;
            w.fetchedAt = System.currentTimeMillis();
            JSONArray t = daily.getJSONArray("time"), c = daily.getJSONArray("weather_code");
            JSONArray hi = daily.getJSONArray("temperature_2m_max"), lo = daily.getJSONArray("temperature_2m_min");
            for (int i = 0; i < 3; i++) {   // index 0 is today
                Day d = new Day();
                d.date = t.getString(i + 1);
                d.code = c.getInt(i + 1);
                d.high = (int) Math.round(hi.getDouble(i + 1));
                d.low = (int) Math.round(lo.getDouble(i + 1));
                w.days[i] = d;
            }
            sp.edit().putString("data", w.toJson().toString()).apply();
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---- icon ------------------------------------------------------------------------------------------

    /** Flat white line-art icon for a WMO weather code, drawn in code so the launcher ships no extra images. */
    static final class Icon extends Drawable {
        private static final int CLEAR = 0, PARTLY = 1, CLOUD = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;
        private final int kind;
        private final boolean day;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        Icon(int code, boolean day) {
            this.day = day;
            if (code == 0) kind = CLEAR;
            else if (code <= 2) kind = PARTLY;
            else if (code == 3) kind = CLOUD;
            else if (code == 45 || code == 48) kind = FOG;
            else if (code >= 71 && code <= 77 || code == 85 || code == 86) kind = SNOW;
            else if (code >= 95) kind = STORM;
            else kind = RAIN;   // 51-67 drizzle/rain, 80-82 showers
        }

        @Override
        public void draw(Canvas c) {
            float s = Math.min(getBounds().width(), getBounds().height());
            c.save();
            c.translate(getBounds().left, getBounds().top);
            float u = s / 48f;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.6f * u);
            p.setColor(0xFFFFFFFF);
            switch (kind) {
                case CLEAR:
                    sunOrMoon(c, 24 * u, 24 * u, 9 * u, u);
                    break;
                case PARTLY:
                    sunOrMoon(c, 31 * u, 17 * u, 7 * u, u * 0.8f);
                    cloud(c, u, 0, 4, true);
                    break;
                case CLOUD:
                    cloud(c, u, 0, 0, false);
                    break;
                case FOG:
                    cloud(c, u, 0, -6, false);
                    for (int i = 0; i < 2; i++) c.drawLine(10 * u, (33 + i * 6) * u, 38 * u, (33 + i * 6) * u, p);
                    break;
                case RAIN:
                    cloud(c, u, 0, -5, false);
                    for (int i = 0; i < 3; i++) c.drawLine((17 + i * 7) * u, 34 * u, (14 + i * 7) * u, 42 * u, p);
                    break;
                case SNOW:
                    cloud(c, u, 0, -5, false);
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 3; i++) c.drawCircle((16 + i * 8) * u, (37 + (i % 2) * 4) * u, 1.9f * u, p);
                    break;
                default:   // STORM
                    cloud(c, u, 0, -5, false);
                    path.reset();
                    path.moveTo(26 * u, 31 * u);
                    path.lineTo(20 * u, 39 * u);
                    path.lineTo(26 * u, 39 * u);
                    path.lineTo(22 * u, 46 * u);
                    c.drawPath(path, p);
                    break;
            }
            c.restore();
        }

        private void sunOrMoon(Canvas c, float cx, float cy, float rad, float u) {
            if (day) {
                p.setColor(0xFFFFD25A);
                c.drawCircle(cx, cy, rad, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    c.drawLine((float) (cx + Math.cos(a) * (rad + 4 * u)), (float) (cy + Math.sin(a) * (rad + 4 * u)),
                            (float) (cx + Math.cos(a) * (rad + 8 * u)), (float) (cy + Math.sin(a) * (rad + 8 * u)), p);
                }
            } else {
                p.setColor(0xFFD9E2FF);
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                path.reset();
                path.arcTo(r, 60, 250);   // crescent: outer arc, then back along a smaller circle
                float k = rad * 0.55f;
                r.set(cx - rad + k, cy - rad + k * 0.2f, cx + rad + k * 0.2f, cy + rad - k * 0.2f);
                path.arcTo(r, 300, -250, false);
                path.close();
                c.drawPath(path, p);
            }
            p.setColor(0xFFFFFFFF);
        }

        /** Cloud outline from three arcs; dy moves it, clip hides the sun behind a partly cloudy one. */
        private void cloud(Canvas c, float u, float dx, float dy, boolean fillBehind) {
            path.reset();
            float x = dx * u, y = dy * u;
            path.moveTo(x + 13 * u, y + 36 * u);
            r.set(x + 5 * u, y + 24 * u, x + 19 * u, y + 38 * u);
            path.arcTo(r, 90, 180);
            r.set(x + 11 * u, y + 14 * u, x + 29 * u, y + 32 * u);
            path.arcTo(r, 180, 160);
            r.set(x + 24 * u, y + 20 * u, x + 40 * u, y + 36 * u);
            path.arcTo(r, 250, 200);
            path.lineTo(x + 13 * u, y + 36 * u);
            path.close();
            if (fillBehind) {
                Paint.Style st = p.getStyle();
                int col = p.getColor();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF0A1A2A);   // backdrop tint, so the sun does not show through
                c.drawPath(path, p);
                p.setStyle(st);
                p.setColor(col);
            }
            c.drawPath(path, p);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
