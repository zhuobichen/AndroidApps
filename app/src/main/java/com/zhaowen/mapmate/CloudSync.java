package com.zhaowen.mapmate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 用 GitHub Gist 做记忆云备份：一个 token 即可，不需要自建服务器。 */
public class CloudSync {

    private static final String TAG = "MapMateCloud";
    private static final String API = "https://api.github.com/gists";
    private static final String FILE_NAME = "mapmate-backup.json";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Cb {
        void onDone(boolean ok, String message, String gistId, String content);
    }

    public static void upload(String token, String gistId, String payload, Cb cb) {
        new Thread(() -> {
            try {
                JSONObject file = new JSONObject().put("content", payload);
                JSONObject files = new JSONObject().put(FILE_NAME, file);
                JSONObject body = new JSONObject()
                        .put("description", "MapMate 记忆备份")
                        .put("public", false)
                        .put("files", files);

                boolean create = gistId == null || gistId.trim().isEmpty();
                Request req = new Request.Builder()
                        .url(create ? API : API + "/" + gistId.trim())
                        .addHeader("Authorization", "Bearer " + token.trim())
                        .addHeader("Accept", "application/vnd.github+json")
                        .method(create ? "POST" : "PATCH", RequestBody.create(body.toString(), JSON))
                        .build();

                try (Response resp = client.newCall(req).execute()) {
                    String text = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        done(cb, false, "HTTP " + resp.code() + "：" + shorten(text), gistId, null);
                        return;
                    }
                    String id = new JSONObject(text).optString("id", gistId);
                    done(cb, true, "已上传", id, null);
                }
            } catch (Throwable t) {
                Log.w(TAG, "upload failed", t);
                done(cb, false, "上传失败：" + t.getMessage(), gistId, null);
            }
        }, "cloud-upload").start();
    }

    public static void download(String token, String gistId, Cb cb) {
        new Thread(() -> {
            try {
                if (gistId == null || gistId.trim().isEmpty()) {
                    done(cb, false, "还没有备份 ID，先上传一次", gistId, null);
                    return;
                }
                Request req = new Request.Builder()
                        .url(API + "/" + gistId.trim())
                        .addHeader("Authorization", "Bearer " + token.trim())
                        .addHeader("Accept", "application/vnd.github+json")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String text = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        done(cb, false, "HTTP " + resp.code() + "：" + shorten(text), gistId, null);
                        return;
                    }
                    JSONObject files = new JSONObject(text).optJSONObject("files");
                    if (files == null || files.optJSONObject(FILE_NAME) == null) {
                        done(cb, false, "备份里没有 " + FILE_NAME, gistId, null);
                        return;
                    }
                    String content = files.getJSONObject(FILE_NAME).optString("content", "");
                    done(cb, true, "已下载", gistId, content);
                }
            } catch (Throwable t) {
                Log.w(TAG, "download failed", t);
                done(cb, false, "下载失败：" + t.getMessage(), gistId, null);
            }
        }, "cloud-download").start();
    }

    /** WebDAV：坚果云 / Nextcloud / 群晖，直接 PUT/GET 一个 JSON 文件 */
    /** 自签证书：按 SHA-256 指纹固定，指纹不匹配直接拒绝 */
    private static OkHttpClient pinnedClient(String pin) {
        if (pin == null || pin.trim().isEmpty()) return client;
        final String want = pin.replace(":", "").replace(" ", "").toLowerCase();
        try {
            javax.net.ssl.X509TrustManager tm = new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                    if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("无证书");
                    String got = sha256(chain[0].getEncoded());
                    if (!got.equals(want)) {
                        throw new java.security.cert.CertificateException("证书指纹不匹配: " + got);
                    }
                }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            };
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{tm}, new java.security.SecureRandom());
            return client.newBuilder()
                    .sslSocketFactory(ctx.getSocketFactory(), tm)
                    .hostnameVerifier((h, s) -> true)
                    .build();
        } catch (Throwable t) {
            Log.w(TAG, "pin setup failed: " + t.getMessage());
            return client;
        }
    }

    private static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    public static void uploadWebdav(String url, String user, String pass, String pin,
                                     String payload, Cb cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", basic(user, pass))
                        .put(RequestBody.create(payload, JSON))
                        .build();
                try (Response resp = pinnedClient(pin).newCall(req).execute()) {
                    if (resp.isSuccessful() || resp.code() == 201 || resp.code() == 204) {
                        done(cb, true, "已上传到 WebDAV", "", null);
                    } else {
                        String text = resp.body() != null ? resp.body().string() : "";
                        done(cb, false, "HTTP " + resp.code() + "：" + shorten(text), "", null);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "webdav upload failed", t);
                done(cb, false, "上传失败：" + t.getMessage(), "", null);
            }
        }, "dav-upload").start();
    }

    public static void downloadWebdav(String url, String user, String pass, String pin, Cb cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", basic(user, pass))
                        .get()
                        .build();
                try (Response resp = pinnedClient(pin).newCall(req).execute()) {
                    String text = resp.body() != null ? resp.body().string() : "";
                    if (resp.isSuccessful()) {
                        done(cb, true, "已从 WebDAV 下载", "", text);
                    } else {
                        done(cb, false, "HTTP " + resp.code() + "：" + shorten(text), "", null);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "webdav download failed", t);
                done(cb, false, "下载失败：" + t.getMessage(), "", null);
            }
        }, "dav-download").start();
    }

    private static String basic(String user, String pass) {
        String s = (user == null ? "" : user) + ":" + (pass == null ? "" : pass);
        return "Basic " + android.util.Base64.encodeToString(
                s.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    private static void done(Cb cb, boolean ok, String msg, String gistId, String content) {
        main.post(() -> cb.onDone(ok, msg, gistId, content));
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}