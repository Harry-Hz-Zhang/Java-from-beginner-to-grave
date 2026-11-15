package com.harry.lab.external;

import static com.harry.lab.external.RegistryException.Reason.AUTHENTICATION_FAILED;
import static com.harry.lab.external.RegistryException.Reason.REPOSITORY_NOT_FOUND;
import static com.harry.lab.external.RegistryException.Reason.UNAVAILABLE;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * 演示一：用 JDK 自带的 HttpClient 对接 Docker Registry V2 协议。
 *
 * <p>【第一，三个超时各有分工，值取自生产配置】连接超时 5 秒，管「连不上」；
 * 请求超时 30 秒，管「连上了但不回」。两者来自 harness.image.connect-timeout
 * 与 harness.image.read-timeout，生产项目在 RunnerImageProperties 上用
 * @DefaultValue("5s")、@DefaultValue("30s") 给出默认值，并校验落在 1ms 到 5m 之间。
 * 第三个是 30 分钟的拉取总时长，属于业务层，控制一整次同步最长能跑多久，
 * 不约束单个 HTTP 请求，所以这里只作为数据保留。
 *
 * <p>【第二，401 不是终点，是换票的起点】私有仓库对匿名请求回 401，并在
 * WWW-Authenticate 头里写明 token 端点。流程是：读这个头，带 Basic 凭据换 token，
 * 再用 Authorization: Bearer 把原请求重放一次。只重放一次，因为第二次还 401
 * 说明凭据本身不对，继续重试只会把一次失败放大成一轮失败。
 *
 * <p>【第三，重定向绝不自动跟随】HttpClient 设成 Redirect.NEVER，手工跳，最多三次。
 * 原因在 blob 下载：仓库会把请求重定向到对象存储，那是另一台主机。
 * 自动跳转可能把 Authorization 一起带过去，等于把仓库令牌交给三方。
 * 这里只在目标 authority 与仓库一致时才带凭据，这是 ACR 加 OSS 回源的关键安全点。
 *
 * <p>【第四，响应体必须有上限】清单是 JSON，正常只有几 KB。设 8 MiB 上限，
 * 用 ofInputStream 加 readNBytes(上限 + 1) 读取；多读一个字节就能判断是否越界，
 * 避免对方返回超大响应时把内存吃满。
 *
 * <p>【第五，状态码翻译成领域异常】401/403、404、其余各有各的处置方式，
 * 上层按 reason() 分流，不需要解析 message 文本。
 */
public class RegistryClient {

    static final String MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";

    private static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;

    private static final Pattern PARAMETER = Pattern.compile("([A-Za-z]+)=\"([^\"]*)\"");

    private static final String BASIC =
            "Basic " + Base64.getEncoder().encodeToString("demo:demo".getBytes(StandardCharsets.UTF_8));

    private final URI origin;

    private final URI base;

    private final String repository;

    private final Timeouts timeouts;

    private final JsonMapper json = JsonMapper.builder().build();

    private final HttpClient http;

    private String authorization;

    public RegistryClient(URI origin, String repository, Timeouts timeouts) {
        this.origin = origin;
        this.repository = repository;
        this.timeouts = timeouts;
        this.base = origin.resolve("/v2/" + repository + "/");
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeouts.connect())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 拉取清单并返回 schemaVersion。 */
    public int schemaVersion(String tag) {
        Answer answer = get(base.resolve("manifests/" + tag), MANIFEST);
        requireAccess(answer.status());
        return json.readTree(answer.body()).path("schemaVersion").asInt();
    }

    /** 下载 blob；仓库通常把它重定向到对象存储，回源请求不带凭据。 */
    public String blob(String digest) {
        Answer answer = get(base.resolve("blobs/" + digest), "*/*");
        requireAccess(answer.status());
        return new String(answer.body(), StandardCharsets.UTF_8);
    }

    /** 一次请求的完整生命周期：发请求、401 换票重放、按需手工跳转。 */
    private Answer get(URI uri, String accept) {
        Answer answer = exchange(uri, accept, authorization);
        if (answer.status() == 401) {
            authorization = token(answer);
            answer = exchange(uri, accept, authorization);
        }
        for (int hops = 0; isRedirect(answer.status()); hops++) {
            if (hops == 3) throw new RegistryException(UNAVAILABLE, "镜像仓库重定向次数过多");
            String location = answer.headers().firstValue("Location")
                    .orElseThrow(() -> new RegistryException(UNAVAILABLE, "镜像仓库重定向缺少地址"));
            URI target = answer.uri().resolve(location);
            boolean sameAuthority = Objects.equals(target.getAuthority(), origin.getAuthority());
            answer = exchange(target, accept, sameAuthority ? authorization : null);
        }
        return answer;
    }

    /** 发一次请求，把响应体读进内存并校验上限。 */
    private Answer exchange(URI uri, String accept, String auth) {
        var request = HttpRequest.newBuilder(uri).timeout(timeouts.read()).header("Accept", accept);
        if (auth != null) request.header("Authorization", auth);
        try {
            var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] body = stream.readNBytes(MAX_DOCUMENT_BYTES + 1);
                if (body.length > MAX_DOCUMENT_BYTES) throw new RegistryException(UNAVAILABLE, "响应超过 8 MiB");
                return new Answer(response.statusCode(), response.headers(), response.uri(), body);
            }
        } catch (IOException ex) {
            throw new RegistryException(UNAVAILABLE, "镜像仓库连接失败", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RegistryException(UNAVAILABLE, "镜像仓库请求被中断", ex);
        }
    }

    /** 401 之后换票：Bearer 挑战去 token 端点换，其它挑战直接回 Basic。 */
    private String token(Answer challenge) {
        String header = challenge.headers().firstValue("WWW-Authenticate").orElse("");
        if (!header.regionMatches(true, 0, "Bearer", 0, 6)) return BASIC;
        var parameters = new HashMap<String, String>();
        var matcher = PARAMETER.matcher(header);
        while (matcher.find()) parameters.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        String query = "service=" + encode(parameters.getOrDefault("service", "registry")) + "&scope="
                + encode(parameters.getOrDefault("scope", "repository:" + repository + ":pull"));
        Answer answer = exchange(URI.create(parameters.getOrDefault("realm", "") + "?" + query),
                "application/json", BASIC);
        if (answer.status() == 401 || answer.status() == 403) throw new RegistryException(AUTHENTICATION_FAILED, "镜像仓库鉴权失败，请检查用户名和密码");
        if (answer.status() != 200) throw new RegistryException(UNAVAILABLE, "镜像仓库鉴权服务暂不可用");
        String value = json.readTree(answer.body()).path("token").asString("");
        if (value.isBlank()) throw new RegistryException(UNAVAILABLE, "鉴权服务未返回 token");
        return "Bearer " + value;
    }

    private static void requireAccess(int status) {
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403) throw new RegistryException(AUTHENTICATION_FAILED, "鉴权失败，请检查用户名、密码和仓库地址");
        if (status == 404) throw new RegistryException(REPOSITORY_NOT_FOUND, "镜像仓库不存在或该标签不存在");
        throw new RegistryException(UNAVAILABLE, "镜像仓库暂不可用（HTTP " + status + "）");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 三个超时的取值来源，生产对应 RunnerImageProperties 的同名字段。 */
    public record Timeouts(Duration connect, Duration read, Duration pull) {
    }

    private record Answer(int status, HttpHeaders headers, URI uri, byte[] body) {
    }
}
