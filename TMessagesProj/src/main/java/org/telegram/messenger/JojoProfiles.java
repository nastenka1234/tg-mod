package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JojoGram-only profile layer: name color, name icon, name status.
 * Data lives in the JojoGram worker, never on Telegram servers. It is re-applied every time a user
 * passes through MessagesController.putUser, so Telegram's own updates cannot erase it.
 */
public class JojoProfiles {

    private static final long POSITIVE_TTL = 10 * 60 * 1000L;
    private static final long NEGATIVE_TTL = 30 * 60 * 1000L;
    private static final int BATCH = 40;

    private static class Profile {
        int nameColor = -1;
        long iconEmojiId;
        long statusEmojiId;
        String gifts = "[]";
    }

    private static class Entry {
        Profile p; // null = this user has no JojoGram profile
        long at;
    }

    private static final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
    private static final Set<Long> pending = new HashSet<>();
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static volatile boolean loaded;
    private static volatile boolean flushScheduled;
    private static volatile long failUntil;
    private static boolean verifyHintShown;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("jojo_profiles", Context.MODE_PRIVATE);
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            String raw = prefs().getString("cache", null);
            if (raw == null) return;
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject j = o.getJSONObject(k);
                Entry e = new Entry();
                e.at = 0; // cached copy is shown immediately but refreshed soon
                Profile p = new Profile();
                p.nameColor = j.optInt("c", -1);
                p.iconEmojiId = j.optLong("i", 0);
                p.statusEmojiId = j.optLong("s", 0);
                p.gifts = j.optString("g", "[]");
                e.p = p;
                cache.put(Long.parseLong(k), e);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void persist() {
        io.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                for (java.util.Map.Entry<Long, Entry> en : cache.entrySet()) {
                    Profile p = en.getValue().p;
                    if (p == null) continue;
                    JSONObject j = new JSONObject();
                    j.put("c", p.nameColor);
                    j.put("i", p.iconEmojiId);
                    j.put("s", p.statusEmojiId);
                    j.put("g", p.gifts);
                    o.put(String.valueOf(en.getKey()), j);
                }
                prefs().edit().putString("cache", o.toString()).apply();
            } catch (Throwable ignore) {
            }
        });
    }

    /** Called for every user that enters MessagesController.putUser. Must stay cheap and never throw. */
    public static void onUser(int account, TLRPC.User user) {
        try {
            if (user == null || user.id <= 0 || !JojoConfig.enabled()) return;
            load();
            applyTo(user);
            if (user.bot || user.deleted || user.id == 777000) return;
            Entry e = cache.get(user.id);
            long ttl = e != null && e.p == null ? NEGATIVE_TTL : POSITIVE_TTL;
            if (e == null || System.currentTimeMillis() - e.at > ttl) {
                want(user.id);
            }
        } catch (Throwable ignore) {
        }
    }

    /** Writes the cached JojoGram profile into the user object. Returns true if something changed. */
    private static boolean applyTo(TLRPC.User u) {
        Entry e = cache.get(u.id);
        if (e == null || e.p == null) return false;
        Profile p = e.p;
        boolean changed = false;
        if (p.nameColor >= 0) {
            if (!(u.color instanceof TLRPC.TL_peerColor)) {
                u.color = new TLRPC.TL_peerColor();
                changed = true;
            }
            if ((u.color.flags & 1) == 0 || u.color.color != p.nameColor) {
                u.color.flags |= 1;
                u.color.color = p.nameColor;
                changed = true;
            }
            if (p.iconEmojiId != 0 && ((u.color.flags & 2) == 0 || u.color.background_emoji_id != p.iconEmojiId)) {
                u.color.flags |= 2;
                u.color.background_emoji_id = p.iconEmojiId;
                changed = true;
            }
            u.flags2 |= 256;
        }
        if (p.statusEmojiId != 0) {
            long cur = 0;
            if (u.emoji_status instanceof TLRPC.TL_emojiStatus) {
                cur = ((TLRPC.TL_emojiStatus) u.emoji_status).document_id;
            }
            if (cur != p.statusEmojiId) {
                TLRPC.TL_emojiStatus s = new TLRPC.TL_emojiStatus();
                s.document_id = p.statusEmojiId;
                u.emoji_status = s;
                u.flags |= 1073741824;
                changed = true;
            }
        }
        return changed;
    }

    // ---------------- fetching other users ----------------

    private static void want(long id) {
        if (System.currentTimeMillis() < failUntil) return;
        synchronized (pending) {
            pending.add(id);
        }
        if (!flushScheduled) {
            flushScheduled = true;
            AndroidUtilities.runOnUIThread(JojoProfiles::flush, 400);
        }
    }

    private static void flush() {
        flushScheduled = false;
        final ArrayList<Long> ids = new ArrayList<>();
        synchronized (pending) {
            Iterator<Long> it = pending.iterator();
            while (it.hasNext() && ids.size() < BATCH) {
                ids.add(it.next());
                it.remove();
            }
            if (!pending.isEmpty() && !flushScheduled) {
                flushScheduled = true;
                AndroidUtilities.runOnUIThread(JojoProfiles::flush, 400);
            }
        }
        if (ids.isEmpty()) return;
        io.execute(() -> fetchBatch(ids));
    }

    private static void fetchBatch(ArrayList<Long> ids) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(ids.get(i));
            }
            String body = http("GET", "/batch?ids=" + sb, null, null);
            if (body == null) {
                failUntil = System.currentTimeMillis() + 60 * 1000L;
                return;
            }
            JSONObject all = new JSONObject(body);
            final ArrayList<Long> changedIds = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Long id : ids) {
                JSONObject j = all.optJSONObject(String.valueOf(id));
                Entry e = new Entry();
                e.at = now;
                if (j != null) {
                    Profile p = new Profile();
                    p.nameColor = j.isNull("nameColor") ? -1 : j.optInt("nameColor", -1);
                    p.iconEmojiId = parseId(j.opt("iconEmojiId"));
                    p.statusEmojiId = parseId(j.opt("statusEmojiId"));
                    JSONArray g = j.optJSONArray("gifts");
                    p.gifts = g != null ? g.toString() : "[]";
                    e.p = p;
                }
                cache.put(id, e);
                changedIds.add(id);
            }
            persist();
            AndroidUtilities.runOnUIThread(() -> applyToLoaded(changedIds));
        } catch (Throwable t) {
            failUntil = System.currentTimeMillis() + 60 * 1000L;
        }
    }

    private static long parseId(Object o) {
        if (o == null || JSONObject.NULL.equals(o)) return 0;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** UI thread: users already on screen get their JojoGram profile and the views refresh. */
    private static void applyToLoaded(ArrayList<Long> ids) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            boolean any = false;
            for (Long id : ids) {
                TLRPC.User u = MessagesController.getInstance(a).getUser(id);
                if (u != null && applyTo(u)) {
                    any = true;
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.userEmojiStatusUpdated, u);
                }
            }
            if (any) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces,
                        MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_EMOJI_STATUS);
            }
        }
    }

    // ---------------- saving my own profile ----------------

    private static long myId(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static String token(long id) {
        SharedPreferences p = prefs();
        String k = "tok_" + id;
        String t = p.getString(k, null);
        if (t == null) {
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 32; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            t = sb.toString();
            p.edit().putString(k, t).apply();
        }
        return t;
    }

    private static Profile mine(long id) {
        Entry e = cache.get(id);
        Profile p = new Profile();
        if (e != null && e.p != null) {
            p.nameColor = e.p.nameColor;
            p.iconEmojiId = e.p.iconEmojiId;
            p.statusEmojiId = e.p.statusEmojiId;
            p.gifts = e.p.gifts;
        }
        return p;
    }

    /** Called after the user saved name color / icon in Telegram's own color screen. */
    public static void mirrorMine(int account, TLRPC.User me) {
        try {
            if (me == null || !JojoConfig.enabled()) return;
            load();
            Profile p = mine(me.id);
            if (me.color instanceof TLRPC.TL_peerColor && (me.color.flags & 1) != 0) {
                p.nameColor = me.color.color;
                p.iconEmojiId = (me.color.flags & 2) != 0 ? me.color.background_emoji_id : 0;
            }
            store(account, me.id, p);
        } catch (Throwable ignore) {
        }
    }

    /** Called when the user picks / clears an emoji status for himself. */
    public static void mirrorStatus(int account, TLRPC.EmojiStatus status) {
        try {
            if (!JojoConfig.enabled()) return;
            load();
            long id = myId(account);
            if (id <= 0) return;
            Profile p = mine(id);
            if (status instanceof TLRPC.TL_emojiStatus) {
                p.statusEmojiId = ((TLRPC.TL_emojiStatus) status).document_id;
            } else if (status instanceof TLRPC.TL_emojiStatusEmpty) {
                p.statusEmojiId = 0;
            } else {
                return; // collectibles etc. are real Telegram things, not ours
            }
            store(account, id, p);
        } catch (Throwable ignore) {
        }
    }

    private static void store(int account, long id, Profile p) {
        Entry e = new Entry();
        e.p = p;
        e.at = System.currentTimeMillis();
        cache.put(id, e);
        persist();
        scheduleSave(account, id, 1500);
    }

    private static final Set<Long> saving = new HashSet<>();

    private static void scheduleSave(int account, long id, long delay) {
        synchronized (saving) {
            if (!saving.add(id)) return; // a save is already queued, it will send the latest data
        }
        AndroidUtilities.runOnUIThread(() -> io.execute(() -> {
            synchronized (saving) {
                saving.remove(id);
            }
            save(account, id);
        }), delay);
    }

    private static void save(int account, long id) {
        try {
            Entry e = cache.get(id);
            if (e == null || e.p == null) return;
            Profile p = e.p;
            JSONObject o = new JSONObject();
            o.put("nameColor", p.nameColor >= 0 ? p.nameColor : JSONObject.NULL);
            o.put("iconEmojiId", p.iconEmojiId != 0 ? String.valueOf(p.iconEmojiId) : JSONObject.NULL);
            o.put("statusEmojiId", p.statusEmojiId != 0 ? String.valueOf(p.statusEmojiId) : JSONObject.NULL);
            o.put("gifts", new JSONArray(p.gifts));
            final int[] code = new int[1];
            final String[] reply = new String[1];
            String res = httpFull("POST", "/p/" + id, o.toString(), token(id), code, reply);
            if (code[0] == 200) return;
            String err = reply[0] == null ? "" : reply[0];
            if (code[0] == 403 && err.contains("not_verified")) {
                AndroidUtilities.runOnUIThread(() -> verifyHint(id));
            } else if (code[0] == 403 && (err.contains("banned") || err.contains("suspended"))) {
                AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                        "JojoGram: your account is restricted", Toast.LENGTH_LONG).show());
            } else if (code[0] == 429 || code[0] == -1 || code[0] >= 500) {
                scheduleSave(account, id, 65 * 1000L); // try again later
            }
        } catch (Throwable ignore) {
        }
    }

    private static void verifyHint(long id) {
        if (verifyHintShown) return;
        verifyHintShown = true;
        Context c = ApplicationLoader.applicationContext;
        if (JojoConfig.BOT_USERNAME == null || JojoConfig.BOT_USERNAME.isEmpty()) {
            Toast.makeText(c, "JojoGram: verification bot is not configured", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(c, "JojoGram: press START in the bot to verify your account", Toast.LENGTH_LONG).show();
        try {
            Browser.openUrl(c, "https://t.me/" + JojoConfig.BOT_USERNAME + "?start=" + token(id));
        } catch (Throwable ignore) {
        }
    }

    // ---------------- tiny HTTP client ----------------

    private static String http(String method, String path, String body, String token) {
        int[] code = new int[1];
        String[] reply = new String[1];
        httpFull(method, path, body, token, code, reply);
        return code[0] == 200 ? reply[0] : null;
    }

    private static String httpFull(String method, String path, String body, String token, int[] code, String[] reply) {
        HttpURLConnection c = null;
        code[0] = -1;
        try {
            c = (HttpURLConnection) new URL(JojoConfig.WORKER_URL + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("accept", "application/json");
            if (token != null) c.setRequestProperty("x-token", token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            code[0] = c.getResponseCode();
            InputStream is = code[0] >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
            }
            reply[0] = sb.toString();
            return reply[0];
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
