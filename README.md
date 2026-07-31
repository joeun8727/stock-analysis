# 📈 단타 백테스터

주식 **단타(스캘핑) 백테스팅** 시스템입니다. 코딩 없이 **규칙 조합**(지표 + 조건)으로 매매 로직을 만들고, 3분봉 엑셀 데이터로 백테스트해서 지정한 투자금액 기준의 성공/실패율, 원화 수익금, 실패 분석, 자동 진단을 받습니다.

| | |
|---|---|
| 백엔드 | Spring Boot 3.5.16 (Gradle, Java 25) — `backend/` |
| 프론트엔드 | Next.js 16 App Router + TypeScript — `frontend/` |
| DB | MySQL 8 (docker compose), 스키마는 Flyway가 자동 관리 |

---

## 빠른 시작

**필요한 것: Docker Desktop 하나뿐입니다.** Java와 Node는 컨테이너 안에서 빌드되므로 따로 설치할 필요가 없습니다.

```bash
git clone https://github.com/joeun8727/stock-analysis.git
cd stock-analysis

cp .env.example .env    # 값을 채우세요 (로컬이면 아무 비밀번호나 OK)

docker compose up -d --build
```

첫 빌드는 몇 분 걸립니다. 다 뜨면 **http://localhost:3000** 으로 접속하세요.

```bash
docker compose ps        # 상태 확인 — mysql이 healthy여야 정상
docker compose logs -f backend
docker compose down      # 종료 (DB 데이터는 볼륨에 남습니다)
```

> `.env` 없이 실행하면 `required variable MYSQL_PASSWORD is missing a value` 로 즉시 실패합니다. 빈 비밀번호로 조용히 뜨는 것보다 낫기 때문에 일부러 이렇게 해뒀습니다.

---

## 처음 쓰는 순서

저장소에는 **봉 데이터가 들어 있지 않습니다**(용량 문제). 그래서 처음 켜면 화면이 비어 있는 게 정상이고, 아래 순서로 채워야 합니다.

### 1. 데이터 업로드 — `데이터 업로드` 화면

3분봉 엑셀(`.xlsx`)을 올립니다. 종목별로 시장 구분(선물 / ETF / 일반)을 고르면 알맞은 `data/` 하위 폴더에 저장되고, 파싱된 봉이 DB(`price_bar`)에 들어갑니다. 14MB / 16만 행짜리도 스트리밍 파서로 처리하지만 시간은 좀 걸립니다.

**엑셀 형식**: 헤더 이름이 아니라 **열 위치**로 읽습니다. 순서가 아래와 같아야 합니다.

| 열 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 내용 | 날짜 | 시각 | 시가 | 고가 | 저가 | 종가 | MA5 | MA10 | MA20 | MA60 | 거래량 | volMA5 | volMA20 | volMA60 | volMA120 |

원본 엑셀의 봉이 최신순으로 정렬돼 있어도 괜찮습니다 — 읽으면서 오름차순으로 정렬합니다.

### 2. (ETF 장전 모드를 쓸 때만) ETF 그룹 만들기 — `ETF 그룹` 화면

장전 선물추세 모드는 **레버리지 + 인버스 + 선물** 세 종목을 함께 봅니다. 그룹을 만들고 슬롯 3개를 업로드한 데이터셋으로 채우세요. 셋이 다 차야 백테스트에서 선택할 수 있습니다.

### 3. 전략 만들기 — `로직 관리` 화면

매수 조건(지표 비교의 AND/OR 조합)과 매도 조건(익절/손절 %, 최대 보유 봉수, 장 마감 청산, 시간대별 익절/손절)을 짭니다. 투자금액과 고정/복리 모드도 여기서 정합니다.

> 매도 규칙이 헷갈리면 **`매도 조건 가이드`** 화면을 보세요. 청산 우선순위, 시간대 밴드, 자주 헷갈리는 점이 정리돼 있습니다.

### 4. 수수료 설정 — `수수료` 화면

수수료는 전략이 아니라 **종목에 붙습니다**(레버리지와 인버스는 요율이 다를 수 있고, 장전 모드는 둘 다 매매하니까요). 종목별 요율을 확인하고 필요하면 고치세요.

### 5. 백테스트 — `백테스트` 화면

전략과 데이터셋(또는 ETF 그룹)을 고르고 기간을 정한 뒤 실행합니다. 결과로 승률, 원화 수익금, 자동 진단, 누적 수익금 곡선, 청산 사유별·시간대별 실패 분석, 거래 내역을 봅니다.

---

## 알아둘 점

**승률과 수익금은 다를 수 있습니다.** 승률은 수수료를 빼지 않은 가격 기준이라, 요율이 높으면 "이긴" 거래가 금액으로는 손실일 수 있습니다. 판단은 원화 수익금 쪽을 보세요.

**LLM 전략 추천은 별도 환경이 필요합니다.** 로컬에 설치·인증된 [Claude Code](https://claude.com/claude-code) CLI를 호출하는 방식이라, **Docker로 띄운 백엔드에서는 동작하지 않습니다.** 쓰려면 백엔드를 로컬에서 직접 실행하세요(아래 참고). 바이너리 경로는 `CLAUDE_BIN`으로 지정합니다.

**다른 PC나 원격 서버에서 접속하게 하려면 `--build`가 필요합니다.** `NEXT_PUBLIC_API_BASE`는 Next.js가 **빌드 시점에 클라이언트 번들에 박아 넣습니다.** 그래서 `.env`만 고치고 재시작하면 이전 값이 그대로 남습니다. 주소를 바꿨다면 반드시 프론트를 다시 빌드하세요.

```bash
# .env 에서 NEXT_PUBLIC_API_BASE=http://192.168.0.10:8080 로 수정한 뒤
docker compose up -d --build frontend
```

---

## 로컬 개발 (컨테이너 없이)

Java 25와 Node 20+가 필요합니다. DB만 컨테이너로 띄웁니다.

```bash
docker compose up -d mysql
```

**백엔드** (`backend/`):
```bash
./gradlew bootRun     # :8080
./gradlew test        # 파서/엔진 단위 테스트 — DB 불필요
./gradlew bootJar
```

**프론트엔드** (`frontend/`):
```bash
npm install
npm run dev      # :3000 (백엔드가 :8080에 떠 있어야 함)
npm run build    # 프로덕션 빌드 + 타입체크
```

compose 밖에서 띄울 때 DB 접속 기본값은 `stock`/`stock` @ `localhost:3306/stock_analysis` 이고, `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` 환경변수로 덮어쓸 수 있습니다.

---

## 환경변수 (`.env`)

`.env`는 git에 올라가지 않습니다. `.env.example`을 복사해서 만드세요.

| 변수 | 설명 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 비밀번호 |
| `MYSQL_DATABASE` | DB 이름 (기본 `stock_analysis`) |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 앱이 쓰는 계정 |
| `DB_HOST` / `DB_PORT` | 백엔드가 붙을 DB 주소 (compose 안에서는 `mysql` / `3306`) |
| `NEXT_PUBLIC_API_BASE` | 브라우저에서 호출할 백엔드 주소 (위 "알아둘 점" 참고) |

백엔드의 `DB_NAME`/`DB_USER`/`DB_PASSWORD`는 compose가 `MYSQL_*` 값을 그대로 넘겨줍니다. **비밀번호는 `.env`에 한 번만 적으면 됩니다.**

---

## 저장소에 없는 것

| | 이유 / 대처 |
|---|---|
| `.env` | 자격증명이라 제외. `cp .env.example .env` |
| `data/**/*.xlsx` | 종목당 14MB라 제외. 폴더 구조만 `.gitkeep`으로 유지 — 데이터 업로드 화면에서 직접 올리세요 |

봉 데이터의 원본(source of truth)은 엑셀 파일이 아니라 **DB의 `price_bar` 테이블**입니다. `data/`의 엑셀은 재파싱·다운로드용 보관본일 뿐이라, 없어도 이미 업로드된 데이터로 백테스트가 됩니다.

---

## 구조

```
backend/src/main/java/com/stockanalysis/
  backtest/     백테스트 엔진 (프레임워크 독립) + 규칙 스펙(spec/)
  data/         엑셀 파싱, 데이터셋·ETF 그룹 관리
  run/          백테스트 실행/저장
  recommend/    LLM 전략 추천
  web/          REST API (/api/...)
  domain/       JPA 엔티티
frontend/src/
  app/          화면 (strategies, datasets, etf-groups, backtest, fees, guide)
  components/   RuleBuilder, EquityCurve, Nav
  lib/          API 클라이언트, 타입
data/           원본 엑셀 보관 (git 제외)
```

엔진 동작의 자세한 규약(청산 우선순위, 금액 환산, 시간대 밴드, 날짜·시각 바인딩 함정 등)은 **[CLAUDE.md](CLAUDE.md)** 에 정리돼 있습니다.
