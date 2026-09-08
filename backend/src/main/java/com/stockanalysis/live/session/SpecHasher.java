package com.stockanalysis.live.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.stockanalysis.backtest.spec.StrategySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@link StrategySpec}의 지문을 뜹니다. 지금 저장된 로직이 아직 백테스트한 그 로직인지
 * 가릴 수 있도록. 이게 없으면 사용자가 전략을 검증받은 뒤 손절을 조용히 넓혀놓고, 더는 그
 * 전략을 설명하지 못하는 백테스트를 근거로 실계좌를 계속 돌릴 수 있습니다.
 *
 * <p>속성 순서를 알파벳순으로 강제해서, 해시가 Jackson의 필드 순서가 아니라 규칙에 의존하게
 * 합니다. 전략의 {@code name}은 규칙 내용만 해싱해 제외합니다 — 이름 변경은 규칙 변경이
 * 아니기 때문입니다.
 */
public final class SpecHasher {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private SpecHasher() {
    }

    /** 정규화된 규칙 JSON의 SHA-256을 16진수로. 절대 null이 아닙니다. */
    public static String hash(StrategySpec spec) {
        if (spec == null) {
            return "";
        }
        try {
            StrategySpec copy = shallowCopyWithoutName(spec);
            byte[] json = CANONICAL.writeValueAsBytes(copy);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        } catch (Exception e) {
            throw new IllegalStateException("전략 스펙을 직렬화하지 못했습니다.", e);
        }
    }

    public static boolean matches(StrategySpec spec, String expectedHash) {
        return expectedHash != null && !expectedHash.isBlank() && hash(spec).equals(expectedHash);
    }

    /**
     * 해시는 규칙만 덮습니다. 호출자의 스펙을 바꾸는 대신 복사본을 만드는데, 그 스펙이 관리
     * 상태의 JPA 필드일 수 있기 때문입니다 — 거기에 쓰면 엉뚱한 UPDATE가 나갑니다.
     */
    private static StrategySpec shallowCopyWithoutName(StrategySpec spec) throws Exception {
        StrategySpec copy = CANONICAL.readValue(CANONICAL.writeValueAsBytes(spec), StrategySpec.class);
        copy.setName(null);
        return copy;
    }
}
