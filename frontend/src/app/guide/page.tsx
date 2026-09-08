import Link from "next/link";

/**
 * 매도 규칙 전용 정적 도움말 페이지 — 규칙 빌더에서 사람들이 가장 많이 걸려 넘어지는 부분입니다.
 * 여기 있는 내용은 전부 BacktestEngine.decideExit / ExitSpec을 그대로 옮긴 것입니다.
 * 엔진을 바꾸면 이 페이지도 함께 고치세요.
 */

const INDICATORS: { key: string; name: string; note: string }[] = [
  { key: "OPEN", name: "시가", note: "그 봉이 시작될 때 가격" },
  { key: "HIGH", name: "고가", note: "그 봉 안에서 가장 높았던 가격" },
  { key: "LOW", name: "저가", note: "그 봉 안에서 가장 낮았던 가격" },
  { key: "CLOSE", name: "종가", note: "그 봉이 끝날 때 가격 — 가장 많이 쓰는 기준" },
  { key: "MA5", name: "5분 이동평균", note: "최근 5분 평균가 (아주 짧은 흐름)" },
  { key: "MA10", name: "10분 이동평균", note: "최근 10분 평균가" },
  { key: "MA20", name: "20분 이동평균", note: "최근 20분 평균가 (단타의 기준선)" },
  { key: "MA60", name: "60분 이동평균", note: "최근 1시간 평균가 (그날의 큰 방향)" },
  { key: "VOLUME", name: "거래량", note: "그 봉에 체결된 수량" },
  { key: "VOL_MA5", name: "거래량 5분 평균", note: "최근 5분 평균 거래량" },
  { key: "VOL_MA20", name: "거래량 20분 평균", note: "최근 20분 평균 거래량" },
  { key: "VOL_MA60", name: "거래량 60분 평균", note: "최근 1시간 평균 거래량" },
  { key: "VOL_MA120", name: "거래량 120분 평균", note: "최근 2시간 평균 거래량" },
];

const OPERATORS: { key: string; label: string; means: string }[] = [
  { key: ">", label: "초과 (GT)", means: "왼쪽이 오른쪽보다 크면 참" },
  { key: ">=", label: "이상 (GTE)", means: "왼쪽이 오른쪽보다 크거나 같으면 참" },
  { key: "<", label: "미만 (LT)", means: "왼쪽이 오른쪽보다 작으면 참" },
  { key: "<=", label: "이하 (LTE)", means: "왼쪽이 오른쪽보다 작거나 같으면 참" },
  { key: "=", label: "같음 (EQ)", means: "두 값이 정확히 같으면 참 (소수점 때문에 거의 안 맞음 — 잘 안 씁니다)" },
  {
    key: "↗",
    label: "상향돌파 (CROSS_ABOVE)",
    means: "직전 봉에서는 왼쪽 ≤ 오른쪽이었는데, 이번 봉에서 왼쪽 > 오른쪽이 된 순간에만 참",
  },
  {
    key: "↘",
    label: "하향돌파 (CROSS_BELOW)",
    means: "직전 봉에서는 왼쪽 ≥ 오른쪽이었는데, 이번 봉에서 왼쪽 < 오른쪽이 된 순간에만 참",
  },
];

const RECIPES: { title: string; rule: string; why: string }[] = [
  {
    title: "짧은 흐름이 꺾이면 판다",
    rule: "CLOSE  하향돌파  MA5",
    why: "5분 평균선을 종가가 뚫고 내려간 그 봉에 청산. 가장 무난한 시그널 청산이고, 익절선까지 못 갔는데 힘이 빠진 자리를 잡아냅니다.",
  },
  {
    title: "데드크로스가 나면 판다",
    rule: "MA5  하향돌파  MA20",
    why: "단기선이 20분선을 아래로 뚫는 자리. 위보다 늦게 나오지만 그만큼 덜 흔들립니다(잔파동에 덜 속음).",
  },
  {
    title: "기준선 아래면 그냥 판다",
    rule: "CLOSE  <  MA20",
    why: "돌파가 아니라 '상태'라서, 이미 기준선 아래라면 보유 다음 봉에서 바로 청산됩니다. 매우 빨리 털고 나오는 설정.",
  },
  {
    title: "거래량이 터지면 판다",
    rule: "VOLUME  >  VOL_MA20",
    why: "평소(20분 평균)보다 거래량이 많아진 봉에서 청산. 급등 끝물에 물량이 쏟아지는 자리를 피하려는 용도입니다.",
  },
  {
    title: "특정 가격에 닿으면 판다",
    rule: "HIGH  >=  숫자 30000",
    why: "오른쪽을 '숫자'로 바꾸면 절대 가격 기준이 됩니다. 종목마다 가격대가 달라 재사용은 어렵습니다.",
  },
];

export default function GuidePage() {
  return (
    <div>
      <h1>매도 조건 가이드</h1>
      <p className="subtitle">
        매도(청산) 규칙이 실제로 어떻게 동작하는지 정리했습니다. <Link href="/strategies">로직 관리</Link>에서 전략을 만들 때 옆에 두고 보세요.
      </p>

      <div className="card">
        <h2>한 줄 요약</h2>
        <p className="diag-headline" style={{ marginBottom: 0 }}>
          매수한 <b>다음 봉부터</b>, 봉이 하나 끝날 때마다 <b>손절 → 익절 → 매도 시그널 → 최대 보유 → 당일 청산</b> 순서로 검사해서
          <b> 가장 먼저 걸리는 하나</b>로 팝니다. &lsquo;매도 시그널 조건&rsquo;은 그중 세 번째 칸일 뿐입니다.
        </p>
      </div>

      <div className="card">
        <div className="section-title">
          <h2>만드는 순서 (처음이라면 여기부터)</h2>
          <span className="pill">화면 그대로 따라 하기</span>
        </div>
        <ol className="guide-steps">
          <li>
            상단 메뉴 <Link href="/strategies">로직 관리</Link> → 맨 위 <b>새 전략 만들기</b> 카드에서 시작합니다.
          </li>
          <li>
            <b>전략 이름</b>을 적고(예: 골든크로스 단타), <b>대상 타입</b>을 고릅니다 — 종목 하나면 &lsquo;일반 종목&rsquo;,
            레버리지/인버스 쌍이면 &lsquo;ETF&rsquo;.
          </li>
          <li>
            <b>투자금액</b>에 1회 매수 금액(또는 복리 시작 자금)과 운용 방식을 정합니다. 이 값은 매매 시점을 바꾸지 않고
            결과를 원화로 환산하는 데만 씁니다.
          </li>
          <li>
            <b>매수 조건</b>에서 <span className="guide-btn">+ 조건 추가</span>를 눌러 언제 살지 정합니다.
            (ETF 장전 선물추세 모드를 켜면 이 칸은 사라지고 08:45~09:00 선물 추세가 대신 진입을 결정합니다.)
          </li>
          <li>
            <b>매도 조건 (청산)</b> → <b>익절 / 손절</b> 표의 첫 줄 <b>전체(기본)</b>에 익절 %와 손절 %를 넣습니다. 비워두면 그 규칙은 쓰지 않는다는 뜻입니다.
            장 초반과 점심때 폭을 다르게 하고 싶으면 <span className="guide-btn">+ 시간대 추가</span>로 줄을 늘리면 됩니다(선택).
            → <a href="#time-bands">아래 설명</a>
          </li>
          <li>
            <b>그 외 청산</b>의 <b>최대 보유 봉수</b>(1봉 = 그 데이터셋의 봉 길이 — 3분봉이면 3분, 1분봉이면 1분)와
            <b> 당일 청산</b>을 정합니다. 단타라면 당일 청산은 켜두는 걸 권합니다.
          </li>
          <li>
            더 세밀하게 팔고 싶을 때만 <b>매도 시그널 조건 (선택)</b>에 조건을 넣습니다. 오른쪽 드롭다운에서
            <b> 하나라도 만족(OR)</b> / <b>모두 만족(AND)</b>을 고르고 <span className="guide-btn">+ 조건 추가</span>.
            <b> 비워두면 익절·손절·최대 보유·당일 청산만으로 매매합니다.</b>
          </li>
          <li>
            <span className="guide-btn primary">전략 저장</span> → 아래 <b>저장된 전략</b> 목록에 추가됩니다.
          </li>
          <li>
            상단 메뉴 <Link href="/backtest">백테스트</Link> → 전략과 데이터(또는 ETF 그룹), 기간을 고르고
            <span className="guide-btn primary">백테스트 실행</span>.
          </li>
          <li>
            결과의 <b>실패 원인별 분석</b>에서 어떤 청산 사유로 졌는지 보고, 다시 로직 관리의 <span className="guide-btn">수정</span> 버튼으로
            해당 칸을 손봅니다. 이 반복이 전부입니다.
          </li>
        </ol>
        <div className="guide-callout">
          <div className="guide-box-h">조건 한 줄 추가하는 법</div>
          <p style={{ margin: "0 0 10px" }}>
            <span className="guide-btn">+ 조건 추가</span>를 누르면 칸 네 개짜리 줄이 하나 생깁니다. 왼쪽부터 이렇게 고릅니다.
          </p>
          <div className="guide-formula">
            <span className="guide-slot">① 왼쪽 지표</span>
            <span className="guide-op">②</span>
            <span className="guide-slot">비교 방법</span>
            <span className="guide-op">③</span>
            <span className="guide-slot">지표 / 숫자</span>
            <span className="guide-op">④</span>
            <span className="guide-slot">오른쪽 값</span>
          </div>
          <p className="hint" style={{ marginTop: 0 }}>
            ③에서 <b>지표</b>를 고르면 오른쪽도 드롭다운(예: MA5)이 되고, <b>숫자</b>를 고르면 직접 입력 칸(예: 30000)이 됩니다.
            줄 오른쪽 끝 <span className="guide-btn">삭제</span>로 그 줄만 지울 수 있습니다. 지표 이름은 아래 표에서 확인하세요.
          </p>
        </div>
      </div>

      <div className="card">
        <div className="section-title">
          <h2>매도 방법 5가지와 우선순위</h2>
          <span className="pill">위에 있을수록 먼저 검사</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>순서</th>
              <th>설정 항목</th>
              <th>언제 팔리나</th>
              <th>체결 가격</th>
              <th>결과 화면 표기</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>1</td>
              <td><b>손절 %</b></td>
              <td>봉의 <b>저가</b>가 손절선에 닿으면</td>
              <td>손절 가격 (매수가 × (1 − 손절%))</td>
              <td><span className="reason-tag">손절</span></td>
            </tr>
            <tr>
              <td>2</td>
              <td><b>익절 %</b></td>
              <td>봉의 <b>고가</b>가 익절선에 닿으면</td>
              <td>익절 가격 (매수가 × (1 + 익절%))</td>
              <td><span className="reason-tag">익절</span></td>
            </tr>
            <tr>
              <td>3</td>
              <td><b>매도 시그널 조건</b></td>
              <td>내가 만든 조건이 참이 되는 봉에서</td>
              <td>그 봉의 <b>종가</b></td>
              <td><span className="reason-tag">시그널</span></td>
            </tr>
            <tr>
              <td>4</td>
              <td><b>최대 보유 봉수</b></td>
              <td>산 뒤 N봉이 지나면 (봉 길이 × N)</td>
              <td>그 봉의 종가</td>
              <td><span className="reason-tag">시간청산</span></td>
            </tr>
            <tr>
              <td>5</td>
              <td><b>당일 청산</b></td>
              <td>그날 마지막 봉에서 무조건</td>
              <td>그 봉의 종가</td>
              <td><span className="reason-tag">당일청산</span></td>
            </tr>
            <tr>
              <td>-</td>
              <td className="muted">(자동)</td>
              <td className="muted">데이터가 끝났는데 아직 들고 있으면</td>
              <td className="muted">마지막 봉의 종가</td>
              <td><span className="reason-tag">데이터종료</span></td>
            </tr>
          </tbody>
        </table>
        <p className="hint">
          1·2번(익절·손절)의 %는 같은 표에서 <b>시간대별로 다르게</b> 줄 수 있습니다. <a href="#time-bands">시간대별 익절 / 손절</a>을 보세요.
        </p>
        <p className="hint">
          한 봉 안에서 손절선과 익절선에 <b>둘 다</b> 닿았다면 <b>손절로 처리</b>합니다. 봉 데이터만으로는 어느 쪽이 먼저였는지 알 수 없어서,
          결과를 좋게 보이게 하지 않는 쪽(보수적)으로 계산합니다.
        </p>
      </div>

      <div className="card" id="time-bands">
        <div className="section-title">
          <h2>시간대별 익절 / 손절</h2>
          <span className="pill">선택 기능</span>
        </div>
        <p style={{ marginTop: 0 }}>
          장 초반은 크게 움직이고 점심때는 잠잠한 것처럼, 시간대마다 적정 폭이 다릅니다.
          <b> 익절 / 손절</b> 표는 한 장입니다 — 첫 줄 <b>전체(기본)</b>가 평소 값이고, 아래로 시간대 줄을 추가하면 그 시간에만 다른 값이 쓰입니다.
        </p>
        <div className="guide-formula" style={{ display: "block" }}>
          <table style={{ margin: 0 }}>
            <thead>
              <tr><th>적용 시간</th><th>익절 %</th><th>손절 %</th></tr>
            </thead>
            <tbody>
              <tr><td>전체 (기본)</td><td>1.5</td><td>1.0</td></tr>
              <tr><td>09:00 ~ 10:00</td><td>2.0</td><td>1.5</td></tr>
              <tr><td>10:00 ~ 15:20</td><td>0.8</td><td className="muted">비움 → 기본 1.0</td></tr>
            </tbody>
          </table>
        </div>
        <p className="hint" style={{ marginTop: 0 }}>
          위 예시에서 15:20 이후(또는 09:00 이전)에 들고 있으면 어느 줄에도 안 걸리므로 <b>전체(기본)</b>의 1.5 / 1.0이 적용됩니다.
        </p>
        <div className="guide-callout">
          <div className="guide-box-h">가장 중요한 규칙 — 기준은 &lsquo;지금 보고 있는 봉의 시각&rsquo;</div>
          <p style={{ margin: "0 0 10px" }}>
            산 시각으로 한 번 정하고 끝이 아니라, <b>보유 중에도 봉마다 다시 판단</b>합니다. 시간대가 바뀌면 익절·손절선도 그 자리에서 옮겨집니다.
          </p>
          <div className="guide-formula" style={{ display: "block" }}>
            <div className="guide-read" style={{ marginBottom: 8 }}>설정: 09:00~10:00 익절 +2.0% / 손절 -1.5% · 10:00~15:20 익절 +0.8% / 손절 -0.5%</div>
            <div>
              <b>09:20 매수 @ 10,000원</b>
              <ul className="guide-list" style={{ marginTop: 6 }}>
                <li>09:20 ~ 09:59 → 익절 10,200 / 손절 9,850</li>
                <li>10:00 이후 → 익절 10,080 / <b>손절 9,950</b> 으로 선이 옮겨짐</li>
                <li>그래서 10:00에 주가가 9,900이면 <b>그 봉에서 손절</b>됩니다 (09시 기준으론 아직 살아있던 자리)</li>
              </ul>
            </div>
          </div>
          <p className="hint" style={{ marginTop: 0, marginBottom: 0 }}>
            즉 시간이 갈수록 폭을 좁히면 &ldquo;오래 들고 있을수록 빨리 정리한다&rdquo;는 뜻이 됩니다. 반대로 넓히면 늦게까지 버팁니다.
          </p>
        </div>
        <ul className="guide-list">
          <li>시간대는 <b>시작 이상 ~ 종료 미만</b>입니다. 09:00~10:00과 10:00~15:20을 나란히 두면 10:00은 뒤쪽 구간에 들어갑니다.</li>
          <li>시간대 줄에서 익절·손절 칸을 <b>비워두면 그 항목만 전체(기본) 값</b>을 씁니다. 손절만 시간대별로 바꾸고 익절은 고정해 두는 식이 가능합니다.</li>
          <li>어느 시간대 줄에도 안 걸리는 시각(예: 13시 이후 줄만 만든 경우의 오전)에는 <b>전체(기본)</b> 줄이 그대로 적용됩니다.</li>
          <li>시간대가 겹치면 <b>표에서 위에 있는 줄</b>이 이깁니다. 좁은 예외 구간을 위에 두면 그 시간만 다르게 다룰 수 있습니다.</li>
          <li>시간대는 익절·손절에만 적용됩니다. 매도 시그널 조건·최대 보유 봉수·당일 청산은 시간대와 무관하게 그대로 동작합니다.</li>
        </ul>
        <p className="hint">
          ETF 장전 선물추세 모드에서도 똑같이 적용됩니다 (진입이 09:00 부근에 고정될 뿐입니다).
        </p>
      </div>

      <div className="card">
        <h2>매도 시그널 조건은 어떻게 읽나</h2>
        <p>조건 한 줄은 이렇게 생겼습니다. 왼쪽 값과 오른쪽 값을 가운데 비교로 견주는 것뿐입니다.</p>
        <div className="guide-formula">
          <span className="guide-slot">CLOSE</span>
          <span className="guide-op">하향돌파</span>
          <span className="guide-slot">MA5</span>
          <span className="guide-read">→ &ldquo;종가가 5분 이동평균을 뚫고 내려가면&rdquo;</span>
        </div>
        <ul className="guide-list">
          <li><b>왼쪽</b>은 항상 지표(가격·이동평균·거래량)입니다.</li>
          <li><b>오른쪽</b>은 지표 또는 숫자 중에 고릅니다. 숫자를 고르면 &ldquo;종가 &gt; 30000&rdquo; 같은 절대 가격 비교가 됩니다.</li>
          <li>보유 중인 봉마다 이 조건을 다시 검사하고, <b>참이 되는 첫 봉의 종가</b>에 팝니다.</li>
          <li>조건을 하나도 넣지 않으면 이 규칙은 <b>없는 것</b>이 됩니다 — 익절·손절·최대 보유·당일 청산만 작동합니다.</li>
        </ul>
        <p className="hint">
          곱하기·더하기는 지원하지 않습니다. &ldquo;거래량 &gt; 평균의 2배&rdquo; 같은 식은 쓸 수 없고, &ldquo;VOLUME &gt; VOL_MA20&rdquo; 처럼 지표끼리 또는 지표와 숫자만 비교합니다.
        </p>
      </div>

      <div className="card">
        <h2>여러 줄을 넣었을 때 — 하나라도(OR) vs 모두(AND)</h2>
        <div className="grid2">
          <div className="guide-box">
            <div className="guide-box-h">하나라도 만족 (OR) · 기본값</div>
            <p>조건 중 <b>하나만</b> 참이어도 팝니다. 탈출구를 여러 개 열어두는 셈이라 <b>빨리, 자주</b> 팔립니다.</p>
            <p className="muted" style={{ margin: 0 }}>예) 종가가 MA5를 하향돌파 <b>또는</b> 종가 &lt; MA20</p>
          </div>
          <div className="guide-box">
            <div className="guide-box-h">모두 만족 (AND)</div>
            <p>조건이 <b>같은 봉에서 전부</b> 참일 때만 팝니다. 훨씬 까다로워서 <b>늦게, 가끔</b> 팔립니다.</p>
            <p className="muted" style={{ margin: 0 }}>예) 종가 &lt; MA5 <b>그리고</b> 종가 &lt; MA20</p>
          </div>
        </div>
        <p className="hint">
          AND에 <b>돌파(상향/하향돌파)를 두 개 이상</b> 넣으면 두 돌파가 같은 봉에 동시에 일어나야 해서 사실상 거의 안 걸립니다.
          AND를 쓸 땐 돌파 1개 + 상태 조건(&lt;, &gt;)으로 섞는 편이 낫습니다.
        </p>
      </div>

      <div className="card">
        <h2>바로 쓸 수 있는 예시</h2>
        {RECIPES.map((r) => (
          <div className="guide-recipe" key={r.title}>
            <div className="guide-recipe-h">{r.title}</div>
            <code className="guide-code">{r.rule}</code>
            <p className="f-fix" style={{ marginTop: 6 }}>{r.why}</p>
          </div>
        ))}
      </div>

      <div className="card">
        <h2>지표 이름 읽는 법</h2>
        <p className="hint" style={{ marginTop: 0 }}>
          이동평균 뒤의 숫자는 <b>봉 개수가 아니라 분</b>입니다 — MA20은 최근 <b>20분</b> 평균이고, 3분봉이든 1분봉이든 같은 값을 뜻합니다.
          봉 길이에 따라 달라지는 건 <b>최대 보유 봉수</b>뿐입니다(1봉 = 3분 또는 1분).
        </p>
        <table>
          <thead>
            <tr><th>화면 표기</th><th>뜻</th><th>설명</th></tr>
          </thead>
          <tbody>
            {INDICATORS.map((i) => (
              <tr key={i.key}>
                <td><code className="guide-code sm">{i.key}</code></td>
                <td>{i.name}</td>
                <td className="muted">{i.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>비교 방법</h2>
        <table>
          <thead>
            <tr><th style={{ width: 40 }} /><th>이름</th><th>뜻</th></tr>
          </thead>
          <tbody>
            {OPERATORS.map((o) => (
              <tr key={o.label}>
                <td><b>{o.key}</b></td>
                <td>{o.label}</td>
                <td className="muted">{o.means}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="hint">
          <b>&lsquo;돌파&rsquo;와 &lsquo;상태&rsquo;의 차이</b>가 핵심입니다. &ldquo;CLOSE &lt; MA5&rdquo;(상태)는 이미 아래에 있기만 하면 계속 참이라 사자마자 팔릴 수 있고,
          &ldquo;CLOSE 하향돌파 MA5&rdquo;(돌파)는 <b>위에서 아래로 넘어가는 그 한 봉</b>에서만 참입니다.
        </p>
      </div>

      <div className="card">
        <h2>자주 헷갈리는 것</h2>
        <div className="finding sev-HIGH">
          <div className="f-title"><span className="badge sev-HIGH">심각</span>산 봉에서는 팔지 않습니다</div>
          <p className="f-detail">매수는 조건이 맞은 봉의 종가에 이루어지고, 매도 검사는 <b>그다음 봉부터</b> 시작합니다. 최소 한 봉(3분봉이면 3분, 1분봉이면 1분)은 보유합니다.</p>
        </div>
        <div className="finding sev-HIGH">
          <div className="f-title"><span className="badge sev-HIGH">심각</span>손절·익절이 시그널보다 먼저입니다</div>
          <p className="f-detail">
            같은 봉에서 손절선에 닿으면서 매도 시그널도 참이라면 <b>손절</b>로 기록됩니다. 결과 화면에 시그널 청산이 적게 나온다면
            손절·익절 폭이 너무 좁아 시그널이 발동할 틈이 없는 경우일 수 있습니다.
          </p>
        </div>
        <div className="finding sev-MEDIUM">
          <div className="f-title"><span className="badge sev-MEDIUM">주의</span>첫 봉과 값이 없는 구간에서는 조건이 거짓입니다</div>
          <p className="f-detail">
            돌파는 직전 봉과 비교하므로 시리즈의 첫 봉에서는 판정할 수 없습니다. 또 이동평균이 아직 채워지지 않은 구간(예: 장 시작 직후의 MA60,
            VOL_MA120)은 값이 비어 있고, <b>값이 비면 그 조건은 무조건 거짓</b>으로 칩니다.
          </p>
        </div>
        <div className="finding sev-MEDIUM">
          <div className="f-title"><span className="badge sev-MEDIUM">주의</span>당일 청산을 켜두면 결국 그날 안에 정리됩니다</div>
          <p className="f-detail">
            아무 조건도 안 걸려도 그날 마지막 봉에서 청산합니다(단타 기본값). 결과의 청산 사유가 <span className="reason-tag">당일청산</span>에 몰려 있다면
            익절·손절·시그널 중 무엇도 제 역할을 못 하고 있다는 신호입니다.
          </p>
        </div>
        <div className="finding sev-INFO">
          <div className="f-title"><span className="badge sev-INFO">참고</span>장전 선물추세 모드에서도 매도 규칙은 그대로입니다</div>
          <p className="f-detail">
            그 모드에서는 <b>매수 조건만</b> 무시되고(08:45~09:00 선물 추세가 대신 진입을 결정), 여기 적힌 매도 규칙은 똑같이 적용됩니다. 당일 안에 반드시 청산합니다.
          </p>
        </div>
      </div>

      <div className="card">
        <h2>결과 화면과 이어 보기</h2>
        <p>
          <Link href="/backtest">백테스트</Link> 결과의 <b>실패 원인별 분석</b>에 나오는 청산 사유가 바로 위 표의 마지막 열입니다.
          손실이 어느 사유에 몰려 있는지를 보고 그 칸의 설정을 손보면 됩니다.
        </p>
        <ul className="guide-list">
          <li><b>손절</b>에 몰림 → 손절 폭이 변동성에 비해 좁습니다. 폭을 넓히거나 매수 조건을 더 까다롭게.</li>
          <li><b>시그널</b>에 몰림 → 매도 조건이 너무 예민합니다. 돌파 대신 더 긴 이동평균을 쓰거나 조건을 AND로 묶어보세요.</li>
          <li><b>시간청산·당일청산</b>에 몰림 → 수익이 날 때까지 못 기다린 게 아니라, 애초에 방향이 안 맞았을 가능성이 큽니다. 매수 조건을 먼저 보세요.</li>
        </ul>
        <p className="hint">결과 화면의 <b>자동 진단</b> 카드가 같은 내용을 그 백테스트 수치에 맞춰 짚어줍니다.</p>
      </div>
    </div>
  );
}
