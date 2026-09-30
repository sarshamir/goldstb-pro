package com.formulatv.player;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class M3uParser {
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    static List<Channel> parse(String data) {
        List<Channel> channels = new ArrayList<>();
        String name = "", group = "Other", logo = "";
        boolean pending = false;
        for (String raw : data.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.lastIndexOf(',');
                name = comma < 0 ? "Untitled" : line.substring(comma + 1).trim();
                group = "Other"; logo = "";
                Matcher m = ATTRIBUTE.matcher(comma < 0 ? line : line.substring(0, comma));
                while (m.find()) {
                    if (m.group(1).equalsIgnoreCase("group-title")) group = m.group(2);
                    if (m.group(1).equalsIgnoreCase("tvg-logo")) logo = m.group(2);
                }
                pending = true;
            } else if (pending && !line.isEmpty() && !line.startsWith("#")) {
                try {
                    URL url = new URL(line);
                    if (url.getProtocol().equals("http") || url.getProtocol().equals("https"))
                        channels.add(new Channel(name.isEmpty() ? "Untitled" : name,
                            group.isEmpty() ? "Other" : group, line, logo));
                } catch (Exception ignored) { }
                pending = false;
            }
        }
        return channels;
    }
}
