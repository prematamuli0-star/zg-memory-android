package com.zgmemory.android;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.util.LruCache;
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private final int BG = Color.rgb(12, 10, 19);
    private final int PANEL = Color.rgb(29, 23, 42);
    private final int PANEL2 = Color.rgb(39, 29, 54);
    private final int PURPLE = Color.rgb(192, 112, 255);
    private final int WHITE = Color.WHITE;
    private final int MUTED = Color.rgb(183, 172, 199);
    private final int GOLD = Color.rgb(255, 184, 38);

    private EditText searchBox;
    private LinearLayout listLayout, libraryRoot, miniPlayer;
    private TextView status, miniTitle, miniArtist, miniPlay;
    private ImageView miniCover;
    private MediaPlayer player;
    private MediaSession mediaSession;
    private final Handler playbackProgressHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackProgressUpdater = new Runnable() {
        @Override public void run() {
            if (player == null || !playerPrepared || mediaSession == null) return;
            try {
                if (!player.isPlaying()) return;
                long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
                mediaSession.setPlaybackState(new PlaybackState.Builder()
                        .setActions(actions)
                        .setState(PlaybackState.STATE_PLAYING, player.getCurrentPosition(), 1.0f,
                                SystemClock.elapsedRealtime())
                        .build());
                playbackProgressHandler.postDelayed(this, 1000);
            } catch (IllegalStateException ignored) {
                // Player was stopped or replaced while this update was running.
            }
        }
    };
    private NotificationManager notificationManager;
    private static final String CHANNEL_ID = "zg_memory_playback";
    private static final int NOTIFICATION_ID = 4107;
    private boolean playerPrepared = false;
    private ArrayList<JSONObject> songs = new ArrayList<>();
    private ArrayList<JSONObject> allSongs = new ArrayList<>();
    private final HashSet<String> favourites = new HashSet<>();
    // Small in-memory cover cache: mini-player can show a previously loaded
    // cover immediately instead of downloading it again.
    private final LruCache<String, Bitmap> coverCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final HashMap<String, ArrayList<ImageView>> coverTargetsInFlight = new HashMap<>();
    private final ArrayList<String> playlists = new ArrayList<>();
    private final HashMap<String, HashSet<String>> playlistSongs = new HashMap<>();
    private boolean shuffleEnabled = false, repeatEnabled = false;
    private JSONObject currentSong;
    private String supabaseUrl = "", publishableKey = "";
    private String authToken = "", authUserId = "", authIdentity = "";
    private final String PREFS = "zg_config";
    private boolean isFullScreen = false;
    private int currentTab = 0;
    private FrameLayout mainFrame;
    private LinearLayout mainColumn;
    private String currentQuery = "";
    private float fullPlayerTouchStartX = 0f;
    private float fullPlayerTouchStartY = 0f;
    private boolean trackingFullPlayerSwipe = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        android.content.SharedPreferences p = getSharedPreferences(PREFS, 0);
        // Prefer the values injected at build time. Saved settings remain a
        // fallback for local builds made without GitHub Actions secrets.
        supabaseUrl = com.zgmemory.android.BuildConfig.SUPABASE_URL;
        publishableKey = com.zgmemory.android.BuildConfig.SUPABASE_PUBLISHABLE_KEY;
        if (supabaseUrl == null || supabaseUrl.trim().isEmpty()) {
            supabaseUrl = p.getString("url", "");
        }
        if (publishableKey == null || publishableKey.trim().isEmpty()) {
            publishableKey = p.getString("key", "");
        }
        supabaseUrl = supabaseUrl == null ? "" : supabaseUrl.trim().replaceAll("/+$", "");
        publishableKey = publishableKey == null ? "" : publishableKey.trim();
        favourites.addAll(p.getStringSet("favourites", new HashSet<String>()));
        authToken = p.getString("auth_token", "");
        authUserId = p.getString("auth_user_id", "");
        authIdentity = p.getString("auth_identity", "");
        try { JSONArray pl = new JSONArray(p.getString("playlists_json", "[]")); for(int i=0;i<pl.length();i++){ JSONObject item=pl.getJSONObject(i); String name=item.optString("name",""); if(!name.isEmpty()){playlists.add(name); HashSet<String> ids=new HashSet<>(); JSONArray idsJson=item.optJSONArray("songs"); if(idsJson!=null)for(int j=0;j<idsJson.length();j++)ids.add(idsJson.optString(j)); playlistSongs.put(name,ids);}} } catch(Exception ignored) {}
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createPlaybackChannel();
        setupMediaSession();
        buildLibrary();
        if (supabaseUrl.isEmpty() || publishableKey.isEmpty()) { if (status != null) status.setText("App configuration missing. Please install the latest official build."); }
        else loadSongs("");
    }

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable gradient(int top, int bottom, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView pillButton(String label, int bg, int fg) {
        TextView t = text(label, 14, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(10), dp(13), dp(10));
        t.setBackground(shape(bg, 22));
        return t;
    }

    private void applySystemBarInsets(View target, int baseLeft, int baseTop, int baseRight, int baseBottom) {
        target.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
            return insets;
        });
        target.requestApplyInsets();
    }

    private void buildLibrary() {
        isFullScreen = false;
        mainFrame = new FrameLayout(this);
        mainFrame.setBackgroundColor(BG);
        // Android 15 / targetSdk 35 draws edge-to-edge. Keep content clear of
        // the status bar and navigation bar so the header and bottom controls
        // are not covered by system UI.
        applySystemBarInsets(mainFrame, 0, 0, 0, 0);
        mainColumn = column();
        mainColumn.setPadding(dp(16), dp(12), dp(16), 0);
        mainFrame.addView(mainColumn, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = row();
        ImageView brandLogo = new ImageView(this);
        brandLogo.setImageResource(com.zgmemory.android.R.drawable.zg_memory_logo);
        brandLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        logoParams.setMargins(0, 0, dp(10), 0);
        header.addView(brandLogo, logoParams);
        LinearLayout brand = column();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("ZG MEMORY", 25, WHITE, true);
        title.setLetterSpacing(.02f);
        brand.addView(title);
        TextView subtitle = text("Zubeen Garg  •  Music Library", 13, MUTED, false);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = text("⚙", 25, PURPLE, false);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(dp(12), dp(4), dp(4), dp(4));
        settings.setContentDescription("Account and sign in");
        settings.setOnClickListener(v -> showAccountMenu());
        header.addView(settings);
        mainColumn.addView(header);

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        tabs.setPadding(0, dp(14), 0, dp(12));
        String[] tabNames = {"Tracks", "Albums", "Artists", "Favourites", "Playlists"};
        for (int i=0;i<tabNames.length;i++) {
            final int ix = i;
            TextView tab = text(tabNames[i], 13, i==currentTab ? WHITE : MUTED, i==currentTab);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(16), dp(10), dp(16), dp(10));
            tab.setBackground(i==currentTab ? gradient(Color.rgb(143,72,225), Color.rgb(196,83,238), 25) : shape(BG,25));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.setMargins(0, 0, dp(6), 0);
            tabs.addView(tab,tp);
            tab.setOnClickListener(v -> {
                currentTab = ix;
                buildLibrary();
                renderVisibleSongs();
            });
        }
        tabsScroll.addView(tabs);
        mainColumn.addView(tabsScroll);

        LinearLayout searchRow = row();
        searchRow.setBackground(shape(PANEL, 26));
        searchRow.setPadding(dp(13), 0, dp(12), 0);
        TextView searchIcon = text("⌕", 25, MUTED, false);
        searchRow.addView(searchIcon);
        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(14);
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(MUTED);
        searchBox.setHint("Search songs, albums, year...");
        searchBox.setBackgroundColor(Color.TRANSPARENT);
        searchBox.setPadding(dp(8), dp(12), dp(4), dp(12));
        searchBox.setText(currentQuery);
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, -2, 1));
        // Search action doubles as a clear (×) button while a query is present.
        TextView searchBtn = text(currentQuery.isEmpty() ? "⌕" : "×", currentQuery.isEmpty() ? 23 : 27, PURPLE, true);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setMinWidth(dp(32));
        searchBtn.setPadding(dp(8), 0, dp(4), 0);
        searchBtn.setContentDescription(currentQuery.isEmpty() ? "Search" : "Clear search");
        searchBtn.setOnClickListener(v -> {
            if (searchBox != null && searchBox.length() > 0) {
                searchBox.setText("");
                searchBox.setSelection(0);
                searchBox.requestFocus();
            } else {
                currentQuery = "";
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchBox.getWindowToken(),0);
                renderVisibleSongs();
            }
        });
        searchBox.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                currentQuery=s.toString().trim();
                searchBtn.setText(currentQuery.isEmpty() ? "⌕" : "×");
                searchBtn.setTextSize(currentQuery.isEmpty() ? 23 : 27);
                searchBtn.setContentDescription(currentQuery.isEmpty() ? "Search" : "Clear search");
                renderVisibleSongs();
            }
            public void afterTextChanged(android.text.Editable e){}
        });
        searchRow.addView(searchBtn);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.setMargins(0, 0, 0, dp(10));
        mainColumn.addView(searchRow, srp);

        status = text("Loading your collection…", 13, MUTED, false);
        status.setPadding(dp(3), dp(2), 0, dp(8));
        mainColumn.addView(status);

        FrameLayout listFrame = new FrameLayout(this);
        LinearLayout.LayoutParams lfp = new LinearLayout.LayoutParams(-1, 0, 1);
        listLayout = column();
        listLayout.setPadding(0, 0, dp(22), dp(10));
        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.addView(listLayout);
        listFrame.addView(listScroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout alphabet = column();
        alphabet.setGravity(Gravity.CENTER);
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
        for (int i=0;i<letters.length();i++) {
            final String letter = String.valueOf(letters.charAt(i));
            TextView lt = text(letter, 9, MUTED, false);
            lt.setGravity(Gravity.CENTER);
            lt.setMinWidth(dp(18));
            lt.setPadding(0, dp(1), 0, dp(1));
            lt.setOnClickListener(v -> jumpToLetter(letter));
            alphabet.addView(lt);
        }
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(22), -1, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        listFrame.addView(alphabet, alp);
        mainColumn.addView(listFrame, lfp);

        miniPlayer = row();
        miniPlayer.setPadding(dp(10), dp(8), dp(10), dp(8));
        miniPlayer.setBackground(gradient(Color.rgb(255,176,27), Color.rgb(235,130,30), 30));
        miniCover = new ImageView(this);
        miniCover.setImageDrawable(defaultCover());
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniPlayer.addView(miniCover, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout meta = column();
        meta.setPadding(dp(9), 0, dp(6), 0);
        miniTitle = text("Choose a song", 13, Color.WHITE, true);
        miniArtist = text("Zubeen Garg", 11, Color.WHITE, false);
        meta.addView(miniTitle);
        meta.addView(miniArtist);
        miniPlayer.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        TextView previous = text("|◀", 17, Color.WHITE, true);
        previous.setPadding(dp(5), dp(8), dp(5), dp(8));
        previous.setOnClickListener(v -> playAt(Math.max(0, indexOfCurrent()-1)));
        miniPlayer.addView(previous);
        miniPlay = text("▶", 18, Color.WHITE, true);
        miniPlay.setGravity(Gravity.CENTER);
        miniPlay.setPadding(dp(9), dp(8), dp(9), dp(8));
        miniPlay.setOnClickListener(v -> togglePlayback());
        miniPlayer.addView(miniPlay);
        TextView next = text("▶|", 17, Color.WHITE, true);
        next.setPadding(dp(5), dp(8), dp(5), dp(8));
        next.setOnClickListener(v -> playAt(Math.min(songs.size()-1, indexOfCurrent()+1)));
        miniPlayer.addView(next);
        miniPlayer.setOnClickListener(v -> showFullScreen());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.setMargins(0, dp(8), 0, dp(8));
        mainColumn.addView(miniPlayer, mp);
        // Rebuilds the library view when returning from full-screen playback.
        // Restore the active song details instead of leaving the default prompt.
        syncMiniPlayerState();

        LinearLayout bottomNav = row();
        bottomNav.setGravity(Gravity.CENTER);
        String[] nav = {"⌂\nHome", "▤\nAlbums", "♡\nFavourites", "♫\nLibrary"};
        for (int i=0;i<nav.length;i++) {
            final int ix=i;
            TextView item = text(nav[i], 11, i==0?PURPLE:MUTED, i==0);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(6), dp(5), dp(6), dp(5));
            bottomNav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            item.setOnClickListener(v -> { currentTab = (ix==3 ? 0 : ix==1 ? 1 : ix==2 ? 3 : 0); buildLibrary(); renderVisibleSongs(); });
        }
        mainColumn.addView(bottomNav);
        setContentView(mainFrame);
        if (!allSongs.isEmpty()) renderVisibleSongs();
    }

    private void buildLibraryChromeOnly() { renderVisibleSongs(); }

    /** Restore mini-player content whenever buildLibrary() creates new views. */
    private void syncMiniPlayerState() {
        if (miniTitle == null || miniArtist == null || miniCover == null || miniPlay == null) return;
        if (currentSong == null) {
            miniTitle.setText("Choose a song");
            miniArtist.setText("Zubeen Garg");
            miniCover.setImageDrawable(defaultCover());
            miniCover.setTag(null);
            miniPlay.setText("▶");
            return;
        }
        miniTitle.setText(currentSong.optString("title", "Untitled"));
        String artist = currentSong.optString("artist", "Zubeen Garg");
        if (artist == null || artist.trim().isEmpty() || "null".equalsIgnoreCase(artist)) artist = "Zubeen Garg";
        miniArtist.setText(artist);
        loadCoverInto(miniCover, currentSong);
        boolean playing = false;
        try { playing = player != null && playerPrepared && player.isPlaying(); }
        catch (Exception ignored) { }
        miniPlay.setText(playing ? "Ⅱ" : "▶");
    }

    private String songKey(JSONObject s) { String id=s.optString("id",""); return !id.isEmpty() && !id.equals("null") ? id : s.optString("title","")+"|"+s.optString("album",""); }
    private void saveFavourites(){ getSharedPreferences(PREFS,0).edit().putStringSet("favourites",new HashSet<>(favourites)).apply(); }
    private void toggleFavourite(JSONObject s){String k=songKey(s); if(favourites.contains(k))favourites.remove(k);else favourites.add(k);saveFavourites();Toast.makeText(this,favourites.contains(k)?"Added to favourites":"Removed from favourites",Toast.LENGTH_SHORT).show();renderVisibleSongs();}
    private void savePlaylists(){try{JSONArray a=new JSONArray();for(String name:playlists){JSONObject o=new JSONObject();o.put("name",name);JSONArray ids=new JSONArray();for(String id:playlistSongs.getOrDefault(name,new HashSet<String>()))ids.put(id);o.put("songs",ids);a.put(o);}getSharedPreferences(PREFS,0).edit().putString("playlists_json",a.toString()).apply();}catch(Exception ignored){}}
    private void createPlaylist(){EditText input=new EditText(this);input.setHint("Playlist name");new AlertDialog.Builder(this).setTitle("Create playlist").setView(input).setPositiveButton("Create",(d,w)->{String name=input.getText().toString().trim();if(!name.isEmpty()&&!playlists.contains(name)){playlists.add(name);playlistSongs.put(name,new HashSet<String>());savePlaylists();renderVisibleSongs();}}).setNegativeButton("Cancel",null).show();}
    private void addCurrentToPlaylist(){addSongToPlaylist(currentSong);}
    private void addSongToPlaylist(JSONObject song){if(song==null){Toast.makeText(this,"Choose a song first",Toast.LENGTH_SHORT).show();return;}if(playlists.isEmpty()){createPlaylist();Toast.makeText(this,"Create a playlist, then tap + again to add this song",Toast.LENGTH_LONG).show();return;}String[] names=playlists.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names,(d,w)->{playlistSongs.get(names[w]).add(songKey(song));savePlaylists();Toast.makeText(this,"Added to "+names[w],Toast.LENGTH_SHORT).show();}).show();}
    private void renderVisibleSongs(){
        if(listLayout==null)return;
        listLayout.removeAllViews(); songs.clear();
        String needle=currentQuery.toLowerCase(Locale.ROOT);
        if(currentTab==1){
            LinkedHashMap<String,Integer> albums=new LinkedHashMap<>();
            LinkedHashMap<String,JSONObject> albumCovers=new LinkedHashMap<>();
            for(JSONObject s:allSongs){String a=s.optString("album","Unknown album");if(matchesQuery(s,needle)){albums.put(a,albums.getOrDefault(a,0)+1);if(!albumCovers.containsKey(a))albumCovers.put(a,s);}}
            status.setText(albums.size()+" albums");
            for(String a:albums.keySet()) addAlbumRow(a,albums.get(a)+" songs",albumCovers.get(a),()->{currentTab=0;currentQuery=a;if(searchBox!=null)searchBox.setText(a);renderVisibleSongs();});
            return;
        }
        if(currentTab==2){LinkedHashSet<String> artists=new LinkedHashSet<>();for(JSONObject s:allSongs)if(matchesQuery(s,needle))artists.add(s.optString("artist","Zubeen Garg"));status.setText(artists.size()+" artists");for(String a:artists)addGroupRow(a,"Artist",()->{currentTab=0;currentQuery=a;if(searchBox!=null)searchBox.setText(a);renderVisibleSongs();});return;}
        if(currentTab==4){status.setText(playlists.size()+" playlists");TextView create=pillButton("＋ Create playlist",PANEL2,WHITE);create.setOnClickListener(v->createPlaylist());listLayout.addView(create);for(String name:playlists){HashSet<String> ids=playlistSongs.getOrDefault(name,new HashSet<String>());addGroupRow(name,ids.size()+" songs",()->{new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Play first song","Add current song","Delete playlist"},(d,w)->{if(w==0){for(JSONObject s:allSongs)if(ids.contains(songKey(s))){playSong(s);break;}}else if(w==1)addCurrentToPlaylist();else{playlists.remove(name);playlistSongs.remove(name);savePlaylists();renderVisibleSongs();}}).show();});}return;}
        for(JSONObject s:allSongs){if(!matchesQuery(s,needle))continue;if(currentTab==3&&!favourites.contains(songKey(s)))continue;songs.add(s);}
        status.setText(currentTab==3?songs.size()+" favourites":songs.size()+" Zubeen Garg songs");
        if(songs.isEmpty())addMessage(currentTab==3?"No favourites yet. Use ⋮ on a song to add it.":"No songs found.");else for(JSONObject s:songs)addRow(s);
    }
    private boolean matchesQuery(JSONObject s,String needle){if(needle.isEmpty())return true;String hay=(s.optString("title","")+" "+s.optString("album","")+" "+s.optString("year","")+" "+s.optString("artist","")).toLowerCase(Locale.ROOT);return hay.contains(needle);}
    private void addAlbumRow(String name,String subtitle,JSONObject representative,Runnable action){
        LinearLayout r=row();r.setPadding(dp(8),dp(8),dp(8),dp(8));r.setBackground(shape(PANEL,16));
        ImageView cover=new ImageView(this);cover.setImageDrawable(defaultCover());cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        r.addView(cover,new LinearLayout.LayoutParams(dp(58),dp(58)));
        if(representative!=null)loadCoverInto(cover,representative);
        LinearLayout info=column();info.setGravity(Gravity.CENTER_VERTICAL);info.setPadding(dp(10),0,dp(4),0);
        info.addView(text(name,15,WHITE,true));info.addView(text(subtitle,12,MUTED,false));
        r.addView(info,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",25,PURPLE,true));r.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));listLayout.addView(r,p);
    }

    private void addGroupRow(String name,String subtitle,Runnable action){LinearLayout r=row();r.setPadding(dp(12),dp(14),dp(8),dp(14));r.setBackground(shape(PANEL,16));LinearLayout info=column();info.addView(text(name,15,WHITE,true));info.addView(text(subtitle,12,MUTED,false));r.addView(info,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",25,PURPLE,true));r.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));listLayout.addView(r,p);}


    private void addRow(JSONObject s) {
        LinearLayout item = row();
        item.setTag(s.optString("title", ""));
        item.setPadding(dp(5), dp(8), dp(4), dp(8));
        item.setBackgroundColor(Color.TRANSPARENT);
        ImageView cover = new ImageView(this);
        cover.setImageDrawable(defaultCover());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        item.addView(cover, new LinearLayout.LayoutParams(dp(54), dp(54)));
        loadCoverInto(cover, s);
        LinearLayout info = column();
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(10), 0, dp(4), 0);
        TextView title = text(s.optString("title","Untitled"), 14, WHITE, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);
        String album = s.optString("album","Album unknown");
        String year = s.optString("year","");
        TextView artist = text("Zubeen Garg", 12, MUTED, false);
        info.addView(artist);
        TextView details = text(album + (year.isEmpty() ? "" : " · " + year), 11, MUTED, false);
        details.setMaxLines(1);
        details.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(details);
        item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text("⋮", 23, MUTED, false);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(8), 0, dp(4), 0);
        more.setOnClickListener(v -> new AlertDialog.Builder(this).setItems(new String[]{"Play now","Open full-screen player", favourites.contains(songKey(s))?"Remove from favourites":"Add to favourites","Add to playlist"},(d,w)->{if(w==0)playSong(s);else if(w==1){playSong(s);showFullScreen();}else if(w==2)toggleFavourite(s);else addSongToPlaylist(s);}).show());
        item.addView(more);
        item.setOnClickListener(v -> playSong(s));
        listLayout.addView(item);
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(47,38,58));
        LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(-1, dp(1));
        dpv.setMargins(dp(66), dp(1), dp(8), 0);
        listLayout.addView(divider, dpv);
    }

    private void jumpToLetter(String letter) {
        if (listLayout == null) return;
        for (int i=0;i<listLayout.getChildCount();i++) {
            View v = listLayout.getChildAt(i);
            if (v instanceof LinearLayout && v.getTag() != null && v.getTag().toString().toUpperCase(Locale.ROOT).startsWith(letter)) {
                v.requestFocus();
                break;
            }
        }
    }

    private Drawable defaultCover() {
        GradientDrawable gd = gradient(Color.rgb(76,39,108), Color.rgb(23,17,34), 14);
        return gd;
    }

    private String coverUrl(JSONObject song) {
        String url = song.optString("cover_url", "").trim();
        if (!url.isEmpty() && !url.equalsIgnoreCase("null")) return url;
        String path = song.optString("cover_path", "").trim();
        if (path.isEmpty() || path.equalsIgnoreCase("null") || supabaseUrl.isEmpty()) return "";
        try {
            path = path.replaceAll("^/+", "");
            if (path.startsWith("storage/v1/object/public/music-covers/"))
                return supabaseUrl + "/" + pathEncode(path);
            if (path.startsWith("music-covers/")) path = path.substring("music-covers/".length());
            return supabaseUrl + "/storage/v1/object/public/music-covers/" + pathEncode(path);
        } catch (Exception ignored) { return ""; }
    }

    private void loadCoverInto(ImageView target, JSONObject song) {
        String finalUrl = coverUrl(song);
        if (finalUrl.isEmpty()) { target.setImageDrawable(defaultCover()); return; }
        target.setTag(finalUrl);
        Bitmap cached = coverCache.get(finalUrl);
        if (cached != null) { target.setImageBitmap(cached); return; }
        target.setImageDrawable(defaultCover());
        synchronized (coverTargetsInFlight) {
            ArrayList<ImageView> waiting = coverTargetsInFlight.get(finalUrl);
            if (waiting != null) { waiting.add(target); return; }
            waiting = new ArrayList<>(); waiting.add(target);
            coverTargetsInFlight.put(finalUrl, waiting);
        }
        new Thread(() -> {
            Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection)new URL(finalUrl).openConnection();
                c.setConnectTimeout(3500); c.setReadTimeout(5000);
                c.setUseCaches(true);
                c.setRequestProperty("Cache-Control", "public, max-age=86400");
                c.setRequestProperty("User-Agent", "ZGMemory/1.0");
                int responseCode = c.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) throw new IOException("Cover HTTP " + responseCode);
                byte[] imageBytes;
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = in.read(chunk)) != -1) out.write(chunk, 0, count);
                    imageBytes = out.toByteArray();
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 1;
                while (bounds.outWidth / opts.inSampleSize > 900 || bounds.outHeight / opts.inSampleSize > 900) opts.inSampleSize *= 2;
                bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, opts);
                if (bmp != null) coverCache.put(finalUrl, bmp);
            } catch(Exception ignored) {
            } finally { if (c != null) c.disconnect(); }
            final Bitmap loaded = bmp;
            final ArrayList<ImageView> waiting;
            synchronized (coverTargetsInFlight) { waiting = coverTargetsInFlight.remove(finalUrl); }
            if (loaded != null && waiting != null) runOnUiThread(() -> {
                for (ImageView imageView : waiting) {
                    if (finalUrl.equals(imageView.getTag())) imageView.setImageBitmap(loaded);
                }
            });
        }).start();
    }

    private void loadSongs(String q) {
        currentQuery = q == null ? "" : q;
        if (supabaseUrl.isEmpty() || publishableKey.isEmpty()) {
            if (status != null) status.setText("Add Supabase settings first.");
            return;
        }
        if (status != null) status.setText("Loading your collection…");
        if (listLayout != null) listLayout.removeAllViews();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                String url = supabaseUrl + "/rest/v1/songs?select=*&order=sort_order.asc";
                c = (HttpURLConnection)new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("apikey", publishableKey);
                c.setRequestProperty("Authorization", "Bearer " + publishableKey);
                int code = c.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String body = readAll(in);
                if (in != null) in.close();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + body);
                JSONArray a = new JSONArray(body);
                ArrayList<JSONObject> got = new ArrayList<>();
                for (int i=0;i<a.length();i++) { JSONObject s=a.getJSONObject(i); if (!s.optString("artist","").toLowerCase(Locale.ROOT).contains("zubeen garg")) continue; got.add(s); }
                runOnUiThread(() -> {
                    allSongs = got;
                    renderVisibleSongs();
                });
            } catch(Exception e) {
                String m = e.getMessage() == null ? "Unknown error" : e.getMessage();
                runOnUiThread(() -> { if(status!=null) status.setText("Load failed: " + m); });
            } finally { if(c!=null) c.disconnect(); }
        }).start();
    }

    private void addMessage(String message) {
        TextView t = text(message, 13, MUTED, false);
        t.setPadding(dp(8), dp(18), dp(8), dp(18));
        listLayout.addView(t);
    }

    private void showAccountMenu() {
        ArrayList<String> options = new ArrayList<>();
        options.add(authToken.isEmpty() ? "Login / Sign up" : "Signed in: " + (authIdentity.isEmpty()?"account":authIdentity));
        if (!authToken.isEmpty()) options.add("Sign out");
        options.add("Back to library");
        new AlertDialog.Builder(this).setTitle("ZG Memory Account").setItems(options.toArray(new String[0]), (d, which) -> {
            String selected = options.get(which);
            if (selected.equals("Login / Sign up")) showAuthChoices();
            else if (selected.equals("Sign out")) signOut();
            else if (selected.equals("Back to library")) buildLibrary();
        }).show();
    }

    private void showAuthChoices() {
        new AlertDialog.Builder(this).setTitle("Login / Sign up")
            .setItems(new String[]{"Email + Password", "Phone number + SMS OTP"}, (d, which) -> {
                if (which == 0) showEmailAuth(); else showPhoneEntry();
            }).setNegativeButton("Cancel", null).show();
    }

    private void showEmailAuth() {
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), 0);
        Spinner mode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Login", "Sign up"}); mode.setAdapter(adapter); form.addView(mode);
        EditText email = new EditText(this); email.setSingleLine(true); email.setInputType( android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); email.setHint("Email address"); form.addView(email);
        EditText pass = new EditText(this); pass.setSingleLine(true); pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); pass.setHint("Password"); form.addView(pass);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Email account").setView(form).setPositiveButton("Continue", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(btn -> {
            String em=email.getText().toString().trim(), pw=pass.getText().toString();
            if(em.isEmpty()||pw.length()<6){Toast.makeText(this,"Enter a valid email and password (6+ characters)",Toast.LENGTH_LONG).show();return;}
            boolean signup=mode.getSelectedItemPosition()==1; dialog.getButton(-1).setEnabled(false);
            new Thread(() -> { try {
                JSONObject body=new JSONObject(); body.put("email",em); body.put("password",pw);
                JSONObject result=authRequest(signup?"/auth/v1/signup":"/auth/v1/token?grant_type=password",body,null);
                String token=result.optString("access_token",""); JSONObject user=result.optJSONObject("user"); if(user==null)user=result;
                String uid=user.optString("id","");
                if(token.isEmpty()||uid.isEmpty()) { String msg=signup?"Sign-up submitted. If email confirmation is enabled, confirm your email, then Login.":"Login did not return a session."; runOnUiThread(()->{dialog.dismiss();new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK",(dd,ww)->showEmailAuth()).show();}); return; }
                finishLogin(token,uid,em); runOnUiThread(()->{dialog.dismiss();Toast.makeText(this,"Signed in successfully",Toast.LENGTH_LONG).show();});
            } catch(Exception e){String msg=e.getMessage();runOnUiThread(()->{dialog.getButton(-1).setEnabled(true);Toast.makeText(this,msg,Toast.LENGTH_LONG).show();});} }).start();
        })); dialog.show();
    }

    private void showPhoneEntry() {
        EditText phone = new EditText(this); phone.setSingleLine(true); phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE); phone.setHint("Phone with country code, e.g. +91XXXXXXXXXX");
        new AlertDialog.Builder(this).setTitle("Phone OTP login / signup").setMessage("SMS provider must be enabled in Supabase Auth.").setView(phone).setPositiveButton("Send OTP", (d,w)->{
            String number=phone.getText().toString().trim(); if(!number.startsWith("+")||number.length()<8){Toast.makeText(this,"Enter phone number with country code, e.g. +91",Toast.LENGTH_LONG).show();return;}
            new Thread(()->{try{JSONObject b=new JSONObject();b.put("phone",number);b.put("create_user",true);authRequest("/auth/v1/otp",b,null);runOnUiThread(()->showOtpEntry(number));}catch(Exception e){runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}}).start();
        }).setNegativeButton("Cancel",null).show();
    }

    private void showOtpEntry(String phone) {
        EditText otp=new EditText(this);otp.setSingleLine(true);otp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);otp.setHint("SMS verification code");
        new AlertDialog.Builder(this).setTitle("Verify phone number").setView(otp).setPositiveButton("Verify & Login",(d,w)->{String code=otp.getText().toString().trim();if(code.isEmpty())return;new Thread(()->{try{JSONObject b=new JSONObject();b.put("phone",phone);b.put("token",code);b.put("type","sms");JSONObject result=authRequest("/auth/v1/verify",b,null);JSONObject user=result.optJSONObject("user");if(user==null)user=new JSONObject();String token=result.optString("access_token","");String uid=user.optString("id","");if(token.isEmpty()||uid.isEmpty())throw new Exception("OTP verification did not return a session");finishLogin(token,uid,phone);runOnUiThread(()->Toast.makeText(this,"Phone verified and signed in",Toast.LENGTH_LONG).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}).setNegativeButton("Cancel",null).show();
    }

    private JSONObject authRequest(String endpoint, JSONObject body, String bearer) throws Exception {
        HttpURLConnection c=null; try { c=(HttpURLConnection)new URL(supabaseUrl+endpoint).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("apikey",publishableKey);c.setRequestProperty("Authorization","Bearer "+(bearer==null||bearer.isEmpty()?publishableKey:bearer));byte[] data=body.toString().getBytes("UTF-8");c.getOutputStream().write(data);int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String response=readAll(in);if(in!=null)in.close();if(code<200||code>=300){try{JSONObject err=new JSONObject(response);throw new Exception(err.optString("msg",err.optString("message",err.optString("error_description",response))));}catch(org.json.JSONException ignored){throw new Exception("HTTP "+code+": "+response);}}return new JSONObject(response);}finally{if(c!=null)c.disconnect();}
    }

    private void finishLogin(String token,String uid,String identity){authToken=token;authUserId=uid;authIdentity=identity;getSharedPreferences(PREFS,0).edit().putString("auth_token",token).putString("auth_user_id",uid).putString("auth_identity",identity).apply();syncCloudFavourites();}
    private void signOut(){authToken="";authUserId="";authIdentity="";getSharedPreferences(PREFS,0).edit().remove("auth_token").remove("auth_user_id").remove("auth_identity").apply();Toast.makeText(this,"Signed out. Local favourites remain on this phone.",Toast.LENGTH_LONG).show();}

    private void syncCloudFavourites(){ if(authToken.isEmpty()||authUserId.isEmpty())return; new Thread(()->{HttpURLConnection c=null;try{String url=supabaseUrl+"/rest/v1/user_favourites?select=song_key&user_id=eq."+URLEncoder.encode(authUserId,"UTF-8");c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod("GET");c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("apikey",publishableKey);c.setRequestProperty("Authorization","Bearer "+authToken);int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=readAll(in);if(in!=null)in.close();if(code<200||code>=300)throw new Exception(body);JSONArray a=new JSONArray(body);HashSet<String> cloud=new HashSet<>();for(int i=0;i<a.length();i++)cloud.add(a.getJSONObject(i).optString("song_key",""));HashSet<String> local=new HashSet<>(favourites);cloud.addAll(local);runOnUiThread(()->{favourites.clear();favourites.addAll(cloud);saveFavourites();renderVisibleSongs();});for(String localKey:local)syncOneFavourite(localKey,true);}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Cloud favourites not ready. Set up the SQL table/policies first.",Toast.LENGTH_LONG).show());}finally{if(c!=null)c.disconnect();}}).start(); }

    private void syncOneFavourite(String key, boolean add){new Thread(()->{HttpURLConnection c=null;try{String base=supabaseUrl+"/rest/v1/user_favourites";if(add){c=(HttpURLConnection)new URL(base+"?on_conflict=user_id,song_key").openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Prefer","resolution=merge-duplicates,return=minimal");JSONObject row=new JSONObject();row.put("user_id",authUserId);row.put("song_key",key);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("apikey",publishableKey);c.setRequestProperty("Authorization","Bearer "+authToken);c.getOutputStream().write(new JSONArray().put(row).toString().getBytes("UTF-8"));}else{String url=base+"?user_id=eq."+URLEncoder.encode(authUserId,"UTF-8")+"&song_key=eq."+URLEncoder.encode(key,"UTF-8");c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod("DELETE");c.setRequestProperty("apikey",publishableKey);c.setRequestProperty("Authorization","Bearer "+authToken);}int code=c.getResponseCode();if(code<200||code>=300){InputStream in=c.getErrorStream();String body=readAll(in);if(in!=null)in.close();throw new Exception(body);}}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Favourite saved on this phone; cloud sync needs table/policies.",Toast.LENGTH_SHORT).show());}finally{if(c!=null)c.disconnect();}}).start();}

    private void showConfig() {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText u = new EditText(this);
        u.setSingleLine(true); u.setHint("Supabase Project URL"); u.setText(supabaseUrl);
        EditText k = new EditText(this);
        k.setSingleLine(true); k.setHint("Publishable / anon key ONLY"); k.setText(publishableKey);
        form.addView(u); form.addView(k);
        new AlertDialog.Builder(this).setTitle("Connect ZG Memory")
            .setMessage("Never enter service_role or secret keys. Use the project URL and publishable/anon key.")
            .setView(form)
            .setPositiveButton("Save & Connect",(d,w)->{
                supabaseUrl=u.getText().toString().trim().replaceAll("/+$","");
                publishableKey=k.getText().toString().trim();
                getSharedPreferences(PREFS,0).edit().putString("url",supabaseUrl).putString("key",publishableKey).apply();
                loadSongs("");
            }).setNegativeButton("Cancel",null).show();
    }

    private String audioUrl(JSONObject s) throws Exception {
        String p = s.optString("audio_path", "").trim();
        if (p.isEmpty() || p.equalsIgnoreCase("null")) p = s.optString("audio_url", "").trim();
        if (p.isEmpty() || p.equalsIgnoreCase("null")) p = s.optString("stream_url", "").trim();
        if (p.startsWith("https://") || p.startsWith("http://")) return p;
        if (p.isEmpty() || p.equalsIgnoreCase("null")) throw new Exception("Missing audio_path/audio_url in songs table");
        p = p.replaceAll("^/+", "");
        String prefix = "storage/v1/object/public/music-audio/";
        if (p.startsWith(prefix)) return supabaseUrl + "/" + pathEncode(p);
        if (p.startsWith("music-audio/")) p = p.substring("music-audio/".length());
        return supabaseUrl + "/storage/v1/object/public/music-audio/" + pathEncode(p);
    }

    private void playSong(JSONObject s) {
        try {
            if(player!=null){try{player.setOnPreparedListener(null);player.setOnErrorListener(null);player.release();}catch(Exception ignored){} player=null;}
            playerPrepared=false;
            currentSong=s;
            if(miniTitle!=null) miniTitle.setText(s.optString("title","Untitled"));
            if(miniArtist!=null) miniArtist.setText("Zubeen Garg");
            if(miniCover!=null) loadCoverInto(miniCover,s);
            if(miniPlay!=null) miniPlay.setText("Ⅱ");
            String u=audioUrl(s);
            final MediaPlayer thisPlayer=new MediaPlayer();
            player=thisPlayer;
            final boolean[] prepared={false};
            thisPlayer.setOnPreparedListener(mp->{
                if(player!=mp) return;
                prepared[0]=true;
                playerPrepared=true;
                try { mp.start(); if(miniPlay!=null) miniPlay.setText("Ⅱ"); updateSessionAndNotification(true); }
                catch(Exception startError){ if(miniPlay!=null) miniPlay.setText("▶"); updateSessionAndNotification(false); }
            });
            thisPlayer.setOnCompletionListener(mp->{if(player!=mp)return;updateSessionAndNotification(false);if(repeatEnabled){mp.seekTo(0);mp.start();if(miniPlay!=null)miniPlay.setText("Ⅱ");}else if(!songs.isEmpty()){int ni=shuffleEnabled?new Random().nextInt(songs.size()):Math.min(songs.size()-1,indexOfCurrent()+1);if(ni!=indexOfCurrent()||songs.size()==1)playAt(ni);else if(miniPlay!=null)miniPlay.setText("▶");}else if(miniPlay!=null)miniPlay.setText("▶");});
            thisPlayer.setOnErrorListener((mp,w,e)->{
                // Ignore delayed error callbacks from a released/replaced player and callbacks after successful preparation.
                if(player!=mp || prepared[0]) return true;
                playerPrepared=false;
                if(miniPlay!=null) miniPlay.setText("▶");
                updateSessionAndNotification(false);
                // MediaPlayer can emit -38 after a player was released or a state changed;
                // ignore stale callbacks and don't show a misleading failure toast.
                if (player == mp) Toast.makeText(this,"Unable to start this track. Try again if playback does not begin.",Toast.LENGTH_SHORT).show();
                return true;
            });
            thisPlayer.setDataSource(u);
            thisPlayer.prepareAsync();
            if(isFullScreen) showFullScreen();
        } catch(Exception e) {
            Toast.makeText(this,"Cannot play: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void togglePlayback() {
        if(player==null){ if(!songs.isEmpty())playSong(songs.get(0)); return; }
        try {
            if(!playerPrepared) return;
            if(player.isPlaying()){player.pause();if(miniPlay!=null)miniPlay.setText("▶");updateSessionAndNotification(false);}
            else {player.start();if(miniPlay!=null)miniPlay.setText("Ⅱ");updateSessionAndNotification(true);}
        } catch(Exception ignored) {}
    }

    private int indexOfCurrent() {
        if(currentSong==null)return 0;
        for(int i=0;i<songs.size();i++)if(songs.get(i).optString("id","").equals(currentSong.optString("id","")))return i;
        return 0;
    }

    private void playAt(int i) { if(i>=0&&i<songs.size())playSong(songs.get(i)); }

    private void showFullScreen() {
        if(currentSong==null) {
            if(!songs.isEmpty()) { playSong(songs.get(0)); return; }
            Toast.makeText(this,"Choose a song first.",Toast.LENGTH_SHORT).show(); return;
        }
        isFullScreen=true;
        int[] colors = {Color.rgb(255,183,104), Color.rgb(245,90,139), Color.rgb(111,36,115)};
        LinearLayout root=column();
        root.setPadding(0,0,0,0);
        root.setBackground(gradient(colors[0],colors[2],0));
        // Overlay a soft gradient using a background drawable with three color stops.
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);
        root.setBackground(bg);
        // The full-screen player also needs safe padding for system bars.
        applySystemBarInsets(root, dp(20), dp(10), dp(20), dp(20));

        LinearLayout top=row();
        TextView down=text("⌄",32,WHITE,false);
        down.setPadding(dp(4),dp(4),dp(12),dp(4));
        down.setOnClickListener(v->buildLibrary());
        top.addView(down);
        LinearLayout topMeta=column(); topMeta.setGravity(Gravity.CENTER);
        TextView playingFrom=text("PLAYING FROM",10,Color.rgb(255,236,238),true);
        TextView album=text(currentSong.optString("album","ZG Memory"),13,WHITE,true);
        topMeta.addView(playingFrom); topMeta.addView(album);
        top.addView(topMeta,new LinearLayout.LayoutParams(0,-2,1));
        TextView more=text("⋮",26,WHITE,true); more.setPadding(dp(10),dp(4),dp(4),dp(4));
        more.setOnClickListener(v->new AlertDialog.Builder(this).setItems(new String[]{"Account / Login","Back to library"},(d,w)->{if(w==0)showAccountMenu();else buildLibrary();}).show());
        top.addView(more);
        root.addView(top);

        ImageView cover=new ImageView(this);
        cover.setImageDrawable(defaultCover());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,0,4f);
        cp.setMargins(dp(10),dp(22),dp(10),dp(16));
        root.addView(cover,cp);
        loadCoverInto(cover,currentSong);
        cover.setBackground(shape(Color.BLACK,24));
        cover.setClipToOutline(true);

        TextView title=text(currentSong.optString("title","Untitled"),24,WHITE,true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        TextView artist=text("Zubeen Garg",16,WHITE,false); artist.setGravity(Gravity.CENTER);
        TextView albumName=text(currentSong.optString("album",""),13,Color.rgb(255,235,238),false); albumName.setGravity(Gravity.CENTER);
        root.addView(title); root.addView(artist); root.addView(albumName);

        LinearLayout actions=row(); actions.setGravity(Gravity.CENTER);
        TextView heart=text(favourites.contains(songKey(currentSong))?"♥":"♡",29,WHITE,false); heart.setGravity(Gravity.CENTER); heart.setOnClickListener(v->{toggleFavourite(currentSong);heart.setText(favourites.contains(songKey(currentSong))?"♥":"♡");}); actions.addView(heart,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView add=text("+",32,WHITE,false); add.setGravity(Gravity.CENTER); add.setOnClickListener(v->addCurrentToPlaylist()); actions.addView(add,new LinearLayout.LayoutParams(0,dp(50),1));
        root.addView(actions);

        SeekBar seek=new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x88FFFFFF));
        root.addView(seek,new LinearLayout.LayoutParams(-1,dp(28)));
        LinearLayout times=row();
        TextView elapsed=text("0:00",12,WHITE,false); TextView duration=text("--:--",12,WHITE,false);
        times.addView(elapsed,new LinearLayout.LayoutParams(0,-2,1)); times.addView(duration);
        root.addView(times);

        LinearLayout controls=row(); controls.setGravity(Gravity.CENTER);
        TextView shuffle=text(shuffleEnabled?"🔀":"⤨",23,WHITE,true); shuffle.setGravity(Gravity.CENTER); shuffle.setOnClickListener(v->{shuffleEnabled=!shuffleEnabled;shuffle.setText(shuffleEnabled?"🔀":"⤨");Toast.makeText(this,shuffleEnabled?"Shuffle on":"Shuffle off",Toast.LENGTH_SHORT).show();});
        TextView prev=text("|◀",23,WHITE,true); prev.setGravity(Gravity.CENTER); prev.setOnClickListener(v->playAt(Math.max(0,indexOfCurrent()-1)));
        TextView play=text(player!=null&&player.isPlaying()?"Ⅱ":"▶",31,Color.rgb(135,35,104),true);
        play.setGravity(Gravity.CENTER); play.setBackground(shape(Color.WHITE,40));
        TextView next=text("▶|",23,WHITE,true); next.setGravity(Gravity.CENTER); next.setOnClickListener(v->playAt(Math.min(songs.size()-1,indexOfCurrent()+1)));
        TextView repeat=text(repeatEnabled?"↻1":"↻",23,WHITE,true); repeat.setGravity(Gravity.CENTER); repeat.setOnClickListener(v->{repeatEnabled=!repeatEnabled;repeat.setText(repeatEnabled?"↻1":"↻");Toast.makeText(this,repeatEnabled?"Repeat current song on":"Repeat off",Toast.LENGTH_SHORT).show();});
        controls.addView(shuffle,new LinearLayout.LayoutParams(0,dp(58),1));
        controls.addView(prev,new LinearLayout.LayoutParams(0,dp(58),1));
        controls.addView(play,new LinearLayout.LayoutParams(dp(62),dp(62)));
        controls.addView(next,new LinearLayout.LayoutParams(0,dp(58),1));
        controls.addView(repeat,new LinearLayout.LayoutParams(0,dp(58),1));
        play.setOnClickListener(v->{togglePlayback();play.setText(player!=null&&player.isPlaying()?"Ⅱ":"▶");});
        root.addView(controls);

        LinearLayout bottom=row(); bottom.setGravity(Gravity.CENTER);
        String[] labels={"Lyrics","♡ Favourite","↓ Download","••• More"};
        for(int i=0;i<labels.length;i++){final int action=i;TextView t=text(i==1&&favourites.contains(songKey(currentSong))?"♥ Favourite":labels[i],11,WHITE,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(3),dp(15),dp(3),dp(4));t.setOnClickListener(v->{if(action==1){toggleFavourite(currentSong);t.setText(favourites.contains(songKey(currentSong))?"♥ Favourite":"♡ Favourite");}else if(action==0)Toast.makeText(this,"Lyrics are not available for this track yet.",Toast.LENGTH_SHORT).show();else if(action==2)Toast.makeText(this,"Download is not implemented in this version.",Toast.LENGTH_SHORT).show();else new AlertDialog.Builder(this).setItems(new String[]{"Account / Login","Add to playlist","Track details"},(d,w)->{if(w==0)showAccountMenu();else if(w==1)addCurrentToPlaylist();else Toast.makeText(this,currentSong.optString("title","")+" · "+currentSong.optString("album",""),Toast.LENGTH_LONG).show();}).show();});bottom.addView(t,new LinearLayout.LayoutParams(0,-2,1));}
        root.addView(bottom);

        // Swipe-down is handled at Activity dispatch level because child views
        // (cover, SeekBar and controls) otherwise consume touch events.
        setContentView(root);
        final Handler handler=new Handler(Looper.getMainLooper());
        Runnable updater=new Runnable(){public void run(){
            if(!isFullScreen)return;
            if(player!=null){try{if(player.getDuration()>0){seek.setMax(player.getDuration());seek.setProgress(player.getCurrentPosition());elapsed.setText(formatTime(player.getCurrentPosition()));duration.setText(formatTime(player.getDuration()));}}catch(Exception ignored){}}
            handler.postDelayed(this,500);
        }};
        handler.post(updater);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&player!=null)elapsed.setText(formatTime(p));}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){if(player!=null)try{player.seekTo(s.getProgress());}catch(Exception ignored){}}
        });
    }

    private String formatTime(int ms){int sec=Math.max(0,ms/1000);return String.format(Locale.ROOT,"%d:%02d",sec/60,sec%60);}
    private String readAll(InputStream in)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toString("UTF-8");}
    private String pathEncode(String p)throws Exception{String[]a=p.split("/");StringBuilder b=new StringBuilder();for(int i=0;i<a.length;i++){if(i>0)b.append('/');b.append(URLEncoder.encode(a[i],"UTF-8").replace("+","%20"));}return b.toString();}

    private boolean fullPlayerSwipeHandled = false;

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (isFullScreen) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                fullPlayerTouchStartX = event.getX();
                fullPlayerTouchStartY = event.getY();
                trackingFullPlayerSwipe = true;
                fullPlayerSwipeHandled = false;
            } else if (action == MotionEvent.ACTION_MOVE && trackingFullPlayerSwipe) {
                float dx = event.getX() - fullPlayerTouchStartX;
                float dy = event.getY() - fullPlayerTouchStartY;
                if (dy > dp(72) && dy > Math.abs(dx) * 1.15f) {
                    trackingFullPlayerSwipe = false;
                    fullPlayerSwipeHandled = true;
                    buildLibrary();
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP) {
                if (fullPlayerSwipeHandled) {
                    fullPlayerSwipeHandled = false;
                    trackingFullPlayerSwipe = false;
                    return true;
                }
                if (trackingFullPlayerSwipe) {
                    float dx = event.getX() - fullPlayerTouchStartX;
                    float dy = event.getY() - fullPlayerTouchStartY;
                    trackingFullPlayerSwipe = false;
                    if (dy > dp(72) && dy > Math.abs(dx) * 1.15f) {
                        buildLibrary();
                        return true;
                    }
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                trackingFullPlayerSwipe = false;
                fullPlayerSwipeHandled = false;
            }
        } else {
            trackingFullPlayerSwipe = false;
            fullPlayerSwipeHandled = false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public void onBackPressed() {
        if(isFullScreen) buildLibrary();
        else super.onBackPressed();
    }
    private void createPlaybackChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "ZG Memory playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Music playback controls");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "ZGMemorySession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { runOnUiThread(() -> { if (player != null && playerPrepared) { try { if (!player.isPlaying()) player.start(); if (miniPlay != null) miniPlay.setText("Ⅱ"); updateSessionAndNotification(true); } catch (Exception ignored) {} } else if (!songs.isEmpty()) playSong(currentSong == null ? songs.get(0) : currentSong); }); }
            @Override public void onPause() { runOnUiThread(() -> { if (player != null && playerPrepared) { try { if (player.isPlaying()) player.pause(); if (miniPlay != null) miniPlay.setText("▶"); updateSessionAndNotification(false); } catch (Exception ignored) {} } }); }
            @Override public void onSkipToNext() { runOnUiThread(() -> playAt(Math.min(songs.size()-1, indexOfCurrent()+1))); }
            @Override public void onSkipToPrevious() { runOnUiThread(() -> playAt(Math.max(0, indexOfCurrent()-1))); }
            @Override public void onSeekTo(long position) {
                runOnUiThread(() -> {
                    if (player != null && playerPrepared) {
                        try {
                            int target = (int)Math.max(0L, Math.min(position, (long)player.getDuration()));
                            player.seekTo(target);
                            updateSessionAndNotification(player.isPlaying());
                        } catch (IllegalStateException ignored) {}
                    }
                });
            }
        });
        mediaSession.setActive(true);
    }

    private void updateSessionAndNotification(boolean playing) {
        if (currentSong == null || mediaSession == null) return;
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentSong.optString("title", "ZG Memory"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentSong.optString("artist", "Zubeen Garg"))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentSong.optString("album", "ZG Memory"))
                // Android SystemUI needs the total duration to draw the notification progress bar.
                .putLong(MediaMetadata.METADATA_KEY_DURATION, safePlayerDuration());
        String url = coverUrl(currentSong);
        Bitmap artwork = url.isEmpty() ? null : coverCache.get(url);
        if (artwork != null) { meta.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork); meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork); }
        mediaSession.setMetadata(meta.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        safePlayerPosition(), playing ? 1.0f : 0.0f, SystemClock.elapsedRealtime())
                .build());
        if (playing) {
            playbackProgressHandler.removeCallbacks(playbackProgressUpdater);
            playbackProgressHandler.postDelayed(playbackProgressUpdater, 1000);
        } else {
            playbackProgressHandler.removeCallbacks(playbackProgressUpdater);
        }
        showPlaybackNotification(playing, artwork);
    }

    private long safePlayerPosition() {
        try { return player != null && playerPrepared ? Math.max(0, player.getCurrentPosition()) : 0; }
        catch (Exception ignored) { return 0; }
    }

    private long safePlayerDuration() {
        try { return player != null && playerPrepared ? Math.max(0, player.getDuration()) : 0; }
        catch (Exception ignored) { return 0; }
    }

    private PendingIntent mediaAction(String action, int requestCode) {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(action);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, requestCode, i, flags);
    }

    private void showPlaybackNotification(boolean playing, Bitmap artwork) {
        if (notificationManager == null || currentSong == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentSong.optString("title", "ZG Memory"))
                .setContentText(currentSong.optString("artist", "Zubeen Garg"))
                .setContentIntent(mediaAction("open", 4110)).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(false).setOngoing(playing);
        if (artwork != null) b.setLargeIcon(artwork);
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", mediaAction("previous", 4111)).build());
        b.addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", mediaAction(playing ? "pause" : "play", 4112)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", mediaAction("next", 4113)).build());
        if (android.os.Build.VERSION.SDK_INT >= 21) b.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1,2));
        notificationManager.notify(NOTIFICATION_ID, b.build());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if ("previous".equals(a)) playAt(Math.max(0, indexOfCurrent()-1));
        else if ("next".equals(a)) playAt(Math.min(songs.size()-1, indexOfCurrent()+1));
        else if ("pause".equals(a)) { if (player != null && playerPrepared && player.isPlaying()) togglePlayback(); }
        else if ("play".equals(a)) { if (player != null && playerPrepared && !player.isPlaying()) togglePlayback(); else if (player == null && !songs.isEmpty()) playSong(songs.get(0)); }
    }

    @Override protected void onDestroy(){playbackProgressHandler.removeCallbacks(playbackProgressUpdater);if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}if(mediaSession!=null){mediaSession.setActive(false);mediaSession.release();mediaSession=null;}if(notificationManager!=null)notificationManager.cancel(NOTIFICATION_ID);super.onDestroy();}
}
