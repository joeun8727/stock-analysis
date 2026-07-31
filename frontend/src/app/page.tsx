import Link from "next/link";

export default function Home() {
  return (
    <div>
      <h1>단타 백테스터</h1>
      <p className="subtitle">규칙을 조합해 단타 로직을 만들고, 3분봉 데이터로 백테스트합니다.</p>
      <div className="grid2">
        <Link href="/strategies" className="card">
          <h2>로직 관리</h2>
          <p className="muted">지표와 조건을 조합해 매수·매도 규칙을 만들고 관리합니다.</p>
        </Link>
        <Link href="/datasets" className="card">
          <h2>데이터 업로드</h2>
          <p className="muted">ETF(레버리지/인버스), 일반 종목, 선물 엑셀을 업로드합니다.</p>
        </Link>
        <Link href="/backtest" className="card">
          <h2>백테스트</h2>
          <p className="muted">전략과 데이터를 골라 백테스트하고 성공/실패율을 분석합니다.</p>
        </Link>
      </div>
    </div>
  );
}
