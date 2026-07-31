"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Dataset, FeeSettingDto } from "@/lib/types";

const MARKET_LABEL: Record<string, string> = {
  FUTURES: "선물",
  ETF: "ETF",
  NORMAL: "일반주식",
};

const KIND_LABEL: Record<string, string> = {
  LEVERAGE: "레버리지",
  INVERSE: "인버스",
  SINGLE: "단일",
};

/** 10,000,000원 traded at r% per side costs this much for a round trip (both legs). */
function roundTripCost(pct: number, notional = 10_000_000): number {
  return notional * (pct / 100) * 2;
}

/** One symbol's rate: commits on Enter or blur, so there's no save button per row. */
function FeeCell({
  dataset,
  onSave,
}: {
  dataset: Dataset;
  onSave: (pct: number) => Promise<void>;
}) {
  const current = String(dataset.feeRatePct);
  const [value, setValue] = useState(current);
  const [saving, setSaving] = useState(false);

  useEffect(() => { setValue(current); }, [current]);

  const commit = async () => {
    if (value.trim() === current) return;
    const next = Number(value);
    if (!Number.isFinite(next)) { setValue(current); return; }
    setSaving(true);
    try {
      await onSave(next);
    } finally {
      setSaving(false);
    }
  };

  return (
    <span className="row" style={{ gap: 4 }}>
      <input
        type="number"
        step="0.001"
        min={0}
        value={value}
        disabled={saving}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") { e.preventDefault(); (e.target as HTMLInputElement).blur(); }
          if (e.key === "Escape") setValue(current);
        }}
        style={{ width: 96, padding: "4px 6px", fontSize: 13 }}
      />
      <span className="muted" style={{ fontSize: 12 }}>%</span>
    </span>
  );
}

export default function FeesPage() {
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [fallback, setFallback] = useState<FeeSettingDto | null>(null);
  const [defaultValue, setDefaultValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      const [d, f] = await Promise.all([api.listDatasets(), api.getFee()]);
      setDatasets(d);
      setFallback(f);
      setDefaultValue(String(f.feeRatePct));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const run = async (fn: () => Promise<unknown>, okMsg?: string) => {
    setError(null);
    setOk(null);
    setBusy(true);
    try {
      await fn();
      if (okMsg) setOk(okMsg);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      await reload();
    } finally {
      setBusy(false);
    }
  };

  const saveDefault = () =>
    run(
      () => api.updateFee(Number(defaultValue)),
      `새로 업로드하는 종목의 기본 수수료율을 ${Number(defaultValue)}%로 저장했습니다.`,
    );

  const applyToAll = () => {
    const pct = Number(defaultValue);
    if (!Number.isFinite(pct)) { setError("숫자를 입력해주세요."); return; }
    if (!confirm(`등록된 종목 ${datasets.length}개 전부를 ${pct}%로 덮어쓸까요?`)) return;
    run(
      async () => { for (const d of datasets) await api.setDatasetFee(d.id, pct); },
      `종목 ${datasets.length}개의 수수료율을 ${pct}%로 맞췄습니다.`,
    );
  };

  const defaultDirty = fallback !== null && Number(defaultValue) !== fallback.feeRatePct;
  const etf = datasets.filter((d) => d.market === "ETF");
  const others = datasets.filter((d) => d.market !== "ETF");

  const rows = (list: Dataset[]) =>
    list.map((d) => (
      <tr key={d.id}>
        <td>{d.symbol}</td>
        <td><span className="badge market">{MARKET_LABEL[d.market] ?? d.market}</span></td>
        <td className="muted">{KIND_LABEL[d.kind] ?? d.kind}</td>
        <td className="muted">{d.groupName ?? "-"}</td>
        <td>
          <FeeCell dataset={d} onSave={(pct) => run(() => api.setDatasetFee(d.id, pct))} />
        </td>
        <td className="muted">{Math.round(roundTripCost(d.feeRatePct)).toLocaleString()}원</td>
      </tr>
    ));

  return (
    <div>
      <h1>수수료 설정</h1>
      <p className="subtitle">
        수수료는 <b>종목마다 따로</b> 지정합니다. 레버리지와 인버스가 요율이 다르면 각각 넣으면 되고, 백테스트는 그날 실제로 매매한 종목의 요율을 적용합니다.
      </p>

      {error && <p className="error">{error}</p>}
      {ok && <p className="success">{ok}</p>}

      <div className="card">
        <div className="section-title">
          <h2>종목별 수수료 (편도 %)</h2>
          <span className="pill">숫자를 고치면 바로 저장</span>
        </div>
        {datasets.length === 0 ? (
          <p className="muted" style={{ margin: 0 }}>
            등록된 종목이 없습니다. <Link href="/datasets">데이터 업로드</Link>에서 먼저 올려주세요.
          </p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>종목</th><th>시장</th><th>종류</th><th>ETF 그룹</th>
                <th>편도 수수료율</th><th>1,000만원 왕복 비용</th>
              </tr>
            </thead>
            <tbody>
              {rows(etf)}
              {rows(others)}
            </tbody>
          </table>
        )}
        <p className="hint">
          <b>매수·매도 양쪽에 각각</b> 적용되므로 왕복 비용은 입력값의 2배입니다. ETF는 증권거래세가 없지만
          <b> 일반주식은 매도 시 거래세</b>가 붙으니, 일반주식 종목에는 매도세를 요율에 함께 얹어 넣어주세요.
        </p>
      </div>

      <div className="card">
        <h2>새 종목 기본값</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          앞으로 업로드하는 종목에 자동으로 들어갈 요율입니다. 이미 등록된 종목에는 영향을 주지 않습니다.
        </p>
        <div className="row">
          <input
            type="number"
            step="0.001"
            min={0}
            value={defaultValue}
            onChange={(e) => setDefaultValue(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); saveDefault(); } }}
            style={{ width: 140 }}
          />
          <span className="muted">%</span>
          <button onClick={saveDefault} disabled={busy || !defaultDirty}>
            {busy ? "저장 중…" : defaultDirty ? "기본값 저장" : "저장됨"}
          </button>
          <button className="secondary" onClick={applyToAll} disabled={busy || datasets.length === 0}>
            이 값을 전체 종목에 적용
          </button>
        </div>
        <p className="hint">
          참고 요율: 비대면 우대 0.0036% · 일반 온라인 0.015% · 오프라인 0.1% 수준. 0으로 두면 수수료를 반영하지 않습니다.
          값을 바꿔 <Link href="/backtest">백테스트</Link>를 다시 돌리면 같은 전략의 수익금이 얼마나 달라지는지 비교할 수 있습니다.
        </p>
      </div>
    </div>
  );
}
