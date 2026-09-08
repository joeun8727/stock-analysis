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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * KIS 접근토큰과 WebSocket 승인키를 발급하고 캐시합니다.
 *
 * <p>토큰 자체는 하루 동안 유효하지만 KIS가 재발급 횟수를 제한하기 때문에, 디스크에 써 두고
 * 재기동해도 다시 씁니다 — 그러지 않으면 오전에 몇 번 재기동하는 것만으로 그날 매매가
 * 막힙니다. 파일에는 베어러 자격증명이 들어가므로 소유자만 읽을 수 있게 만듭니다.
 */
@Component
public class KisTokenStore {

    private static final Logger log = LoggerFactory.getLogger(KisTokenStore.class);

    /** 실제 만료보다 이만큼 앞서 갱신합니다 — 긴 세션이 만료 직전 토큰으로 매매하지 않도록. */
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

    /** 유효한 베어러 토큰. 만료가 가까워지기 전까지는 캐시된 것을 그대로 씁니다. */
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

    /** WebSocket 승인키. 재발급이 부담 없어서 메모리에만 캐시합니다. */
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

    /** 캐시된 토큰을 버려 다음 호출에서 재발급하게 합니다 — KIS가 만료로 거절했을 때 씁니다. */
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
            // 다른 앱키나 다른 서버로 발급받은 토큰은 우리에게 쓸모가 없습니다.
            if (!props.getAppKey().equals(node.path("appKey").asText(null))
                    || !props.resolvedRestBase().equals(node.path("restBase").asText(null))) {
                return;
            }
            accessToken = node.path("accessToken").asText(null);
            String expiry = node.path("expiresAt").asText(null);
            accessTokenExpiresAt = expiry == null ? null : LocalDateTime.parse(expiry);
        } catch (Exception e) {
            // 캐시가 깨진 것 때문에 실패로 처리할 이유는 없습니다 — 다시 발급받으면 됩니다.
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
            // 토큰을 쓰기 **전에** 소유자 전용 권한으로 파일을 만듭니다. 먼저 쓰고 나서 권한을
            // 조이면 그 사이에 같은 머신의 다른 계정이 읽을 수 있는 창이 열립니다.
            createOwnerOnly(tokenFile);
            Files.writeString(tokenFile, mapper.writeValueAsString(node));
        } catch (Exception e) {
            // 캐시를 잃어도 재발급 한 번이면 되는 일이라, 이것 때문에 매매가 멈추면 안 됩니다.
            log.warn("KIS 토큰을 저장하지 못했습니다: {}", e.toString());
        }
    }

    /** 파일을 소유자만 읽고 쓸 수 있는 상태로 준비합니다(이미 있으면 권한만 조입니다). */
    private static void createOwnerOnly(Path file) throws IOException {
        Set<PosixFilePermission> ownerOnly =
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            if (Files.exists(file)) {
                Files.setPosixFilePermissions(file, ownerOnly);
            } else {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(ownerOnly));
            }
        } catch (UnsupportedOperationException e) {
            // POSIX가 아닌 파일시스템(Windows) — 여기서 조일 수 있는 것이 없습니다.
        }
    }
}
