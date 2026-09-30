package com.formulatv.player;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SourceClient {
    static List<Channel> loadM3u(String address) throws Exception {
        return M3uParser.parse(download(address));
    }
    static List<Channel> loadXtream(String base, String user, String pass) throws Exception {
        String root = base.replaceAll("/+$", "");
        String auth = "username=" + enc(user) + "&password=" + enc(pass);
        JSONArray categories = new JSONArray(download(root + "/player_api.php?" + auth + "&action=get_live_categories"));
        Map<String,String> names = new HashMap<>();
        for (int i=0;i<categories.length();i++) {
            JSONObject o = categories.optJSONObject(i);
            if (o != null) names.put(o.optString("category_id"), o.optString("category_name", "Other"));
        }
        JSONArray streams = new JSONArray(download(root + "/player_api.php?" + auth + "&action=get_live_streams"));
        List<Channel> channels = new ArrayList<>();
        for (int i=0;i<streams.length();i++) {
            JSONObject o = streams.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("stream_id");
            if (!id.matches("[0-9]+")) continue;
            String ext = o.optString("container_extension", "ts");
            if (!ext.matches("[A-Za-z0-9]{1,5}")) ext = "ts";
            String streamUrl = root + "/live/" + enc(user) + "/" + enc(pass) + "/" + id + "." + ext;
            channels.add(new Channel(o.optString("name", "Untitled"),
                names.getOrDefault(o.optString("category_id"), "Other"), streamUrl,
                o.optString("stream_icon")));
        }
        return channels;
    }
    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
    private static String download(String address) throws Exception {
        URL url = new URL(address);
        if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https"))
            throw new IllegalArgumentException("Use an http or https source URL.");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "FormulaTV/0.1");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Source returned HTTP " + code);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 16_000_000) throw new IllegalStateException("Source list is too large.");
                    bytes.write(buffer, 0, n);
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }
}
