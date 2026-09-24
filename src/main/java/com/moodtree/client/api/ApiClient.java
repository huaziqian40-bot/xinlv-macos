/* 服务器 REST API 客户端（/api/v1/）。
 * 所有网络调用都在这里，UI 层不直接碰 HttpClient。
 * 注意：所有方法都是同步阻塞的，UI 层必须在后台线程调用，回到界面要用 Platform.runLater。 */
package com.moodtree.client.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moodtree.client.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public class ApiClient {

    /** 统一异常：status=0 表示网络层失败（断网/服务器没开），>0 是 HTTP 状态码 */
    public static class ApiException extends Exception {
        public final int status;
        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
        public boolean isOffline() { return status == 0; }
    }

    private final Config config;
    private final HttpClient http;

    public ApiClient(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    // ---------- 基础请求 ----------

    private HttpRequest.Builder req(String path, Map<String, String> params, boolean auth) {
        StringBuilder url = new StringBuilder(config.serverBase()).append(path);
        if (params != null && !params.isEmpty()) {
            url.append('?');
            params.forEach((k, v) -> url.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
            url.setLength(url.length() - 1);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(15));
        if (auth) b.header("Authorization", "Bearer " + config.token());
        return b;
    }

    private JsonObject send(HttpRequest request) throws ApiException {
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(0, "连不上服务器，检查网络或服务器是否已启动");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "请求被中断");
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new ApiException(resp.statusCode(), "服务器返回格式异常（HTTP " + resp.statusCode() + "）");
        }
        if (resp.statusCode() >= 400) {
            String msg = body.has("error") ? body.get("error").getAsString()
                    : "请求失败（HTTP " + resp.statusCode() + "）";
            throw new ApiException(resp.statusCode(), msg);
        }
        return body;
    }

    private JsonObject get(String path, Map<String, String> params, boolean auth) throws ApiException {
        return send(req(path, params, auth).GET().build());
    }

    private JsonObject post(String path, JsonObject body, boolean auth) throws ApiException {
        return send(req(path, null, auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
    }

    // ---------- 具体端点 ----------

    /** 探活：成功返回 true，任何失败都返回 false（客户端用它判断在线/离线） */
    public boolean ping() {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(config.serverBase() + "/api/v1/ping/"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            return http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 登录成功返回 {token, username, streak}；失败抛 ApiException(401) */
    public JsonObject login(String username, String password) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty("device", config.device());
        return post("/api/v1/login/", body, false);
    }

    /** 注册成功直接返回 {token, username, streak}（注册即登录，不用再调 login） */
    public JsonObject register(String username, String password) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty("agree", true);   // 界面上的勾选框已确认，这里如实上报
        body.addProperty("device", config.device());
        return post("/api/v1/register/", body, false);
    }

    public void logout() throws ApiException {
        post("/api/v1/logout/", new JsonObject(), true);
    }

    /** 增量拉取。since 传 null 表示全量。返回 {server_time, entries[]} */
    public JsonObject pullEntries(String since) throws ApiException {
        return get("/api/v1/sync/pull/", since == null ? null : Map.of("since", since), true);
    }

    /** 批量上传。entriesJson 是 {"entries":[...]}。返回 {saved,updated,skipped,errors,server_time} */
    public JsonObject pushEntries(JsonObject entriesJson) throws ApiException {
        return post("/api/v1/sync/push/", entriesJson, true);
    }

    /** 在线小游戏物理参数（免认证）；网络失败抛 ApiException，调用方保留本地默认值。 */
    public JsonObject gameConfig() throws ApiException {
        return get("/api/game-config/", null, false);
    }

    /** 全量推荐内容目录（离线缓存用） */
    public JsonObject catalog() throws ApiException {
        return get("/api/v1/catalog/", null, true);
    }

    /** 按心情取推荐（在线实时版；离线时用 catalog 缓存自行推荐） */
    public JsonObject recommend(String mood) throws ApiException {
        return get("/api/v1/recommend/", Map.of("mood", mood), true);
    }

    /** AI 树洞发消息。返回 {reply} 或 {crisis:true, reply, hotline}；超时放宽到 60 秒（AI 生成慢） */
    public JsonObject chat(String message) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        HttpRequest r = req("/api/v1/chat/", null, true)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(r);
    }

    public JsonObject chatHistory() throws ApiException {
        return get("/api/v1/chat/history/", null, true);
    }

    /** AI 主动发的消息轮询：{server_time, messages:[{role,content,is_weekly_essay,created_at}]}
     *  since 传 ISO8601 时间，只返回该时间之后新增的主动消息；null 表示最近 24 小时。 */
    public JsonObject chatProactive(String since) throws ApiException {
        return get("/api/v1/chat/proactive/",
                since == null ? null : Map.of("since", since), true);
    }

    public void chatClear() throws ApiException {
        post("/api/v1/chat/clear/", new JsonObject(), true);
    }

    public JsonObject profile() throws ApiException {
        return get("/api/v1/profile/", null, true);
    }

    // ---- phix 会话凭据（§7 首启引导） ----

    /** phix 登录：先取密钥材料；v2 账号发 auth_hash（服务端只存 AuthHash），v1 老账号发 password。 */
    public JsonObject phixLogin(String server, String username, String password) throws ApiException {
        JsonObject kmBody = new JsonObject();
        kmBody.addProperty("username", username);
        JsonObject km = postTo(server, "/api/v1/auth/keymaterial", kmBody);
        String algo = km.has("kdf_algo") ? km.get("kdf_algo").getAsString() : "";
        String salt = km.has("auth_salt") ? km.get("auth_salt").getAsString() : "";

        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        if ("scrypt-hkdf-v2".equals(algo) && salt != null && !salt.isEmpty()) {
            // v2 账号：服务器只存 AuthHash（HKDF(scrypt(P,auth_salt))），绝不能发口令原文
            body.addProperty("auth_hash", PhixCrypto.authHashHex(password, salt));
        } else {
            body.addProperty("password", password);   // v1 老账号兼容路径
        }
        return postTo(server, "/api/v1/auth/login", body);
    }

    /** phix 注册：显式选 v1，待客户端具备完整密钥管理后升级 v2 */
    public JsonObject phixRegister(String server, String username, String password) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty("agree", true);
        // 显式选 v1（scrypt-n15-r8-p1）：不带 auth_salt / auth_hash；
        // 待客户端具备完整密钥管理后升级 v2
        body.addProperty("kdf_algo", "scrypt-n15-r8-p1");
        return postTo(server, "/api/v1/auth/register", body);
    }

    private JsonObject postTo(String server, String path, JsonObject body) throws ApiException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(server + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(r);
    }

    /** phix 连通性验证：GET /api/v1/sync/manifest */
    public JsonObject phixVerifyManifest(String server, String token) throws ApiException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(server + "/api/v1/sync/manifest"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return send(r);
    }

    // ---- profile.pub 头像（§4 新增对象，免解密封装） ----

    /** 读取 profile.pub（明文展示对象）：GET /api/v1/sync/objects/profile.pub */
    public JsonObject getProfilePub(String server, String token) throws ApiException {
        HttpRequest r = req("/api/v1/sync/objects/profile.pub", null, false)
                .header("Authorization", "Bearer " + token)
                .GET().build();
        try {
            return send(r);
        } catch (ApiException e) {
            if (e.status == 404) return null;   // 还没有 profile.pub，非错误
            throw e;
        }
    }

    /** 上传 profile.pub（明文展示对象）：POST /api/v1/sync/objects/profile.pub */
    public JsonObject postProfilePub(String server, String token, JsonObject payload, int baseRevision) throws ApiException {
        JsonObject envelope = new JsonObject();
        envelope.add("payload", payload);
        envelope.addProperty("base_revision", baseRevision);
        HttpRequest r = HttpRequest.newBuilder(URI.create(server + "/api/v1/sync/objects/profile.pub"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(envelope.toString(), StandardCharsets.UTF_8))
                .build();
        return send(r);
    }
}
