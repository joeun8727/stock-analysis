"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Dataset, EtfGroup } from "@/lib/types";

const MARKET_LABEL: Record<string, string> = {
  FUTURES: "선물",
  ETF: "ETF",
  NORMAL: "일반주식",
};

export default function DatasetsPage() {
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [groups, setGroups] = useState<EtfGroup[]>([]);
  const [market, setMarket] = useState("ETF");
  const [kind, setKind] = useState("LEVERAGE");
  const [symbol, setSymbol] = useState("");
  const [etfGroupId, setEtfGroupId] = useState<number | "">("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const reload = useCallback(async () => {
    try {
      const [d, g] = await Promise.all([api.listDatasets(), api.listEtfGroups()]);
      setDatasets(d);
      setGroups(g);
      // Preselect the first group only while nothing has been chosen yet.
      setEtfGroupId((prev) => (prev === "" && g[0] ? g[0].id : prev));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const needsGroup = market === "ETF" || market === "FUTURES";

  const upload = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    const file = fileRef.current?.files?.[0];
    if (!file) { setError("엑셀 파일을 선택해주세요."); return; }
    if (!symbol.trim()) { setError("종목명을 입력해주세요."); return; }

    const form = new FormData();
    form.append("file", file);
    form.append("symbol", symbol.trim());
    form.append("market", market);
    if (market === "ETF") form.append("kind", kind);
    if (needsGroup && etfGroupId !== "") form.append("etfGroupId", String(etfGroupId));

    setUploading(true);
    try {
      const ds = await api.uploadDataset(form);
      setSuccess(`업로드 완료: ${ds.symbol} (${ds.barIntervalMinutes}분봉 ${ds.barCount.toLocaleString()}개)${ds.groupName ? ` · 그룹 ${ds.groupName}` : ""}`);
      setSymbol("");
      if (fileRef.current) fileRef.current.value = "";
      await reload();
    } catch (e2) {
      setError(e2 instanceof Error ? e2.message : String(e2));
    } finally {
      setUploading(false);
    }
  };

  const remove = async (id: number) => {
    if (!confirm("이 데이터셋과 봉 데이터를 모두 삭제할까요?")) return;
    try {
      await api.deleteDataset(id);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div>
      <h1>데이터 업로드</h1>
      <p className="subtitle">분봉 엑셀을 업로드하면 DB에 적재되어 백테스트에 사용됩니다. 봉 길이(3분/1분)는 파일에서 자동으로 읽습니다.</p>

      <form className="card" onSubmit={upload}>
        <h2>새 데이터 업로드</h2>
        <div className="grid2">
          <div>
            <label>시장 구분</label>
            <select value={market} onChange={(e) => setMarket(e.target.value)} style={{ width: "100%" }}>
              <option value="ETF">ETF (레버리지/인버스)</option>
              <option value="NORMAL">일반주식</option>
              <option value="FUTURES">선물</option>
            </select>
          </div>
          {market === "ETF" && (
            <div>
              <label>ETF 종류</label>
              <select value={kind} onChange={(e) => setKind(e.target.value)} style={{ width: "100%" }}>
                <option value="LEVERAGE">레버리지</option>
                <option value="INVERSE">인버스</option>
              </select>
            </div>
          )}
          <div>
            <label>종목명</label>
            <input value={symbol} onChange={(e) => setSymbol(e.target.value)} placeholder="예: KODEX레버리지" style={{ width: "100%" }} />
          </div>
          {needsGroup && (
            <div>
              <label>ETF 그룹 {market === "FUTURES" ? "(이 선물이 속할 ETF 짝)" : "(레버리지/인버스 짝)"}</label>
              <select value={etfGroupId} onChange={(e) => setEtfGroupId(e.target.value ? Number(e.target.value) : "")} style={{ width: "100%" }}>
                <option value="">그룹 없음 (장전추세 미사용)</option>
                {groups.map((g) => (
                  <option key={g.id} value={g.id}>{g.name}{g.ready ? " (완성)" : ""}</option>
                ))}
              </select>
              <p className="hint" style={{ marginBottom: 0 }}>
                찾는 그룹이 없으면 <Link href="/etf-groups">ETF 그룹 관리</Link>에서 먼저 만들어주세요.
              </p>
            </div>
          )}
          <div>
            <label>엑셀 파일 (.xlsx)</label>
            <input ref={fileRef} type="file" accept=".xlsx" style={{ width: "100%" }} />
          </div>
        </div>
        {error && <p className="error">{error}</p>}
        {success && <p className="success">{success}</p>}
        <div className="row" style={{ marginTop: 14 }}>
          <button type="submit" disabled={uploading}>{uploading ? "업로드·적재 중… (수십초 소요)" : "업로드"}</button>
        </div>
        <p className="hint">14MB 대용량 파일은 파싱·DB 적재에 십수 초가 걸릴 수 있습니다. 한 그룹에는 레버리지·인버스·선물이 각각 하나만 들어갑니다.</p>
      </form>

      <div className="card">
        <h2>업로드된 데이터셋 ({datasets.length})</h2>
        {datasets.length === 0 ? (
          <p className="muted">아직 업로드된 데이터가 없습니다.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>종목</th><th>시장</th><th>종류</th><th>그룹</th><th>수수료</th><th>봉 길이</th><th>봉 수</th><th>기간</th><th></th>
              </tr>
            </thead>
            <tbody>
              {datasets.map((d) => (
                <tr key={d.id}>
                  <td>{d.symbol}</td>
                  <td><span className="badge market">{MARKET_LABEL[d.market] ?? d.market}</span></td>
                  <td className="muted">{d.kind}</td>
                  <td className="muted">{d.groupName ?? "-"}</td>
                  <td className="muted">{d.feeRatePct}%</td>
                  <td><span className="badge market">{d.barIntervalMinutes}분봉</span></td>
                  <td>{d.barCount.toLocaleString()}</td>
                  <td className="muted">{d.fromTs?.slice(0, 10)} ~ {d.toTs?.slice(0, 10)}</td>
                  <td><button className="danger" onClick={() => remove(d.id)}>삭제</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="hint">
          그룹 배정은 <Link href="/etf-groups">ETF 그룹 관리</Link>, 종목별 수수료는 <Link href="/fees">수수료 설정</Link>에서 바꿉니다 (둘 다 재업로드 불필요).
        </p>
      </div>
    </div>
  );
}
