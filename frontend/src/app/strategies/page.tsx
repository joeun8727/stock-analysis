"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Condition, Dataset, Meta, Strategy, VerificationStatus } from "@/lib/types";
import RuleBuilder from "@/components/RuleBuilder";

function operandText(o: { indicator?: string; const?: number | null }): string {
  if (o.const !== undefined && o.const !== null && !o.indicator) return String(o.const);
  return o.indicator ?? "?";
}
function condText(c: Condition): string {
  return `${operandText(c.left)} ${c.op} ${operandText(c.right)}`;
}

/**
 * 이 저장된 로직으로 돈을 걸 수 있는지를, 로직 바로 옆에 보여줍니다.
 *
 * <p>같은 StrategySpec이 백테스트와 실계좌를 함께 굴리므로, 이 컬럼은 둘을 정직하게 유지하는
 * 규칙의 눈에 보이는 형태입니다: 화면의 규칙이 아직 백테스트가 점수를 매긴 그 규칙일 때만
 * 매매할 수 있습니다. 규칙을 고치면 "백테스트 필요"로 돌아갑니다.
 */
function LiveReadiness({
  strategy,
  status,
}: {
  strategy: Strategy;
  status: VerificationStatus | undefined;
}) {
  const premarket = strategy.spec.targetType === "ETF" && strategy.spec.premarket?.enabled;
  if (!premarket) {
    return <span className="muted" style={{ fontSize: 12 }}>—</span>;
  }
  if (!status) {
    return <span className="muted" style={{ fontSize: 12 }}>확인 중…</span>;
  }
  if (!status.verified) {
    return (
      <Link href="/backtest" className="pill" title={status.reason}>
        백테스트 필요
      </Link>
    );
  }
  return (
    <Link
      href="/live"
      className="badge llm"
      title={`백테스트 #${status.runId} · ${status.totalTrades}건 · ${status.spanDays}일`}
    >
      검증됨 {status.winRate?.toFixed(0)}%
    </Link>
  );
}

export default function StrategiesPage() {
  const [meta, setMeta] = useState<Meta | null>(null);
  const [strategies, setStrategies] = useState<Strategy[]>([]);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [editing, setEditing] = useState<Strategy | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [recDatasetId, setRecDatasetId] = useState<number | "">("");
  const [recommending, setRecommending] = useState(false);
  const [recMsg, setRecMsg] = useState<string | null>(null);
  const [liveStatus, setLiveStatus] = useState<Map<number, VerificationStatus>>(new Map());

  const reload = useCallback(async () => {
    try {
      setStrategies(await api.listStrategies());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    try {
      const candidates = await api.liveCandidates();
      setLiveStatus(new Map(candidates.map((c) => [c.strategyId, c.status])));
    } catch {
      // 실투자가 아예 설정돼 있지 않을 수 있습니다. 그러면 이 컬럼은 그냥 비어 있습니다.
      setLiveStatus(new Map());
    }
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const [m, ds] = await Promise.all([api.meta(), api.listDatasets()]);
        setMeta(m);
        setDatasets(ds);
        if (ds[0]) setRecDatasetId(ds[0].id);
        await reload();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [reload]);

  const recommend = async () => {
    if (recDatasetId === "") {
      setError("추천 기준 데이터셋을 선택해주세요.");
      return;
    }
    setError(null);
    setRecMsg(null);
    setRecommending(true);
    try {
      const created = await api.recommend(Number(recDatasetId));
      setRecMsg(`Claude가 ${created.length}개의 전략을 추천해 저장했습니다.`);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRecommending(false);
    }
  };

  const onSaved = async () => {
    setEditing(null);
    await reload();
  };

  const remove = async (id: number) => {
    if (!confirm("이 전략을 삭제할까요?")) return;
    try {
      await api.deleteStrategy(id);
      if (editing?.id === id) setEditing(null);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div>
      <h1>로직 관리</h1>
      <p className="subtitle">지표와 조건을 조합해 단타 규칙을 만듭니다. 저장한 전략은 백테스트에서 사용할 수 있습니다.</p>
      {error && <p className="error">{error}</p>}

      {meta && <RuleBuilder meta={meta} editing={editing} onSaved={onSaved} onCancel={() => setEditing(null)} />}

      <div className="card">
        <div className="section-title">
          <h2>🤖 Claude 전략 추천</h2>
          <span className="pill">claude CLI · API 키 불필요</span>
        </div>
        <p className="muted" style={{ marginTop: 0 }}>
          선택한 종목의 통계를 Claude Code에 보내 백테스트 가능한 전략 3~5개를 자동 생성합니다. (수십 초 소요)
        </p>
        <div className="row">
          <select
            value={recDatasetId}
            onChange={(e) => setRecDatasetId(e.target.value ? Number(e.target.value) : "")}
            style={{ minWidth: 280 }}
          >
            {datasets.length === 0 && <option value="">데이터 없음 (먼저 업로드)</option>}
            {datasets.map((d) => (
              <option key={d.id} value={d.id}>{d.symbol} ({d.kind}, {d.barIntervalMinutes}분봉 {d.barCount.toLocaleString()}개)</option>
            ))}
          </select>
          <button onClick={recommend} disabled={recommending || datasets.length === 0}>
            {recommending ? "Claude가 전략 설계 중…" : "LLM 추천 받기"}
          </button>
        </div>
        {recMsg && <p className="success">{recMsg}</p>}
      </div>

      <div className="card">
        <h2>저장된 전략 ({strategies.length})</h2>
        {strategies.length === 0 ? (
          <p className="muted">아직 저장된 전략이 없습니다.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>이름</th>
                <th>구분</th>
                <th>매수 조건</th>
                <th>익절/손절</th>
                <th>실투자</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {strategies.map((s) => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td><span className={`badge ${s.source === "LLM" ? "llm" : "user"}`}>{s.source}</span></td>
                  <td className="muted">
                    {s.spec.targetType === "ETF" && s.spec.premarket?.enabled
                      ? `🕘 장전 선물추세 (${s.spec.premarket.startTime}~${s.spec.premarket.endTime}, ±${s.spec.premarket.thresholdPct}%)`
                      : s.spec.entry.conditions.map(condText).join(` ${s.spec.entry.logic} `) || "-"}
                  </td>
                  <td className="muted">
                    {s.spec.exit.takeProfitPct != null ? `+${s.spec.exit.takeProfitPct}%` : "-"} /{" "}
                    {s.spec.exit.stopLossPct != null ? `-${s.spec.exit.stopLossPct}%` : "-"}
                    {(s.spec.exit.bands?.length ?? 0) > 0 && (
                      <>
                        {" "}
                        <span className="pill">🕐 시간대 {s.spec.exit.bands!.length}</span>
                      </>
                    )}
                  </td>
                  <td>
                    <LiveReadiness strategy={s} status={liveStatus.get(s.id)} />
                  </td>
                  <td>
                    <div className="row">
                      <button className="secondary" onClick={() => { setEditing(s); window.scrollTo({ top: 0, behavior: "smooth" }); }}>수정</button>
                      <button className="danger" onClick={() => remove(s.id)}>삭제</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
