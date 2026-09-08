package com.stockanalysis.live.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Issues and caches the KIS access token and the WebSocket approval key.
 *
 * <p>The token lasts a day but KIS rate-limits reissuing it, so it is written to disk and reused
 * across restarts — otherwise a few restarts in a morning would lock us out of trading. The file is
 * created with owner-only permissions since it holds a bearer credential.
 */
@Component
public class KisTokenStore {

    private static final Logger log = LoggerFactory.getLogger(KisTokenStore.class);

    /** Refresh this far before real expiry so a long session never trades on an expiring token. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(30);

    private final KisProperties props;
    private final ObjectMapper mapper;
    private final Path tokenFile;

    private String accessToken;
    private LocalDateTime accessTokenExpiresAt;
    private String approvalKey;

    public KisTokenStore(KisProperties props, ObjectMapper mapper,
                         @Value("${app.data-dir:../data}") String dataDir) {
        this.props = props;
        this.mapper = mapper;
        this.tokenFile = Path.of(dataDir, ".kis-token.json");
    }

    /** A valid bearer token, reusing the cached one until it is close to expiring. */
    public synchronized String accessToken() {
        if (accessToken == null) {
            loadFromDisk();
        }
        if (accessToken != null && accessTokenExpiresAt != null
                && LocalDateTime.now().isBefore(accessTokenExpiresAt.minus(EXPIRY_MARGIN))) {
            return accessToken;
        }
        return issueAccessToken();
    }

    /** The WebSocket approval key. Cheap to reissue, so it is only cached in memory. */
    public synchronized String approvalKey() {
        if (approvalKey != null) {
            return approvalKey;
        }
        requireCredentials();
        JsonNode body = restClient().post()
                .uri("/oauth2/Approval")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "grant_type", "client_credentials",
                        "appkey", props.getAppKey(),
                        "secretkey", props.getAppSecret()))
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.hasNonNull("approval_key")) {
            throw new KisApiException("WebSocket approval_key 발급에 실패했습니다: " + body);
        }
        approvalKey = body.get("approval_key").asText();
        return approvalKey;
    }

    /** Drops the cached token so the next call re-issues — used when KIS rejects it as expired. */
    public synchronized void invalidate() {
        accessToken = null;
        accessTokenExpiresAt = null;
    }

    private String issueAccessToken() {
        requireCredentials();
        JsonNode body = restClient().post()
                .uri("/oauth2/tokenP")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "grant_type", "client_credentials",
                        "appkey", props.getAppKey(),
                        "appsecret", props.getAppSecret()))
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.hasNonNull("access_token")) {
            throw new KisApiException("접근토큰 발급에 실패했습니다: " + body);
        }
        accessToken = body.get("access_token").asText();
        long expiresIn = body.path("expires_in").asLong(86400L);
        accessTokenExpiresAt = LocalDateTime.now().plusSeconds(expiresIn);
        saveToDisk();
        log.info("KIS 접근토큰 발급 완료 (만료 {})", accessTokenExpiresAt);
        return accessToken;
    }

    private void requireCredentials() {
        if (!props.hasCredentials()) {
            throw new KisApiException(
                    "KIS 앱키가 설정되지 않았습니다. .env에 KIS_APP_KEY / KIS_APP_SECRET을 넣어주세요.");
        }
    }

    private RestClient restClient() {
        return RestClient.builder().baseUrl(props.resolvedRestBase()).build();
    }

    private void loadFromDisk() {
        if (!Files.exists(tokenFile)) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(Files.readString(tokenFile));
            // A token issued for a different app key or server is useless to us.
            if (!props.getAppKey().equals(node.path("appKey").asText(null))
                    || !props.resolvedRestBase().equals(node.path("restBase").asText(null))) {
                return;
            }
            accessToken = node.path("accessToken").asText(null);
            String expiry = node.path("expiresAt").asText(null);
            accessTokenExpiresAt = expiry == null ? null : LocalDateTime.parse(expiry);
        } catch (Exception e) {
            // A corrupt cache is not worth failing over — just reissue.
            log.warn("저장된 KIS 토큰을 읽지 못했습니다. 새로 발급합니다: {}", e.toString());
            accessToken = null;
            accessTokenExpiresAt = null;
        }
    }

    private void saveToDisk() {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("appKey", props.getAppKey());
            node.put("restBase", props.resolvedRestBase());
            node.put("accessToken", accessToken);
            node.put("expiresAt", accessTokenExpiresAt.toString());
            Files.createDirectories(tokenFile.toAbsolutePath().getParent());
            Files.writeString(tokenFile, mapper.writeValueAsString(node));
            restrictPermissions(tokenFile);
        } catch (Exception e) {
            // Losing the cache only costs us a reissue; it must never stop trading.
            log.warn("KIS 토큰을 저장하지 못했습니다: {}", e.toString());
        }
    }

    private static void restrictPermissions(Path file) throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows); nothing to tighten.
        }
    }
}
