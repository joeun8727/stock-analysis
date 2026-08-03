# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 저장소에서 작업할 때 참고하는 안내서입니다.

## 이 프로젝트는

주식 **단타(스캘핑) 백테스팅** 시스템입니다. 사용자는 코딩 없이 **규칙 조합**(지표 + 조건)으로 매매 로직을 구성하고, 분봉 엑셀 데이터(3분봉·1분봉)로 백테스트해서 지정한 투자금액 기준의 **성공/실패율 + 실패 분석 + 원화 수익금 + 자동 생성 진단**을 받습니다. LLM(Claude)도 동일한 규칙 포맷으로 전략을 추천합니다.

엑셀 파싱에 `com.github.pjfanning:excel-streaming-reader`를 쓰는 이유는 SAX 방식이라 14 MB / 16만 행 파일을 저메모리로 처리하기 때문입니다 — 일반 POI로 바꾸면 업로드에서 메모리가 터집니다.

전체 계획과 단계 구분은 `/Users/eunyum/.claude/plans/optimized-yawning-rain.md`에 있습니다.

깊은 내용은 작업하는 폴더에서 자동으로 로드됩니다: 데이터 모델·백테스트 엔진·실행 저장·LLM 추천·REST API는 `backend/CLAUDE.md`, 화면 상세는 `frontend/CLAUDE.md`.

## 명령어

표준 Gradle/npm 명령을 씁니다(`backend/`와 `frontend/`에서 각각). 셋업 절차 전체는 `README.md` 참고. 비자명한 것만:

- **`./gradlew test`는 DB가 필요 없습니다** — 파서와 엔진만 쓰는 독립 테스트입니다. `./gradlew bootRun`은 MySQL이 필요하고(`docker compose up -d mysql`), Flyway가 스키마를 자동 마이그레이션합니다.
- `npm run dev`는 백엔드가 :8080에 떠 있어야 동작합니다.
- `docker compose up`은 전체 스택(mysql + backend + frontend)을 띄웁니다.

**compose 설정값은 저장소 루트의 `.env`에 있습니다**(git 제외). 새로 받은 환경에서는 `cp .env.example .env` 후 값을 채우세요 — 없으면 compose가 `required variable ... is missing a value`로 즉시 실패합니다(빈 비밀번호로 조용히 뜨지 않도록 `${VAR:?}` 를 씁니다). `docker-compose.yml`에 자격증명을 다시 적어 넣지 마세요. 백엔드의 `DB_NAME/DB_USER/DB_PASSWORD`는 compose에서 `MYSQL_*` 값을 그대로 넘겨받으므로 비밀번호는 `.env`에 **한 번만** 씁니다.

DB 접속 정보는 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` 환경변수로 읽습니다 (`bootRun`처럼 compose 밖에서 띄울 때의 기본값은 `stock`/`stock` @ `localhost:3306/stock_analysis`). 프론트엔드는 `NEXT_PUBLIC_API_BASE`(기본 `http://localhost:8080`)로 백엔드를 호출하고, CORS는 `localhost:3000`을 허용합니다 (`config/WebConfig.java`).

## 핵심 규약 (전 계층 공통 — 어기면 조용히 데이터가 틀어집니다)
- **원본 엑셀의 봉은 최신순**입니다. `BarSeries`는 생성 시 항상 오름차순으로 정렬합니다.
- 지표는 엑셀 **열 위치**로 바인딩합니다(헤더에 맨숫자가 중복 사용됨): `0 날짜, 1 시각, 2 시가, 3 고가, 4 저가, 5 종가, 6 MA5, 7 MA10, 8 MA20, 9 MA60, 10 거래량, 11 volMA5, 12 volMA20, 13 volMA60, 14 volMA120`.
- **이동평균 뒤의 숫자는 봉 개수가 아니라 분입니다** — MA20은 20**분** 이동평균이고 volMA120은 120**분** 평균입니다. 원본이 3분봉이든 1분봉이든 같은 값을 뜻하므로, 봉 길이가 달라져도 지표의 의미는 변하지 않습니다. 봉 길이에 걸리는 건 `ExitSpec.maxHoldBars`(봉 개수) 하나뿐입니다.
- **봉 길이는 데이터셋마다 다릅니다** — 같은 종목을 3분봉과 1분봉으로 따로 업로드할 수 있습니다. 업로드 시 파싱한 타임스탬프에서 추론해(`BarSeries.inferIntervalMinutes`) `dataset.bar_interval_minutes`에 넣고, UI와 LLM 프롬프트가 이 값으로 "1봉 = 몇 분"을 설명합니다. **엔진은 이 값을 보지 않습니다**(봉을 세기만 함).
- 데이터 배치: `data/futures/`(선물), `data/stock/etf/`(레버리지/인버스 쌍), `data/stock/normal/`(단일 종목).
- **날짜·시각 바인딩 함정**: 모든 DATETIME 컬럼은 순간(instant)이 아니라 **KST 벽시계**를 담습니다. raw JDBC에서는 `LocalDateTime`을 **직접** 바인딩하고(`ps.setObject` / `rs.getObject(.., LocalDateTime.class)`) 절대 `java.sql.Timestamp`를 거치지 마세요 — `PriceBarWriter`/`DatasetLoader` 참고. JPA 엔티티는 그렇게 할 수 없으므로(Hibernate가 `LocalDateTime`을 항상 `Timestamp`로 보냄) JDBC url에 **`preserveInstants=false`** 를 넣어 드라이버의 타임존 변환을 막았습니다. 이게 없으면 JPA 컬럼(`dataset.from_ts/to_ts/uploaded_at`, `backtest_run.created_at`, `strategy.created_at/updated_at`)이 raw JDBC 컬럼(`price_bar.ts`, `trade.*_ts`)보다 9시간 뒤처집니다 — 읽기 시프트가 쓰기 시프트를 상쇄해 API로는 안 보이지만, DB 안에서는 틀린 값이고 두 그룹을 조인하는 SQL에서도 틀립니다.

## 데이터
**엑셀 원본은 저장소에 없습니다** (`data/**/*.xlsx`는 git 제외 — 종목당 14 MB). 새로 clone하면 `data/`는 비어 있고, 데이터는 업로드 화면에서 넣습니다. 개발자 로컬에는 시드 3개가 있습니다 (3분봉 OHLCV + 이동평균, 각각 약 14만~16만 행): KOSPI200 선물, KODEX 레버리지, KODEX200 선물인버스. 파일이 있을 때 빠르게 확인하려면 `read_only=True`로 `python3 -c "import openpyxl; ..."` 를 쓰세요.
