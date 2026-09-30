package com.formulatv.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF120C20, CARD = 0xFF24163B, ACCENT = 0xFF8E60E8;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Channel> channels = new ArrayList<>();
    private LinearLayout page, results;
    private EditText source, user, pass, search;
    private String mode = "m3u", category = "All", query = "";
    private ExoPlayer player;
    private boolean playing;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        mode = getPreferences(0).getString("mode", "m3u");
        showSetup();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String value, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(Color.WHITE); t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }
    private void page(String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(20), dp(22), dp(25));
        scroll.addView(page); setContentView(scroll);
        TextView title = label("FORMULA  TV", 26, true); title.setTextColor(0xFFC9ADFF);
        page.addView(title);
        TextView sub = label(subtitle, 14, false); sub.setTextColor(0xFFB5A6CB);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(20)); page.addView(sub, p);
    }
    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setText(value); e.setHint(hint);
        e.setTextColor(Color.WHITE); e.setHintTextColor(0xFFA899BA);
        e.setTextSize(17); e.setInputType(type); e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(background(CARD, 12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, 0, 0, dp(12)); page.addView(e, p);
        return e;
    }
    private Button button(String text, Runnable action, LinearLayout parent) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(16); b.setBackground(background(CARD, 12));
        b.setOnFocusChangeListener((v, focus) -> {
            b.setBackground(background(focus ? ACCENT : CARD, 12));
            b.setScaleX(focus ? 1.02f : 1f); b.setScaleY(focus ? 1.02f : 1f);
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, 0, 0, dp(10)); parent.addView(b, p);
        return b;
    }
    private void note(String value, LinearLayout parent) {
        TextView t = label(value, 14, false); t.setTextColor(0xFFB5A6CB);
        t.setPadding(0, dp(7), 0, dp(10)); parent.addView(t);
    }
    private void showSetup() {
        playing = false;
        page("Add a playlist you are authorized to use");
        note("Source type", page);
        button("M3U playlist" + (mode.equals("m3u") ? "  ✓" : ""), () -> { mode = "m3u"; showSetup(); }, page);
        button("Xtream login" + (mode.equals("xtream") ? "  ✓" : ""), () -> { mode = "xtream"; showSetup(); }, page);
        String saved = getPreferences(0).getString("source", "");
        source = field(mode.equals("m3u") ? "Playlist URL (https://…)" : "Server URL (https://…)",
            saved, android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (mode.equals("xtream")) {
            user = field("Username", getPreferences(0).getString("user", ""), 1);
            pass = field("Password", getPreferences(0).getString("pass", ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        button("Connect", this::connect, page);
        note("Formula TV is a media player. It includes no channels or subscriptions. Use only content you are authorized to access.", page);
        source.requestFocus();
    }
    private void connect() {
        String address = source.getText().toString().trim();
        String username = mode.equals("xtream") ? user.getText().toString().trim() : "";
        String password = mode.equals("xtream") ? pass.getText().toString() : "";
        if (!address.matches("(?i)^https?://.+")) { note("Enter a full http:// or https:// URL.", page); return; }
        if (mode.equals("xtream") && (username.isEmpty() || password.isEmpty())) {
            note("Enter your username and password.", page); return;
        }
        getPreferences(0).edit().putString("mode", mode).putString("source", address)
            .putString("user", username).putString("pass", password).apply();
        page("Connecting…"); note("Loading channels from your source", page);
        worker.execute(() -> {
            try {
                List<Channel> loaded = mode.equals("m3u") ? SourceClient.loadM3u(address)
                    : SourceClient.loadXtream(address, username, password);
                runOnUiThread(() -> {
                    channels.clear(); channels.addAll(loaded); category = "All"; query = "";
                    if (channels.isEmpty()) { page("No playable live channels found");
                        button("Edit source", this::showSetup, page); }
                    else showChannels();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    page("Could not load channels");
                    note(error.getMessage() == null ? "Check your connection and source." : error.getMessage(), page);
                    button("Edit source and retry", this::showSetup, page);
                });
            }
        });
    }
    private void showChannels() {
        playing = false;
        page(channels.size() + " live channels");
        button("Source settings", this::showSetup, page);
        search = field("Search channels", query, 1);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString(); renderResults();
            }
            public void afterTextChanged(android.text.Editable e) { }
        });
        LinearLayout categoryList = new LinearLayout(this);
        categoryList.setOrientation(LinearLayout.VERTICAL); page.addView(categoryList);
        Set<String> groups = new LinkedHashSet<>(); groups.add("All");
        for (Channel c : channels) groups.add(c.group);
        for (String group : groups) {
            button((group.equals(category) ? "●  " : "○  ") + group, () -> {
                category = group; showChannels();
            }, categoryList);
        }
        note("CHANNELS", page);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results); renderResults();
        if (!channels.isEmpty()) categoryList.getChildAt(0).requestFocus();
    }
    private void renderResults() {
        if (results == null) return;
        results.removeAllViews(); int shown = 0;
        for (Channel c : channels) {
            if (!category.equals("All") && !category.equals(c.group)) continue;
            if (!c.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
            button(c.name, () -> play(c), results); shown++;
            if (shown >= 300) break;
        }
        if (shown == 0) note("No channels match.", results);
        else if (shown >= 300) note("Showing first 300. Narrow your search to see more.", results);
    }
    private void play(Channel c) {
        playing = true;
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        LinearLayout container = new LinearLayout(this); container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.BLACK);
        TextView title = label(c.name, 18, true); title.setPadding(dp(16), dp(10), dp(16), dp(10));
        container.addView(title);
        PlayerView view = new PlayerView(this);
        view.setUseController(true);
        container.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView error = label("", 15, false); error.setGravity(Gravity.CENTER);
        container.addView(error);
        setContentView(container);
        player = new ExoPlayer.Builder(this).build(); view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                error.setText("Playback failed. Check the stream or try another channel.");
            }
        });
        player.setMediaItem(MediaItem.fromUri(c.url)); player.prepare(); player.play();
        view.requestFocus();
    }
    @Override public void onBackPressed() {
        if (playing) {
            releasePlayer();
            getWindow().getDecorView().setSystemUiVisibility(0);
            showChannels();
        } else super.onBackPressed();
    }
    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
        playing = false;
    }
    @Override protected void onStop() { super.onStop(); releasePlayer(); }
    @Override protected void onDestroy() { worker.shutdownNow(); releasePlayer(); super.onDestroy(); }
}
