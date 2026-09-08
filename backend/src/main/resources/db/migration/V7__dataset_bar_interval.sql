-- 봉 길이는 시스템이 아니라 업로드한 파일의 성질입니다: 같은 종목을 1분봉으로 다시 올려
-- 별도 데이터셋/그룹으로 둘 수 있습니다. "봉"을 말하는 모든 것(maxHoldBars, 이동평균,
-- LLM 프롬프트, UI 문구)이 이 값에 따라 다른 길이의 시간을 뜻합니다. 엔진 자체는 봉 길이를
-- 모릅니다 — 봉을 세기만 합니다.
--
-- 업로드가 파싱한 타임스탬프에서 추론합니다(BarSeries.inferIntervalMinutes). 이 마이그레이션
-- 이전에 올라간 데이터셋은 전부 3분봉이었고, 그게 컬럼 기본값이기도 합니다.
ALTER TABLE dataset ADD COLUMN bar_interval_minutes INT NOT NULL DEFAULT 3 AFTER bar_count;
