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
 * Fingerprints a {@link StrategySpec} so we can tell whether the logic now saved is still the logic
 * that was backtested. Without this, a user could verify a strategy, quietly widen its stop-loss,
 * and keep trading a real account on the strength of a backtest that no longer describes it.
 *
 * <p>Property order is forced to be alphabetical so the hash depends on the rules, not on Jackson's
 * field ordering. The strategy's {@code name} is excluded by hashing the rule content only — a
 * rename is not a rule change.
 */
public final class SpecHasher {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private SpecHasher() {
    }

    /** SHA-256 of the canonical rule JSON, hex-encoded. Never null. */
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
     * The hash covers rules only. A copy is made rather than mutating the caller's spec, which may
     * be a managed JPA field — writing to it would trigger a stray UPDATE.
     */
    private static StrategySpec shallowCopyWithoutName(StrategySpec spec) throws Exception {
        StrategySpec copy = CANONICAL.readValue(CANONICAL.writeValueAsBytes(spec), StrategySpec.class);
        copy.setName(null);
        return copy;
    }
}
