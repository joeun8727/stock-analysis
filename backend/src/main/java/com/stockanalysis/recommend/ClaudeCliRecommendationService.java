package com.stockanalysis.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.domain.Dataset;
import com.stockanalysis.domain.DatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 로컬 Claude Code CLI를 헤드리스로 호출해서 전략 추천을 만듭니다
 * ({@code claude -p <prompt> --output-format json}). 사용자의 기존 Claude Code 세션을 쓰므로
 * API 키가 필요 없습니다. 대신 {@code claude}가 설치·인증된 환경에서 백엔드가 돌아야 합니다
 * (로컬 개발 환경이지 Docker 이미지가 아닙니다).
 */
@Service
public class ClaudeCliRecommendationService implements RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliRecommendationService.class);

    private final DatasetRepository datasetRepo;
    private final DatasetStats datasetStats;
    private final ObjectMapper mapper;
    private final String claudeBin;
    private final int timeoutSeconds;

    public ClaudeCliRecommendationService(DatasetRepository datasetRepo,
                                          DatasetStats datasetStats,
                                          ObjectMapper mapper,
                                          @Value("${app.claude-bin}") String claudeBin,
                                          @Value("${app.recommend-timeout-seconds}") int timeoutSeconds) {
        this.datasetRepo = datasetRepo;
        this.datasetStats = datasetStats;
        this.mapper = mapper;
        this.claudeBin = claudeBin;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public List<StrategySpec> recommend(long datasetId) {
        Dataset ds = datasetRepo.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + datasetId));
        DatasetStats.Stats stats = datasetStats.compute(ds);
        String prompt = buildPrompt(stats);
        String answer = runClaude(prompt);
        List<StrategySpec> specs = parseSpecs(answer);
        if (specs.isEmpty()) {
            throw new IllegalStateException("LLM이 유효한 전략을 생성하지 못했습니다. 다시 시도해주세요.");
        }
        return specs;
    }

    private String buildPrompt(DatasetStats.Stats s) {
        return """
                당신은 한국 주식 단타(스캘핑) 전략 설계자입니다.
                아래 종목의 %d분봉 통계를 참고하여, 백테스트 가능한 매매 전략 3~5개를 설계하세요.
                이 데이터는 1봉이 %d분입니다 — maxHoldBars는 봉 개수이므로 10을 넣으면 %d분 보유입니다.
                (지표의 MA5/MA10/MA20/MA60과 VOL_MA5/20/60/120은 봉 개수가 아니라 분 단위 이동평균이라
                 봉 길이와 무관하게 항상 5분/10분/20분/60분, 5분/20분/60분/120분 평균입니다.)

                [종목 통계]
                - 종목: %s (%s / %s)
                - 봉 수: %d개 (거래일 %d일), 기간 %s ~ %s
                - 종가: 최저 %.2f, 최고 %.2f, 평균 %.2f
                - 평균 봉 변동폭(고저/종가): %.3f%%
                - 평균 거래량: %.0f

                [규칙 JSON 스키마]
                각 전략은 다음 형태의 객체입니다:
                {
                  "name": "전략 이름(한글, 접근법이 드러나게)",
                  "source": "LLM",
                  "position": "LONG",
                  "entry": { "logic": "AND"|"OR", "conditions": [ <조건>, ... ] },
                  "exit": {
                    "takeProfitPct": <숫자 또는 null>,
                    "stopLossPct": <숫자 또는 null>,
                    "maxHoldBars": <정수 또는 null>,
                    "closeAtDayEnd": true,
                    "logic": "OR",
                    "conditions": [ <조건>, ... ],
                    "bands": [ <시간대>, ... ]
                  }
                }
                <시간대> = { "startTime": "09:00", "endTime": "10:00",
                            "takeProfitPct": <숫자 또는 null>, "stopLossPct": <숫자 또는 null> }
                  · 그 시간대([시작, 종료))에만 적용되는 익절/손절. 보유 중 시각이 넘어가면 그 자리에서 값이 바뀝니다.
                  · null이면 위의 기본 takeProfitPct/stopLossPct를 사용. 필요 없으면 "bands": [] 로 두세요.
                <조건> = { "left": <피연산자>, "op": <연산자>, "right": <피연산자> }
                <피연산자> = { "indicator": <지표명> }  또는  { "const": <숫자> }
                지표명: OPEN, HIGH, LOW, CLOSE, MA5, MA10, MA20, MA60, VOLUME, VOL_MA5, VOL_MA20, VOL_MA60, VOL_MA120
                연산자: GT, GTE, LT, LTE, EQ, CROSS_ABOVE(상향돌파), CROSS_BELOW(하향돌파)

                [요구사항]
                - entry.conditions는 최소 1개.
                - 단타이므로 익절/손절은 대략 0.3%%~2%% 범위, closeAtDayEnd=true 권장.
                - 전략마다 접근을 다양하게: 추세추종(골든크로스), 돌파(전고/거래량), 역추세, 거래량 급증 등.
                - 이 종목의 평균 변동폭에 맞춰 익절/손절 폭을 현실적으로 잡을 것.
                - 시간대별 폭이 필요한 전략(장 초반 변동성 활용 등)에만 bands를 쓰고, 나머지는 빈 배열로 둘 것.
                - 반드시 JSON 배열만 출력. 설명, 마크다운, 코드펜스 없이 대괄호 배열 [ ... ] 만 출력.
                """.formatted(
                s.barIntervalMinutes(), s.barIntervalMinutes(), s.barIntervalMinutes() * 10,
                s.symbol(), s.market(), s.kind(),
                s.barCount(), s.tradingDays(), s.fromTs(), s.toTs(),
                s.minClose(), s.maxClose(), s.avgClose(),
                s.avgRangePct(), s.avgVolume());
    }

    private String runClaude(String prompt) {
        IOException lastIo = null;
        for (String bin : candidateBins()) {
            ProcessBuilder pb = new ProcessBuilder(bin, "-p", prompt, "--output-format", "json");
            pb.redirectErrorStream(false);
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                lastIo = e; // 이 경로에 바이너리가 없습니다. 다음 후보를 시도합니다
                continue;
            }
            ExecutorService reader = Executors.newSingleThreadExecutor();
            try {
                process.getOutputStream().close();
                Future<String> out = reader.submit(
                        () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                String stdout;
                try {
                    stdout = out.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    process.destroyForcibly();
                    throw new IllegalStateException("LLM 추천 시간 초과(" + timeoutSeconds + "초).");
                }
                process.waitFor(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0) {
                    String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException("claude CLI 오류: " + firstLine(err));
                }
                return extractResultText(stdout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LLM 추천이 중단되었습니다.");
            } catch (java.util.concurrent.ExecutionException | IOException e) {
                throw new IllegalStateException("claude CLI 실행 중 오류: " + e.getMessage());
            } finally {
                reader.shutdownNow();
            }
        }
        throw new IllegalStateException("claude CLI를 찾을 수 없습니다. Claude Code가 설치·로그인된 환경에서 "
                + "백엔드를 실행하거나 CLAUDE_BIN 환경변수에 절대경로를 지정하세요. (" + lastIo + ")");
    }

    /** 설정된 바이너리를 먼저, 그다음 흔한 설치 위치를 봅니다 — PATH가 비어도 동작하도록. */
    private List<String> candidateBins() {
        List<String> bins = new ArrayList<>();
        bins.add(claudeBin);
        String home = System.getProperty("user.home");
        for (String p : List.of(home + "/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude")) {
            if (!bins.contains(p) && Files.isExecutable(Path.of(p))) {
                bins.add(p);
            }
        }
        return bins;
    }

    /** CLI가 답을 JSON 봉투에 싸서 주므로, 모델이 쓴 텍스트를 꺼냅니다. */
    private String extractResultText(String stdout) {
        try {
            JsonNode env = mapper.readTree(stdout);
            if (env.path("is_error").asBoolean(false)) {
                throw new IllegalStateException("claude CLI가 오류를 반환했습니다: " + env.path("result").asText(""));
            }
            if (env.has("result")) {
                return env.get("result").asText();
            }
        } catch (IOException ignore) {
            // 봉투가 아닙니다. 아래로 내려가 원문 그대로 씁니다
        }
        return stdout;
    }

    /** 답변 텍스트에서 JSON 배열을 꺼내(코드펜스·설명문이 섞여도 견딤) 스펙으로 매핑합니다. */
    private List<StrategySpec> parseSpecs(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("LLM 응답에서 JSON 배열을 찾지 못함: {}", firstLine(text));
            return List.of();
        }
        String json = text.substring(start, end + 1);
        List<StrategySpec> valid = new ArrayList<>();
        try {
            StrategySpec[] parsed = mapper.readValue(json, StrategySpec[].class);
            for (StrategySpec spec : parsed) {
                if (spec == null || spec.getEntry() == null || spec.getEntry().isEmpty()) {
                    continue;
                }
                spec.setSource("LLM");
                if (spec.getName() == null || spec.getName().isBlank()) {
                    spec.setName("LLM 추천 " + (valid.size() + 1));
                }
                valid.add(spec);
                if (valid.size() >= 5) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("LLM 응답 JSON 파싱 실패: " + e.getMessage());
        }
        return valid;
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "(빈 응답)";
        }
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 300 ? line.substring(0, 300) : line;
    }
}
