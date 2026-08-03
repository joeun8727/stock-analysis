import Link from "next/link";

export default function Home() {
  return (
    <div>
      <h1>단타 백테스터</h1>
      <p className="subtitle">규칙을 조합해 단타 로직을 만들고, 분봉 데이터(3분봉·1분봉)로 백테스트합니다.</p>
      <div className="grid2">
        <Link href="/strategies" className="card">
          <h2>로직 관리</h2>
          <p className="muted">지표와 조건을 조합해 매수·매도 규칙을 만들고 관리합니다.</p>
        </Link>
        <Link href="/datasets" className="card">
          <h2>데이터 업로드</h2>
          <p className="muted">ETF(레버리지/인버스), 일반 종목, 선물 엑셀을 업로드합니다. 봉 길이는 파일에서 자동으로 읽습니다.</p>
        </Link>
        <Link href="/etf-groups" className="card">
          <h2>ETF 그룹</h2>
          <p className="muted">레버리지·인버스·선물 세 데이터를 한 묶음으로 지정합니다. 장전 선물추세 백테스트에 필요합니다.</p>
        </Link>
        <Link href="/backtest" className="card">
          <h2>백테스트</h2>
          <p className="muted">전략과 데이터를 골라 백테스트하고 성공/실패율을 분석합니다.</p>
        </Link>
        <Link href="/fees" className="card">
          <h2>수수료 설정</h2>
          <p className="muted">종목별 매매 수수료율과 새 업로드에 적용할 기본값을 정합니다.</p>
        </Link>
        <Link href="/guide" className="card">
          <h2>매도 조건 가이드</h2>
          <p className="muted">청산이 어떤 순서로 검사되는지, 시간대별 익절·손절은 어떻게 쓰는지 설명합니다.</p>
        </Link>
      </div>
    </div>
  );
}
