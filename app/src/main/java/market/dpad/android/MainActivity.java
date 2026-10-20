package market.dpad.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String MARKET_RELEASE_API = "https://api.github.com/repos/jbriones95/DPADmarket/releases/latest";
    private static final String REMOTE_CATALOG_URL = "https://raw.githubusercontent.com/jbriones95/DPADmarket/main/catalog.remote.json";
    private int BG, PANEL, PANEL_HI, RULE, FG, MUTED, ACCENT, POSITIVE, WARNING;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<AppEntry> catalog = new ArrayList<>();
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private LinearLayout root;
    private String activeCategory = "All";
    private String query = "";
    private boolean showingDownloaded;
    private AppEntry selected;
    private boolean showingSettings;
    private boolean darkTheme;
    private boolean checkingMarketUpdate;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        darkTheme = getPreferences(MODE_PRIVATE).getBoolean("dark_theme", false);
        applyThemeColors();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        loadCatalog();
        showHome();
        refreshRemoteCatalog(false);
    }

    private void applyThemeColors() {
        if (darkTheme) {
            BG = 0xff101010; PANEL = 0xff1c1c1c; PANEL_HI = 0xff303030;
            RULE = 0xff454545; FG = 0xfff2f2f2; MUTED = 0xffb0b0b0;
            ACCENT = 0xffeeeeee; POSITIVE = 0xffb8d8b8; WARNING = 0xffffc27a;
        } else {
            BG = 0xffffffff; PANEL = 0xfff2f2f2; PANEL_HI = 0xffe5e5e5;
            RULE = 0xffd0d0d0; FG = 0xff171717; MUTED = 0xff626262;
            ACCENT = 0xff303030; POSITIVE = 0xff276238; WARNING = 0xff9a4b00;
        }
    }

    private void loadCatalog() {
        try (InputStream in = getAssets().open("catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            JSONObject source = new JSONObject(out.toString("UTF-8"));
            String cached = getPreferences(MODE_PRIVATE).getString("remote_catalog", "");
            if (!cached.isEmpty()) {
                try {
                    JSONObject cachedSource = new JSONObject(cached);
                    if (validCatalog(cachedSource)) source = cachedSource;
                } catch (Exception ignored) { }
            }
            addCatalogApps(source);
            addUserSources();
        } catch (Exception e) { Toast.makeText(this, "Could not read the app catalog", Toast.LENGTH_LONG).show(); }
    }

    private void addCatalogApps(JSONObject source) throws Exception {
        JSONArray apps = source.getJSONArray("apps");
        for (int i = 0; i < apps.length(); i++) {
            AppEntry app = AppEntry.from(apps.getJSONObject(i)); app.unlisted = false; catalog.add(app);
        }
    }

    private void addUserSources() throws Exception {
        JSONArray personal = new JSONArray(getPreferences(MODE_PRIVATE).getString("user_sources", "[]"));
        for (int i = 0; i < personal.length(); i++) catalog.add(AppEntry.from(personal.getJSONObject(i)));
    }

    private boolean validCatalog(JSONObject source) {
        if (source.optInt("schemaVersion", -1) != 1) return false;
        JSONArray apps = source.optJSONArray("apps"); if (apps == null) return false;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i); if (app == null) return false;
            for (String field : new String[]{"id", "packageId", "name", "developer", "repository", "releaseApi", "category", "summary", "license", "platform", "inputSupport"}) {
                if (app.optString(field, "").trim().isEmpty()) return false;
            }
            if (!ids.add(app.optString("id")) || !app.optString("repository").startsWith("https://github.com/") ||
                    !app.optString("releaseApi").startsWith("https://api.github.com/repos/")) return false;
        }
        return true;
    }

    private void refreshRemoteCatalog(boolean notify) {
        io.execute(() -> {
            try {
                JSONObject remote = getJson(REMOTE_CATALOG_URL);
                if (!validCatalog(remote)) throw new Exception("Remote catalog failed validation");
                getPreferences(MODE_PRIVATE).edit().putString("remote_catalog", remote.toString()).apply();
                runOnUiThread(() -> {
                    for (int i = catalog.size() - 1; i >= 0; i--) if (!catalog.get(i).unlisted) catalog.remove(i);
                    try { addCatalogApps(remote); } catch (Exception ignored) { return; }
                    if (notify) Toast.makeText(this, "App catalog refreshed.", Toast.LENGTH_SHORT).show();
                    if (showingSettings) showSettings(); else if (selected == null) showHome();
                });
            } catch (Exception e) {
                if (notify) runOnUiThread(() -> Toast.makeText(this, "Could not refresh app catalog.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void base(String title) {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout appBar = new LinearLayout(this); appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setPadding(dp(20), dp(15), dp(16), dp(8)); appBar.setBackgroundColor(BG);
        LinearLayout titleBlock = new LinearLayout(this); titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(text("DPAD Market", 24, FG, false));
        titleBlock.addView(text(title, 13, MUTED, false));
        appBar.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("Settings", FG, PANEL_HI); settings.setTextSize(11); settings.setMinHeight(dp(40));
        appBar.addView(settings); settings.setOnClickListener(v -> showSettings());
        root.addView(appBar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if ("Apps".equals(title) || "Downloaded".equals(title)) addBottomBar();
        setContentView(root); currentContent = content;
    }
    private LinearLayout currentContent;

    private void addBottomBar() {
        View rule = new View(this); rule.setBackgroundColor(RULE);
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER); bar.setPadding(dp(12), dp(7), dp(12), dp(9));
        bar.setBackgroundColor(PANEL);
        LinearLayout apps = navItem("Apps", !showingDownloaded, false, () -> {
            if (showingDownloaded) { showingDownloaded = false; showHome(); }
        });
        LinearLayout downloaded = navItem("Downloaded", showingDownloaded, true, () -> {
            if (!showingDownloaded) { showingDownloaded = true; showHome(); }
        });
        bar.addView(apps, new LinearLayout.LayoutParams(0, dp(62), 1));
        LinearLayout.LayoutParams downloadedParams = new LinearLayout.LayoutParams(0, dp(62), 1);
        downloadedParams.leftMargin = dp(8); bar.addView(downloaded, downloadedParams);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout navItem(String label, boolean selected, boolean downloaded, Runnable action) {
        int color = selected ? POSITIVE : MUTED;
        LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        item.setFocusable(true); item.setClickable(true); item.setPadding(dp(8), dp(4), dp(8), dp(4));
        item.setBackground(selectable(selected ? PANEL_HI : PANEL));
        NavIconView icon = new NavIconView(downloaded, color); icon.setContentDescription(label); item.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView title = text(label, 11, color, selected); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(2), 0, 0); item.addView(title);
        item.setOnClickListener(v -> action.run()); item.setOnKeyListener((v, key, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
                action.run(); return true;
            }
            return false;
        });
        return item;
    }

    private class NavIconView extends View {
        private final boolean downloaded;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        NavIconView(boolean downloaded, int color) {
            super(MainActivity.this); this.downloaded = downloaded; paint.setColor(color); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas canvas) {
            float scale = Math.min(getWidth(), getHeight()) / 24f; canvas.save(); canvas.scale(scale, scale);
            if (downloaded) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.1f);
                canvas.drawRoundRect(4, 14, 20, 20, 2, 2, paint);
                canvas.drawLine(12, 4, 12, 15, paint); canvas.drawLine(8, 11, 12, 15, paint); canvas.drawLine(16, 11, 12, 15, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(4, 4, 10.5f, 10.5f, 1.5f, 1.5f, paint);
                canvas.drawRoundRect(13.5f, 4, 20, 10.5f, 1.5f, 1.5f, paint);
                canvas.drawRoundRect(4, 13.5f, 10.5f, 20, 1.5f, 1.5f, paint);
                canvas.drawRoundRect(13.5f, 13.5f, 20, 20, 1.5f, 1.5f, paint);
            }
            canvas.restore();
        }
    }

    private void showHome() {
        selected = null;
        showingSettings = false;
        base(showingDownloaded ? "Downloaded" : "Apps");
        LinearLayout titleRow = new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCopy = new LinearLayout(this); titleCopy.setOrientation(LinearLayout.VERTICAL);
        titleCopy.addView(text(showingDownloaded ? "Apps installed from the catalog" : "Track and install apps", 14, MUTED, false));
        titleRow.addView(titleCopy, new LinearLayout.LayoutParams(0, -2, 1));
        Button addSource = button("Add source", FG, PANEL_HI); addSource.setTextSize(11);
        addSource.setMinHeight(dp(40)); titleRow.addView(addSource); addSource.setOnClickListener(v -> promptAddSource());
        currentContent.addView(titleRow, bottom(dp(18)));

        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(14); search.setTextColor(FG);
        search.setHintTextColor(MUTED); search.setHint("Search apps and developers"); search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(22), dp(4), dp(22), dp(4)); search.setBackground(rounded(PANEL, dp(30), 0));
        search.setText(query); currentContent.addView(search, bottom(dp(12)));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { query = s.toString(); refreshList(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        HorizontalScrollView filtersScroll = new HorizontalScrollView(this); filtersScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = new LinearLayout(this); filters.setOrientation(LinearLayout.HORIZONTAL);
        for (String category : new String[]{"All", "Media", "Audio", "Tools", "Maps", "Community"}) {
            Button chip = button(category, category.equals(activeCategory) ? BG : FG, category.equals(activeCategory) ? ACCENT : PANEL_HI);
            chip.setTextSize(12); chip.setMinHeight(0); chip.setMinimumHeight(0);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(42)); cp.setMargins(0, 0, dp(7), 0);
            filters.addView(chip, cp); chip.setOnClickListener(v -> { activeCategory = category; showHome(); });
        }
        filtersScroll.addView(filters); currentContent.addView(filtersScroll, bottom(dp(20)));
        resultsLabel = text((showingDownloaded ? "Downloaded apps" : "Recommended apps") + "  ·  " + visibleCount(), 11, MUTED, true);
        currentContent.addView(resultsLabel, bottom(dp(9)));
        listHost = new LinearLayout(this); listHost.setOrientation(LinearLayout.VERTICAL); currentContent.addView(listHost);
        refreshList();
    }

    private int visibleCount() {
        int count = 0;
        for (AppEntry app : catalog) {
            if (!showUnreviewedSources() && app.unlisted) continue;
            if (showingDownloaded && !isInstalled(app)) continue;
            if (!activeCategory.equals("All") && !app.category.equalsIgnoreCase(activeCategory)) continue;
            String haystack = (app.name + " " + app.developer + " " + app.summary + " " + app.category).toLowerCase(Locale.ROOT);
            if (haystack.contains(query.toLowerCase(Locale.ROOT))) count++;
        }
        return count;
    }
    private LinearLayout listHost;
    private TextView resultsLabel;

    private void refreshList() {
        if (listHost == null) return;
        if (resultsLabel != null) resultsLabel.setText((showingDownloaded ? "Downloaded apps" : (activeCategory.equals("All") ? "Recommended apps" : activeCategory)) + "  ·  " + visibleCount());
        listHost.removeAllViews();
        int count = 0;
        for (AppEntry app : catalog) {
            if (!showUnreviewedSources() && app.unlisted) continue;
            if (showingDownloaded && !isInstalled(app)) continue;
            if (!activeCategory.equals("All") && !app.category.equalsIgnoreCase(activeCategory)) continue;
            String haystack = (app.name + " " + app.developer + " " + app.summary + " " + app.category).toLowerCase(Locale.ROOT);
            if (!haystack.contains(query.toLowerCase(Locale.ROOT))) continue;
            listHost.addView(appRow(app)); count++;
        }
        if (count == 0) {
            String emptyMessage = showingDownloaded
                    ? (query.isEmpty() ? "No downloaded apps yet." : "No matching downloaded apps.")
                    : (query.isEmpty() ? "No apps in this category." : "No matching apps.");
            TextView empty = text(emptyMessage, 14, MUTED, false);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24)); listHost.addView(empty);
        }
        else { View first = listHost.getChildAt(0); first.post(first::requestFocus); }
    }

    private boolean showUnreviewedSources() {
        return getPreferences(MODE_PRIVATE).getBoolean("show_unreviewed_sources", true);
    }

    private void showSettings() {
        selected = null; showingSettings = true; base("Settings");
        addBack(currentContent, "Back to apps");

        sectionLabel(currentContent, "Appearance");
        settingRow("Dark theme", darkTheme ? "On · use a dark monochrome theme" : "Off · use a light monochrome theme", () -> {
            darkTheme = !darkTheme;
            getPreferences(MODE_PRIVATE).edit().putBoolean("dark_theme", darkTheme).apply();
            applyThemeColors();
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
            showSettings();
        });

        sectionLabel(currentContent, "Catalog");
        settingRow("Refresh app catalog", "Fetch the latest approved listings from GitHub", () -> refreshRemoteCatalog(true));
        settingRow("Show unreviewed sources", showUnreviewedSources() ? "On  ·  User-added GitHub sources appear in the catalog" : "Off  ·  Hide user-added GitHub sources", () -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("show_unreviewed_sources", !showUnreviewedSources()).apply();
            showSettings();
        });
        settingRow("Remove added sources", userSourceCount() == 0 ? "No user-added sources on this device" : userSourceCount() + " user-added source" + (userSourceCount() == 1 ? "" : "s") + "  ·  Select to remove all", () -> {
            if (userSourceCount() == 0) return;
            new AlertDialog.Builder(this)
                    .setTitle("Remove added sources?")
                    .setMessage("This removes only sources you added from the device. Bundled apps stay in DPAD Market.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> clearUserSources())
                    .show();
        });

        sectionLabel(currentContent, "Installation");
        settingRow("Install permissions", "Manage whether DPAD Market can hand APKs to Android", () -> {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Android manages this permission on install.", Toast.LENGTH_SHORT).show();
            }
        });
        settingRow("Check for DPAD Market updates", "Current version " + currentVersionName() + " · Check GitHub for the latest signed APK", this::checkForMarketUpdates);

        sectionLabel(currentContent, "About");
        addMeta("Version", currentVersionName());
        addMeta("Bundled catalog", catalog.size() - userSourceCount() + " curated apps");
        currentContent.addView(text("DPAD Market reads release metadata from GitHub and verifies published APK hashes before installation.", 13, MUTED, false), bottom(dp(12)));
    }

    private int userSourceCount() {
        int count = 0;
        for (AppEntry app : catalog) if (app.unlisted) count++;
        return count;
    }

    private void clearUserSources() {
        for (int i = catalog.size() - 1; i >= 0; i--) if (catalog.get(i).unlisted) catalog.remove(i);
        saveUserSources(); showHome(); Toast.makeText(this, "Added sources removed.", Toast.LENGTH_SHORT).show();
    }

    private void settingRow(String title, String summary, final Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setFocusable(true); row.setClickable(true);
        row.setBackground(selectable()); row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.addView(text(title, 16, FG, true));
        TextView detail = text(summary, 12, MUTED, false); detail.setPadding(0, dp(4), 0, 0); row.addView(detail);
        row.setOnClickListener(v -> action.run()); row.setOnKeyListener((v, key, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) { action.run(); return true; }
            return false;
        });
        currentContent.addView(row, bottom(dp(9)));
    }

    private View appRow(AppEntry app) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        row.setFocusable(true); row.setClickable(true); row.setBackground(selectable()); row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2); rowParams.bottomMargin = dp(10);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text(app.name.substring(0, 1).toUpperCase(Locale.ROOT), 19, ACCENT, true);
        mark.setGravity(Gravity.CENTER); mark.setBackground(rounded(PANEL_HI, dp(28), 0));
        ImageView icon = new ImageView(this); icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(rounded(PANEL_HI, dp(28), 0)); icon.setVisibility(View.GONE);
        android.widget.FrameLayout iconFrame = new android.widget.FrameLayout(this);
        iconFrame.addView(mark, new android.widget.FrameLayout.LayoutParams(dp(48), dp(48)));
        iconFrame.addView(icon, new android.widget.FrameLayout.LayoutParams(dp(48), dp(48)));
        top.addView(iconFrame, new LinearLayout.LayoutParams(dp(48), dp(48)));
        loadIcon(app, icon, mark);
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(12), 0, 0, 0);
        names.addView(text(app.name, 18, FG, false)); names.addView(text("By " + app.developer, 13, MUTED, false));
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView source = text(app.unlisted ? "Local" : "GitHub", 12, MUTED, false); source.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(source); row.addView(top);
        TextView summary = text(app.summary, 12, MUTED, false); summary.setMaxLines(2); summary.setPadding(dp(60), dp(8), 0, dp(5)); row.addView(summary);
        TextView status = text(app.unlisted ? "Unlisted source" : "D-pad: " + app.inputSupport, 10,
                app.unlisted ? WARNING : POSITIVE, true);
        status.setPadding(dp(60), 0, 0, 0); row.addView(status);
        row.setOnClickListener(v -> showDetail(app)); row.setOnKeyListener((v, key, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) { showDetail(app); return true; }
            return false;
        });
        row.setLayoutParams(rowParams); return row;
    }

    private void showDetail(AppEntry app) {
        selected = app; showingSettings = false; base("App details");
        addBack(currentContent, "Back to apps");
        LinearLayout identity = new LinearLayout(this); identity.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(app.name.substring(0, 1).toUpperCase(Locale.ROOT), 25, ACCENT, true);
        icon.setGravity(Gravity.CENTER); icon.setBackgroundColor(PANEL_HI);
        identity.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(14), 0, 0, 0);
        names.addView(text(app.name, 24, FG, true));
        names.addView(text(app.developer, 12, MUTED, false));
        identity.addView(names, new LinearLayout.LayoutParams(0, -2, 1)); currentContent.addView(identity, bottom(dp(18)));
        TextView category = text(app.category + "  ·  " + app.platform, 11, ACCENT, true);
        currentContent.addView(category, bottom(dp(9)));
        currentContent.addView(text(app.summary, 15, FG, false), bottom(dp(16)));
        boolean installed = isInstalled(app);
        currentContent.addView(text(installed ? "Installed on this device · updates are fetched from GitHub." : "Not installed · install the latest GitHub APK below.", 12, MUTED, false), bottom(dp(10)));
        Button install = button(installed ? "Update from GitHub" : "Install latest APK", BG, ACCENT);
        currentContent.addView(install, bottom(dp(10)));
        install.setOnClickListener(v -> installLatest(app, install));
        if (installed) {
            Button uninstall = button("Uninstall", FG, PANEL_HI);
            currentContent.addView(uninstall, bottom(dp(10)));
            uninstall.setOnClickListener(v -> uninstallApp(app));
        }
        TextView release = text("Fetching latest GitHub release…", 12, MUTED, false);
        release.setPadding(0, dp(5), 0, dp(17)); currentContent.addView(release);
        fetchLatest(app, release);
        sectionLabel(currentContent, "About this app");
        currentContent.addView(text(app.unlisted ? "Added from your GitHub sources. This listing has not been reviewed by DPAD Market.\n\n" + app.curatorNote : app.curatorNote, 13, MUTED, false), bottom(dp(18)));
        addMeta("D-pad support", app.unlisted ? "Unlisted · not reviewed" : app.inputSupport);
        addMeta("License", app.license);
        addMeta("Source repository", app.repository);
        TextView safety = text("GitHub source is not a security audit. Android confirms the install; a published GitHub SHA-256 is checked before handoff.", 11, MUTED, false);
        safety.setPadding(0, dp(15), 0, dp(12)); currentContent.addView(safety, bottom(dp(8)));
        Button source = button("View source repository", FG, PANEL_HI); currentContent.addView(source);
        source.setOnClickListener(v -> openUrl(app.repository));

        LinearLayout screenshots = new LinearLayout(this); screenshots.setOrientation(LinearLayout.VERTICAL);
        currentContent.addView(screenshots, bottom(dp(18)));
        loadScreenshots(app, screenshots);
    }

    private void loadScreenshots(AppEntry app, LinearLayout section) {
        section.addView(text("Screenshots", 10, ACCENT, true), bottom(dp(8)));
        TextView loading = text("Loading screenshots from the repository…", 12, MUTED, false);
        section.addView(loading, bottom(dp(8)));
        io.execute(() -> {
            List<Screenshot> screenshots = new ArrayList<>();
            try {
                String[] repositoryParts = repositoryParts(app.repository);
                JSONObject repositoryInfo = getJson("https://api.github.com/repos/" + repositoryParts[0] + "/" + repositoryParts[1]);
                String branch = repositoryInfo.optString("default_branch", "main");
                String readmeAddress = "https://api.github.com/repos/" + repositoryParts[0] + "/" + repositoryParts[1] + "/readme";
                JSONObject readme = getJson(readmeAddress);
                String content = new String(Base64.decode(readme.optString("content", "").replaceAll("\\s", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
                String rawReadme = "https://raw.githubusercontent.com/" + repositoryParts[0] + "/" + repositoryParts[1] + "/" + branch + "/README.md";
                screenshots.addAll(findScreenshots(content, rawReadme));
                if (screenshots.isEmpty()) screenshots.addAll(findRepositoryImages(repositoryParts, branch));
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (section.getParent() == null) return;
                section.removeAllViews();
                if (screenshots.isEmpty()) {
                    section.setVisibility(View.GONE);
                } else {
                    section.addView(text("Screenshots", 10, ACCENT, true), bottom(dp(8)));
                    HorizontalScrollView scroll = new HorizontalScrollView(this);
                    scroll.setHorizontalScrollBarEnabled(false);
                    LinearLayout images = new LinearLayout(this); images.setGravity(Gravity.CENTER_VERTICAL);
                    for (Screenshot screenshot : screenshots) addScreenshot(images, screenshot);
                    scroll.addView(images); section.addView(scroll, new LinearLayout.LayoutParams(-1, dp(260)));
                }
            });
        });
    }

    private List<Screenshot> findScreenshots(String readme, String rawReadme) {
        List<Screenshot> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        Pattern markdown = Pattern.compile("!\\[([^]]*)\\]\\(([^)\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher markdownMatcher = markdown.matcher(readme);
        while (markdownMatcher.find() && result.size() < 12) addScreenshotReference(result, seen, markdownMatcher.group(2), markdownMatcher.group(1), rawReadme);
        Pattern html = Pattern.compile("<img[^>]+src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher htmlMatcher = html.matcher(readme);
        while (htmlMatcher.find() && result.size() < 12) addScreenshotReference(result, seen, htmlMatcher.group(1), "Repository screenshot", rawReadme);
        return result;
    }

    private List<Screenshot> findRepositoryImages(String[] repositoryParts, String branch) throws Exception {
        List<Screenshot> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JSONObject treeResponse = getJson("https://api.github.com/repos/" + repositoryParts[0] + "/" + repositoryParts[1] + "/git/trees/" + branch + "?recursive=1");
        JSONArray tree = treeResponse.optJSONArray("tree");
        if (tree == null) return result;
        for (int i = 0; i < tree.length() && result.size() < 12; i++) {
            JSONObject item = tree.optJSONObject(i); if (item == null || !"blob".equals(item.optString("type"))) continue;
            String path = item.optString("path", ""); String lower = path.toLowerCase(Locale.ROOT);
            if (!lower.matches(".*\\.(png|jpe?g|webp|gif)$") || !lower.matches(".*(screenshot|screen|mockup|screenshots).*")) continue;
            String address = "https://raw.githubusercontent.com/" + repositoryParts[0] + "/" + repositoryParts[1] + "/" + branch + "/" + path;
            if (seen.add(address)) result.add(new Screenshot(address, path.substring(path.lastIndexOf('/') + 1)));
        }
        return result;
    }

    private void addScreenshotReference(List<Screenshot> result, Set<String> seen, String reference, String alt, String rawReadme) {
        try {
            String address = new URL(new URL(rawReadme), reference.replace("&amp;", "&")).toString();
            String lower = address.toLowerCase(Locale.ROOT);
            if (!(lower.contains("user-attachments") || lower.matches(".*\\.(png|jpe?g|webp|gif)(\\?.*)?$"))) return;
            if (seen.add(address)) result.add(new Screenshot(address, alt == null || alt.isEmpty() ? "Repository screenshot" : alt));
        } catch (Exception ignored) { }
    }

    private void addScreenshot(LinearLayout parent, Screenshot screenshot) {
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(rounded(PANEL, dp(18), RULE)); image.setPadding(dp(6), dp(6), dp(6), dp(6));
        image.setContentDescription(screenshot.alt); image.setFocusable(true); image.setClickable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(180), dp(250)); params.setMargins(0, 0, dp(10), 0);
        parent.addView(image, params); image.setOnClickListener(v -> openUrl(screenshot.address));
        io.execute(() -> {
            try {
                Bitmap bitmap = downloadScreenshot(screenshot.address);
                if (bitmap != null) runOnUiThread(() -> { if (image.getParent() != null) image.setImageBitmap(bitmap); });
            } catch (Exception ignored) { }
        });
    }

    private Bitmap downloadScreenshot(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "DPAD-Market-Android"); connection.connect();
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new Exception("Screenshot request failed");
        try (InputStream input = new BufferedInputStream(connection.getInputStream()); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count; if (total > 12L * 1024 * 1024) throw new Exception("Screenshot is too large");
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray(); BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > dp(360) || bounds.outHeight / options.inSampleSize > dp(500)) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } finally { connection.disconnect(); }
    }

    private String[] repositoryParts(String repository) throws Exception {
        List<String> parts = Uri.parse(repository).getPathSegments();
        if (parts.size() < 2) throw new Exception("Invalid repository URL");
        return new String[]{parts.get(0), parts.get(1)};
    }

    private void fetchLatest(AppEntry app, TextView target) {
        io.execute(() -> {
            try {
                JSONObject release = getJson(app.releaseApi);
                String version = release.optString("tag_name", "release");
                String date = release.optString("published_at", "");
                JSONObject assetInfo = findApkAsset(release, app);
                String asset = assetInfo == null ? null : assetInfo.optString("name");
                String digest = assetInfo == null ? "" : assetInfo.optString("digest", "");
                String result = version + (date.isEmpty() ? "" : "  ·  " + date.substring(0, Math.min(10, date.length()))) +
                        (asset == null ? "  ·  No APK asset found" : "  ·  " + asset + (digest.startsWith("sha256:") ? "  ·  SHA-256 published" : "  ·  no published hash"));
                runOnUiThread(() -> { if (!isFinishing()) target.setText("Latest release  /  " + result); });
            } catch (Exception ex) {
                runOnUiThread(() -> { if (!isFinishing()) target.setText("Latest release  /  Could not load from GitHub. Check connection or repository releases."); });
            }
        });
    }

    private void promptAddSource() {
        EditText address = new EditText(this);
        address.setSingleLine(true); address.setTextSize(15); address.setTextColor(FG); address.setHintTextColor(MUTED);
        address.setHint("https://github.com/owner/repository");
        address.setPadding(dp(17), 0, dp(17), 0); address.setBackground(rounded(BG, dp(18), RULE));

        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(14)); panel.setBackground(rounded(PANEL, dp(26), 0));
        panel.addView(text("Add a source", 21, FG, true));
        TextView intro = text("Connect a public GitHub repository to your market. Added sources are marked unreviewed.", 13, MUTED, false);
        intro.setPadding(0, dp(7), 0, dp(17)); panel.addView(intro);
        panel.addView(text("Repository URL", 11, ACCENT, true), bottom(dp(6)));
        panel.addView(address, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView hint = text("Example: github.com/owner/repository", 11, MUTED, false); hint.setPadding(dp(3), dp(7), 0, 0);
        panel.addView(hint, bottom(dp(18)));

        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        Button cancel = button("Cancel", FG, PANEL_HI); cancel.setTextSize(12);
        Button check = button("Check source", BG, ACCENT); check.setTextSize(12);
        actions.addView(cancel, new LinearLayout.LayoutParams(-2, dp(44)));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-2, dp(44)); checkParams.leftMargin = dp(8);
        actions.addView(check, checkParams); panel.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        check.setOnClickListener(v -> { dialog.dismiss(); checkUserSource(address.getText().toString()); });
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(rounded(PANEL, dp(26), 0));
                int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(390));
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private void checkUserSource(String address) {
        Uri uri = Uri.parse(address.trim());
        List<String> parts = uri.getPathSegments();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) || parts.size() != 2) {
            Toast.makeText(this, "Use a GitHub repository URL: github.com/owner/repository", Toast.LENGTH_LONG).show(); return;
        }
        String owner = parts.get(0), repository = parts.get(1).replaceFirst("\\.git$", "");
        String canonical = "https://github.com/" + owner + "/" + repository;
        for (AppEntry existing : catalog) if (existing.repository.equalsIgnoreCase(canonical)) {
            Toast.makeText(this, "That source is already in your catalog.", Toast.LENGTH_SHORT).show(); return;
        }
        Toast.makeText(this, "Checking repository and latest release…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject repositoryInfo = getJson("https://api.github.com/repos/" + owner + "/" + repository);
                JSONObject release = getJson("https://api.github.com/repos/" + owner + "/" + repository + "/releases/latest");
                if (findApkUrl(release) == null) throw new Exception("The latest release has no direct APK asset.");
                AppEntry app = new AppEntry();
                app.id = "user-" + owner + "-" + repository; app.packageId = "";
                app.name = repositoryInfo.optString("name", repository);
                JSONObject ownerInfo = repositoryInfo.optJSONObject("owner");
                app.developer = ownerInfo == null ? owner : ownerInfo.optString("login", owner);
                app.repository = canonical;
                app.releaseApi = "https://api.github.com/repos/" + owner + "/" + repository + "/releases/latest";
                app.category = "Community";
                app.summary = repositoryInfo.optString("description", "GitHub release app");
                if (app.summary == null || app.summary.trim().isEmpty()) app.summary = "GitHub release app";
                JSONObject license = repositoryInfo.optJSONObject("license");
                app.license = license == null ? "Not declared" : license.optString("spdx_id", "Not declared");
                app.platform = "Android (user-added)";
                app.inputSupport = "Not reviewed";
                app.curatorNote = "User-added source. Latest GitHub release: " + release.optString("tag_name", "unknown") + ".";
                app.unlisted = true;
                runOnUiThread(() -> {
                    catalog.add(app); saveUserSources(); activeCategory = "All"; query = ""; showHome();
                    Toast.makeText(this, app.name + " added as an unlisted source.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not add source: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void saveUserSources() {
        JSONArray userApps = new JSONArray();
        for (AppEntry app : catalog) if (app.unlisted) userApps.put(app.toJson());
        getPreferences(MODE_PRIVATE).edit().putString("user_sources", userApps.toString()).apply();
    }

    private void installLatest(AppEntry app, Button installButton) {
        installButton.setEnabled(false);
        installButton.setText("Preparing install...");
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            installButton.setEnabled(true);
            installButton.setText("Install latest APK");
            Toast.makeText(this, "Allow installs for DPAD Market, then choose install again.", Toast.LENGTH_LONG).show(); return;
        }
        Toast.makeText(this, "Checking GitHub release…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject release = getJson(app.releaseApi);
                JSONObject asset = findApkAsset(release, app);
                if (asset == null) throw new Exception("This release does not provide a direct APK asset.");
                String apkUrl = asset.optString("browser_download_url", null);
                String digest = asset.optString("digest", "");
                long size = asset.optLong("size", -1);
                if (size > 350L * 1024 * 1024) throw new Exception("APK asset exceeds the 350 MB safety limit.");
                installFromUrl(app, apkUrl, release.optString("tag_name", app.name), digest, size);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    installButton.setEnabled(true);
                    installButton.setText(isInstalled(app) ? "Update from GitHub" : "Install latest APK");
                    Toast.makeText(this, "Could not prepare install: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String currentVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "Unknown"; }
    }

    private void checkForMarketUpdates() {
        if (checkingMarketUpdate) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Allow installs for DPAD Market, then check for updates again.", Toast.LENGTH_LONG).show();
            return;
        }
        checkingMarketUpdate = true;
        Toast.makeText(this, "Checking GitHub for DPAD Market updates…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject release = getJson(MARKET_RELEASE_API);
                String version = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                if (version.isEmpty()) throw new Exception("GitHub release has no version tag.");
                JSONObject asset = findApkAsset(release);
                if (asset == null) throw new Exception("The latest GitHub release has no APK asset.");
                String apkUrl = asset.optString("browser_download_url", null);
                if (apkUrl == null || apkUrl.isEmpty()) throw new Exception("The release APK has no download URL.");
                long size = asset.optLong("size", -1);
                if (size > 350L * 1024 * 1024) throw new Exception("DPAD Market APK exceeds the 350 MB safety limit.");

                String currentVersion = currentVersionName();
                int versionComparison = compareVersions(version, currentVersion);
                if (versionComparison <= 0) {
                    String status = versionComparison == 0
                            ? "DPAD Market is up to date (v" + currentVersion + ")."
                            : "Installed v" + currentVersion + " is newer than the latest release (v" + version + ").";
                    runOnUiThread(() -> Toast.makeText(this, status, Toast.LENGTH_LONG).show());
                    return;
                }

                AppEntry market = new AppEntry();
                market.name = "DPAD Market";
                market.packageId = getPackageName();
                installFromUrl(market, apkUrl, version, asset.optString("digest", ""), size);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not check for DPAD Market updates: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                runOnUiThread(() -> checkingMarketUpdate = false);
            }
        });
    }

    private int compareVersions(String candidate, String current) {
        String[] candidateParts = candidate.split("[+-]", 2)[0].split("\\.");
        String[] currentParts = current.split("[+-]", 2)[0].replaceFirst("^[vV]", "").split("\\.");
        int count = Math.max(candidateParts.length, currentParts.length);
        for (int i = 0; i < count; i++) {
            long candidatePart = i < candidateParts.length ? parseVersionPart(candidateParts[i]) : 0;
            long currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
            if (candidatePart != currentPart) return Long.compare(candidatePart, currentPart);
        }
        return 0;
    }

    private long parseVersionPart(String part) {
        try { return Long.parseLong(part.replaceFirst("[^0-9].*$", "")); }
        catch (Exception ignored) { return 0; }
    }

    private boolean isInstalled(AppEntry app) {
        if (app.packageId == null || app.packageId.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(app.packageId, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void uninstallApp(AppEntry app) {
        if (app.packageId == null || app.packageId.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + app.packageId)));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Android's uninstall screen.", Toast.LENGTH_LONG).show();
        }
    }

    private void installFromUrl(AppEntry app, String apkUrl, String version, String expectedDigest, long expectedSize) throws Exception {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (!app.packageId.isEmpty()) params.setAppPackageName(app.packageId);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "DPAD-Market/0.1");
            connection.connect();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new Exception("GitHub download returned HTTP " + connection.getResponseCode());
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream()); OutputStream output = session.openWrite("base.apk", 0, expectedSize)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 350L * 1024 * 1024) throw new Exception("APK download exceeds the 350 MB safety limit.");
                    sha256.update(buffer, 0, count); output.write(buffer, 0, count);
                }
                String actualDigest = toHex(sha256.digest());
                if (expectedSize >= 0 && total != expectedSize) throw new Exception("Downloaded file size does not match GitHub metadata.");
                if (expectedDigest.startsWith("sha256:") && !actualDigest.equalsIgnoreCase(expectedDigest.substring(7)))
                    throw new Exception("SHA-256 does not match GitHub's release asset digest. Install cancelled.");
                session.fsync(output);
            } finally { connection.disconnect(); }
            Intent status = new Intent(this, InstallStatusReceiver.class).setAction(InstallStatusReceiver.ACTION_INSTALL_STATUS);
            status.putExtra("app_name", app.name); status.putExtra("version", version);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent callback = PendingIntent.getBroadcast(this, sessionId, status, flags);
            session.commit(callback.getIntentSender());
            runOnUiThread(() -> Toast.makeText(this, "Download complete. Android will ask you to confirm installation.", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            installer.abandonSession(sessionId); throw e;
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private String findApkUrl(JSONObject release) {
        JSONObject asset = findApkAsset(release);
        return asset == null ? null : asset.optString("browser_download_url", null);
    }

    private JSONObject findApkAsset(JSONObject release) {
        return findApkAsset(release, null);
    }

    private JSONObject findApkAsset(JSONObject release, AppEntry app) {
        JSONArray assets = release.optJSONArray("assets"); if (assets == null) return null;
        JSONObject best = null; int bestScore = -1;
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
        for (int i = 0; i < assets.length(); i++) {
            JSONObject item = assets.optJSONObject(i); if (item == null) continue;
            String name = item.optString("name", ""); String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".apk") || lower.contains("source") || lower.contains("symbols")) continue;
            int score = 0;
            if (lower.contains("universal")) score += 4;
            if (!abi.isEmpty() && lower.contains(abi.toLowerCase(Locale.ROOT))) score += 8;
            if (lower.contains("release")) score++;
            if (app != null && !app.apkAsset.isEmpty() && lower.contains(app.apkAsset.toLowerCase(Locale.ROOT))) score += 16;
            if (score > bestScore) { bestScore = score; best = item; }
        }
        return best;
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "DPAD-Market-Android");
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("GitHub returned HTTP " + responseCode + " for " + Uri.parse(address).getPath());
        }
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            return new JSONObject(output.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }

    private void addMeta(String key, String value) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, dp(10), 0, dp(10));
        TextView k = text(key, 11, MUTED, true); row.addView(k);
        TextView v = text(value, 13, FG, false); row.addView(v);
        currentContent.addView(row); View line = new View(this); line.setBackgroundColor(RULE); currentContent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    private void sectionLabel(LinearLayout parent, String label) { TextView t = text(label, 10, ACCENT, true); parent.addView(t, bottom(dp(8))); }
    private void addBack(LinearLayout parent, String label) { Button b = button(label, FG, PANEL_HI); parent.addView(b, bottom(dp(22))); b.setOnClickListener(v -> showHome()); }
    private void openUrl(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show(); } }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setFocusable(false); return t;
    }
    private Button button(String label, int textColor, int backgroundColor) {
        Button b = new Button(this); b.setText(label); b.setTextSize(12); b.setTextColor(textColor); b.setAllCaps(false);
        b.setBackground(selectable(backgroundColor)); b.setMinHeight(dp(46)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(13), 0, dp(13), 0); return b;
    }
    private android.graphics.drawable.Drawable selectable() { return selectable(PANEL); }
    private android.graphics.drawable.Drawable selectable(int color) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable focused = new android.graphics.drawable.GradientDrawable(); focused.setColor(PANEL_HI); focused.setCornerRadius(dp(28)); focused.setStroke(dp(2), ACCENT);
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable(); normal.setColor(color); normal.setCornerRadius(dp(28));
        states.addState(new int[]{android.R.attr.state_focused}, focused); states.addState(new int[]{android.R.attr.state_pressed}, focused); states.addState(new int[]{}, normal); return states;
    }
    private android.graphics.drawable.Drawable rounded(int color, float radius, int strokeColor) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(radius);
        if (strokeColor != 0) shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private void loadIcon(AppEntry app, ImageView target, TextView fallback) {
        String cacheKey = app.id;
        Bitmap cached = iconCache.get(cacheKey);
        if (cached != null) {
            target.setImageBitmap(cached); target.setVisibility(View.VISIBLE); fallback.setVisibility(View.GONE); return;
        }
        io.execute(() -> {
            try {
                File iconDirectory = new File(getCacheDir(), "app-icons");
                if (!iconDirectory.exists() && !iconDirectory.mkdirs()) return;
                File apk = new File(iconDirectory, cacheKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk");
                if (!apk.exists()) downloadIconApk(app, apk);
                PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(apk.getPath(), PackageManager.GET_META_DATA);
                if (packageInfo == null) return;
                if (!app.packageId.isEmpty() && !app.packageId.equals(packageInfo.packageName)) return;
                packageInfo.applicationInfo.sourceDir = apk.getPath();
                packageInfo.applicationInfo.publicSourceDir = apk.getPath();
                Drawable drawable = packageInfo.applicationInfo.loadIcon(getPackageManager());
                Bitmap bitmap = drawableToBitmap(drawable);
                if (bitmap == null) return;
                synchronized (iconCache) { iconCache.put(cacheKey, bitmap); }
                runOnUiThread(() -> {
                    if (target.getParent() != null) {
                        target.setImageBitmap(bitmap); target.setVisibility(View.VISIBLE); fallback.setVisibility(View.GONE);
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private void downloadIconApk(AppEntry app, File destination) throws Exception {
        JSONObject release = getJson(app.releaseApi);
        JSONObject asset = findApkAsset(release, app);
        if (asset == null) return;
        String apkUrl = asset.optString("browser_download_url", null);
        if (apkUrl == null) return;
        long expectedSize = asset.optLong("size", -1);
        if (expectedSize > 350L * 1024 * 1024) return;
        HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "DPAD-Market/0.1");
        connection.connect();
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new Exception("Icon APK download returned HTTP " + connection.getResponseCode());
        File temporary = new File(destination.getPath() + ".part");
        try (InputStream input = new BufferedInputStream(connection.getInputStream()); OutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32768]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 350L * 1024 * 1024) throw new Exception("Icon APK download exceeds the 350 MB safety limit.");
                output.write(buffer, 0, count);
            }
            if (expectedSize >= 0 && total != expectedSize) throw new Exception("Icon APK size does not match GitHub metadata.");
        } finally { connection.disconnect(); }
        if (!temporary.renameTo(destination)) throw new Exception("Could not cache icon APK.");
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        int width = Math.max(dp(48), drawable.getIntrinsicWidth());
        int height = Math.max(dp(48), drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height); drawable.draw(canvas);
        return bitmap;
    }
    static class Screenshot {
        final String address, alt;
        Screenshot(String address, String alt) { this.address = address; this.alt = alt; }
    }
    private LinearLayout.LayoutParams bottom(int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = margin; return p; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && selected != null) { selected = null; showHome(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BACK && showingSettings) { showHome(); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override protected void onDestroy() { super.onDestroy(); }
    @Override protected void onResume() {
        super.onResume();
        if (selected != null && !isInstalled(selected)) showDetail(selected);
    }

    static class AppEntry {
        String id, packageId, name, developer, repository, releaseApi, category, summary, curatorNote, license, platform, inputSupport, apkAsset;
        boolean unlisted;
        static AppEntry from(JSONObject o) {
            AppEntry a = new AppEntry(); a.id=o.optString("id"); a.packageId=o.optString("packageId"); a.name=o.optString("name"); a.developer=o.optString("developer");
            a.repository=o.optString("repository"); a.releaseApi=o.optString("releaseApi"); a.category=o.optString("category");
            a.summary=o.optString("summary"); a.curatorNote=o.optString("curatorNote"); a.license=o.optString("license");
            a.platform=o.optString("platform"); a.inputSupport=o.optString("inputSupport"); a.apkAsset=o.optString("apkAsset"); a.unlisted=o.optBoolean("unlisted", false); return a;
        }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("packageId", packageId); o.put("name", name); o.put("developer", developer);
                o.put("repository", repository); o.put("releaseApi", releaseApi); o.put("category", category);
                o.put("summary", summary); o.put("curatorNote", curatorNote); o.put("license", license);
                o.put("platform", platform); o.put("inputSupport", inputSupport); o.put("apkAsset", apkAsset); o.put("unlisted", unlisted);
            } catch (Exception ignored) { }
            return o;
        }
    }
}
