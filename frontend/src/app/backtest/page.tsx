"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import type { BacktestListItem, BacktestResponse, Dataset, EtfGroup, StoredTrade, Strategy } from "@/lib/types";
import EquityCurve from "@/components/EquityCurve";

const REASON_LABEL: Record<string, string> = {
  STOP_LOSS: "손절",
  TAKE_PROFIT: "익절",
  SIGNAL: "시그널",
  TIME: "시간청산",
  DAY_END: "당일청산",
  END_OF_DATA: "데이터종료",
};

const pct = (n: number) => `${n >= 0 ? "" : ""}${n.toFixed(2)}%`;
const fmtTs = (s: string) => s.replace("T", " ");

const CAPITAL_MODE_LABEL: Record<string, string> = { FIXED: "고정 금액", COMPOUND: "복리 재투자" };

const SEVERITY_LABEL: Record<string, string> = { HIGH: "심각", MEDIUM: "주의", INFO: "참고" };

const INSTRUMENT_LABEL: Record<string, string> = { SINGLE: "", LEVERAGE: "레버리지 ", INVERSE: "인버스 " };

/** "0.015%" for a single symbol, "레버리지 0.01% / 인버스 0.1%" when the sides differ. */
function feeText(rates: Record<string, number> | undefined): string {
  const entries = Object.entries(rates ?? {});
  if (entries.length === 0) return "-";
  return entries.map(([k, v]) => `${INSTRUMENT_LABEL[k] ?? `${k} `}${v}%`).join(" / ");
}

/** Won, rounded to the nearest won and signed so gains and losses read at a glance. */
const won = (n: number) => `${n < 0 ? "-" : ""}${Math.abs(Math.round(n)).toLocaleString()}원`;
const wonSigned = (n: number) => `${n > 0 ? "+" : n < 0 ? "-" : ""}${Math.abs(Math.round(n)).toLocaleString()}원`;

function Stat({ k, v, tone }: { k: string; v: string; tone?: "pos" | "neg" | "warn" }) {
  return (
    <div className="stat">
      <div className="k">{k}</div>
      <div className={`v ${tone ?? ""}`}>{v}</div>
    </div>
  );
}

const PER_PAGE = 50;

export default function BacktestPage() {
  const [strategies, setStrategies] = useState<Strategy[]>([]);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [etfGroups, setEtfGroups] = useState<EtfGroup[]>([]);
  const [history, setHistory] = useState<BacktestListItem[]>([]);
  const [strategyId, setStrategyId] = useState<number | "">("");
  const [datasetId, setDatasetId] = useState<number | "">("");
  const [groupId, setGroupId] = useState<number | "">("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [result, setResult] = useState<BacktestResponse | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const reloadHistory = useCallback(async () => {
    try {
      setHistory(await api.listBacktests());
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const [s, d, g] = await Promise.all([api.listStrategies(), api.listDatasets(), api.listEtfGroups()]);
        setStrategies(s);
        setDatasets(d);
        setEtfGroups(g);
        if (s[0]) setStrategyId(s[0].id);
        if (d[0]) setDatasetId(d[0].id);
        const readyGroup = g.find((x) => x.ready);
        if (readyGroup) setGroupId(readyGroup.id);
        await reloadHistory();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [reloadHistory]);

  const readyGroups = etfGroups.filter((g) => g.ready);
  const selectedStrategy = strategies.find((s) => s.id === strategyId);
  const usesPremarket =
    selectedStrategy?.spec.targetType === "ETF" && !!selectedStrategy?.spec.premarket?.enabled;

  /**
   * The dates the current selection actually covers. Pre-market mode reads three datasets, so the
   * usable window is their overlap — the widest range where all three have bars.
   */
  const available = useMemo(() => {
    const pick = (id: number | null | undefined) => datasets.find((d) => d.id === id);
    const members: (Dataset | undefined)[] = usesPremarket
      ? (() => {
          const g = etfGroups.find((x) => x.id === groupId);
          return [pick(g?.leverage?.datasetId), pick(g?.inverse?.datasetId), pick(g?.futures?.datasetId)];
        })()
      : [pick(typeof datasetId === "number" ? datasetId : undefined)];
    const froms = members.map((d) => d?.fromTs?.slice(0, 10)).filter(Boolean) as string[];
    const tos = members.map((d) => d?.toTs?.slice(0, 10)).filter(Boolean) as string[];
    if (froms.length === 0 || tos.length === 0) return null;
    return { from: froms.sort().at(-1)!, to: tos.sort()[0] };
  }, [datasets, etfGroups, usesPremarket, groupId, datasetId]);

  // Follow the selection with the full span until the user types their own dates.
  const [dateTouched, setDateTouched] = useState(false);
  useEffect(() => {
    if (dateTouched || !available) return;
    setFromDate(available.from);
    setToDate(available.to);
  }, [available, dateTouched]);

  const setRange = (from: string, to: string) => {
    setDateTouched(true);
    setFromDate(from);
    setToDate(to);
  };

  /** Last N months up to the data's end — the common "recent period only" check. */
  const setRecentMonths = (months: number) => {
    if (!available) return;
    const end = new Date(`${available.to}T00:00:00`);
    const start = new Date(end);
    start.setMonth(start.getMonth() - months);
    const iso = start.toISOString().slice(0, 10);
    setRange(iso < available.from ? available.from : iso, available.to);
  };

  const run = async () => {
    if (strategyId === "") {
      setError("전략을 선택해주세요.");
      return;
    }
    if (usesPremarket ? groupId === "" : datasetId === "") {
      setError(usesPremarket ? "ETF 그룹을 선택해주세요." : "데이터셋을 선택해주세요.");
      return;
    }
    if (fromDate && toDate && fromDate > toDate) {
      setError("시작일이 종료일보다 늦습니다.");
      return;
    }
    setError(null);
    setRunning(true);
    setResult(null);
    try {
      const r = await api.runBacktest(Number(strategyId), {
        ...(usesPremarket ? { groupId: Number(groupId) } : { datasetId: Number(datasetId) }),
        fromDate: fromDate || null,
        toDate: toDate || null,
      });
      setResult(r);
      setPage(0);
      await reloadHistory();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRunning(false);
    }
  };

  const openRun = async (id: number) => {
    setError(null);
    try {
      const r = await api.getBacktest(id);
      setResult(r);
      setPage(0);
      window.scrollTo({ top: 0, behavior: "smooth" });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const hourMax = useMemo(() => {
    if (!result) return 1;
    return Math.max(1, ...Object.values(result.failureByHour).map((h) => h.lossCount));
  }, [result]);

  // Cumulative won profit, one point per closed trade (net of commission, so the last
  // point equals the 총 수익금 card). Trades come back ordered by entry and the engine
  // holds one position at a time, so entry order is exit order.
  const profitCurve = useMemo(() => {
    if (!result?.money || result.trades.length === 0) return [];
    let sum = 0;
    const pts = result.trades.map((t) => {
      sum += t.profitAmount ?? 0;
      return { ts: t.exitTs, value: sum };
    });
    return [{ ts: result.trades[0].entryTs, value: 0 }, ...pts];
  }, [result]);

  const s = result?.summary;
  // Newest first for the table. result.trades stays chronological — profitCurve accumulates it in order.
  const trades = useMemo(() => (result ? result.trades.slice().reverse() : []), [result]);
  const hasInstrument = trades.some((t) => !!t.instrument);
  // Runs recorded before the money feature have no summary, and their trade rows read back as 0 —
  // key off the summary so those older runs don't show a column of zeroes.
  const hasMoney = !!result?.money;
  const pageTrades = trades.slice(page * PER_PAGE, page * PER_PAGE + PER_PAGE);
  const totalPages = Math.ceil(trades.length / PER_PAGE);

  return (
    <div>
      <h1>백테스트</h1>
      <p className="subtitle">전략과 데이터를 골라 백테스트하고 성공/실패율과 실패 원인을 분석합니다.</p>

      <div className="card">
        <div className="grid2">
          <div>
            <label>전략</label>
            <select value={strategyId} onChange={(e) => setStrategyId(e.target.value ? Number(e.target.value) : "")} style={{ width: "100%" }}>
              {strategies.length === 0 && <option value="">전략 없음 (로직 관리에서 먼저 생성)</option>}
              {strategies.map((st) => (
                <option key={st.id} value={st.id}>{st.name} ({st.source})</option>
              ))}
            </select>
          </div>
          {usesPremarket ? (
            <div>
              <label>ETF 그룹 (레버리지+인버스+선물)</label>
              <select value={groupId} onChange={(e) => setGroupId(e.target.value ? Number(e.target.value) : "")} style={{ width: "100%" }}>
                {etfGroups.length === 0 && <option value="">저장된 ETF 그룹 없음 (ETF 그룹 화면에서 먼저 생성)</option>}
                {readyGroups.length === 0 && etfGroups.length > 0 && <option value="">실행 가능한 그룹 없음 — 아래에서 슬롯을 채워주세요</option>}
                {/* Incomplete groups stay listed but disabled: hiding them made a saved group look lost. */}
                {etfGroups.map((g) => (
                  <option key={g.id} value={g.id} disabled={!g.ready}>
                    {g.name}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <div>
              <label>데이터셋</label>
              <select value={datasetId} onChange={(e) => setDatasetId(e.target.value ? Number(e.target.value) : "")} style={{ width: "100%" }}>
                {datasets.length === 0 && <option value="">데이터 없음 (데이터 업로드에서 먼저 등록)</option>}
                {datasets.map((d) => (
                  <option key={d.id} value={d.id}>{d.symbol} ({d.kind}, {d.barCount.toLocaleString()}봉)</option>
                ))}
              </select>
            </div>
          )}
        </div>
        <div className="grid2" style={{ marginTop: 12 }}>
          <div>
            <label>시작일</label>
            <input
              type="date"
              value={fromDate}
              min={available?.from}
              max={available?.to}
              onChange={(e) => setRange(e.target.value, toDate)}
              style={{ width: "100%" }}
            />
          </div>
          <div>
            <label>종료일 (당일 포함)</label>
            <input
              type="date"
              value={toDate}
              min={available?.from}
              max={available?.to}
              onChange={(e) => setRange(fromDate, e.target.value)}
              style={{ width: "100%" }}
            />
          </div>
        </div>
        <div className="row" style={{ marginTop: 8 }}>
          <button type="button" className="ghost" onClick={() => available && setRange(available.from, available.to)}>
            전체 기간
          </button>
          <button type="button" className="ghost" onClick={() => setRecentMonths(1)}>최근 1개월</button>
          <button type="button" className="ghost" onClick={() => setRecentMonths(3)}>최근 3개월</button>
          <button type="button" className="ghost" onClick={() => setRecentMonths(6)}>최근 6개월</button>
          <button type="button" className="ghost" onClick={() => setRecentMonths(12)}>최근 1년</button>
        </div>
        <p className="hint">
          {available
            ? `선택한 데이터에 있는 기간: ${available.from} ~ ${available.to}${usesPremarket ? " (레버리지·인버스·선물 3개가 모두 있는 구간)" : ""}`
            : "데이터를 선택하면 사용 가능한 기간이 표시됩니다."}
        </p>
        {usesPremarket && (
          <p className="hint">🕘 이 전략은 장전 선물추세 모드입니다. 매일 08:45~09:00 선물 추세로 레버리지/인버스를 선택합니다.</p>
        )}
        {error && <p className="error">{error}</p>}
        <div className="row" style={{ marginTop: 14 }}>
          <button
            onClick={run}
            disabled={running || strategies.length === 0 || (usesPremarket ? readyGroups.length === 0 : datasets.length === 0)}
          >
            {running ? "백테스트 실행 중…" : "백테스트 실행"}
          </button>
        </div>
      </div>

      {s && result && (
        <>
          <div className="card">
            <div className="section-title">
              <h2>{result.strategyName} × {result.datasetSymbol}</h2>
              <span className="pill">run #{result.runId} · {fmtTs(result.createdAt).slice(0, 19)}</span>
            </div>
            {result.params && (
              <p className="hint" style={{ marginTop: 0 }}>
                기간 {result.params.barFromTs.slice(0, 10)} ~ {result.params.barToTs.slice(0, 10)}
                {" · "}{result.params.barCount.toLocaleString()}봉
                {result.params.fromDate || result.params.toDate ? " (지정 기간)" : " (전체 기간)"}
              </p>
            )}
            <div className="stat-grid">
              <Stat k="성공률" v={pct(s.winRate)} tone="pos" />
              <Stat k="실패율" v={pct(s.lossRate)} tone="neg" />
              <Stat k="총 거래" v={`${s.totalTrades.toLocaleString()}건`} />
              <Stat k="성공 / 실패" v={`${s.wins} / ${s.losses}`} />
              <Stat k="복리 수익" v={pct(s.compoundedReturnPct)} tone={s.compoundedReturnPct >= 0 ? "pos" : "neg"} />
              <Stat k="최대 낙폭 (MDD)" v={pct(s.maxDrawdownPct)} tone="warn" />
              <Stat k="평균 이익 / 손실" v={`${pct(s.avgWinPct)} / ${pct(s.avgLossPct)}`} />
              <Stat k="최대 연속 손실" v={`${s.maxConsecutiveLosses}회`} tone="warn" />
            </div>
          </div>

          {result.money && (
            <div className="card">
              <div className="section-title">
                <h2>수익 금액</h2>
                <span className="pill">
                  {CAPITAL_MODE_LABEL[result.money.mode] ?? result.money.mode}
                  {" · "}
                  {result.money.mode === "FIXED" ? "1회" : "시작"} {won(result.money.investAmount)}
                  {" · 수수료 "}{feeText(result.money.feeRatesPct)}
                </span>
              </div>
              <div className="stat-grid">
                <Stat
                  k="총 수익금 (수수료 차감)"
                  v={wonSigned(result.money.totalProfitAmount)}
                  tone={result.money.totalProfitAmount >= 0 ? "pos" : "neg"}
                />
                <Stat
                  k={result.money.mode === "FIXED" ? "투자금 + 누적 수익" : "최종 잔고"}
                  v={won(result.money.finalBalance)}
                  tone={result.money.finalBalance >= result.money.investAmount ? "pos" : "neg"}
                />
                <Stat
                  k="투자금 대비"
                  v={pct(result.money.returnOnCapitalPct)}
                  tone={result.money.returnOnCapitalPct >= 0 ? "pos" : "neg"}
                />
                <Stat k="총 수수료" v={won(result.money.totalFeeAmount)} tone="warn" />
                <Stat
                  k="거래당 평균 손익"
                  v={wonSigned(result.money.avgProfitPerTrade)}
                  tone={result.money.avgProfitPerTrade >= 0 ? "pos" : "neg"}
                />
                <Stat k="최고 수익 거래" v={wonSigned(result.money.bestTradeAmount)} tone="pos" />
                <Stat k="최대 손실 거래" v={wonSigned(result.money.worstTradeAmount)} tone="neg" />
                <Stat
                  k="매수 불가 (금액 부족)"
                  v={`${result.money.unaffordableTrades.toLocaleString()}건`}
                  tone={result.money.unaffordableTrades > 0 ? "warn" : undefined}
                />
              </div>
              {result.money.unaffordableTrades > 0 && (
                <p className="hint">
                  {result.money.unaffordableTrades.toLocaleString()}건은 투자금이 1주 값보다 적어 매수하지 못한 것으로 계산했습니다
                  {result.money.mode === "COMPOUND" ? " (복리 모드에서 잔고가 줄어든 경우 포함)" : ""}. 전략의 투자금액을 올려보세요.
                </p>
              )}
              <p className="hint">
                수수료는 종목별 요율({feeText(result.money.feeRatesPct)})을 매수·매도 양쪽에 각각 적용했습니다. 성공률·수익률 지표는 수수료를 빼지 않은 가격 기준이라, 수수료가 클수록 위쪽 %와 이 금액의 방향이 달라질 수 있습니다.
              </p>
            </div>
          )}

          {result.diagnosis && (
            <div className="card">
              <div className="section-title">
                <h2>자동 진단</h2>
                <span className="pill">규칙 기반 · 참고용</span>
              </div>
              <p className="diag-headline">{result.diagnosis.headline}</p>
              {result.diagnosis.findings.length === 0 ? (
                <p className="muted" style={{ margin: 0 }}>짚어낼 만한 문제 패턴이 없습니다.</p>
              ) : (
                result.diagnosis.findings.map((f, i) => (
                  <div className={`finding sev-${f.severity}`} key={i}>
                    <div className="f-title">
                      <span className={`badge sev-${f.severity}`}>{SEVERITY_LABEL[f.severity] ?? f.severity}</span>
                      {f.title}
                    </div>
                    <p className="f-detail">{f.detail}</p>
                    <p className="f-fix">→ {f.suggestion}</p>
                  </div>
                ))
              )}
            </div>
          )}

          <div className="card">
            {result.money && profitCurve.length >= 2 ? (
              <>
                <div className="section-title">
                  <h2>누적 수익금 곡선</h2>
                  <span className="pill">
                    수수료 차감 후 · {result.money.mode === "FIXED" ? "1회" : "시작"} {won(result.money.investAmount)}
                  </span>
                </div>
                <EquityCurve
                  points={profitCurve}
                  baseline={0}
                  format={wonSigned}
                  sub={(v) =>
                    result.money!.investAmount > 0
                      ? `투자금 대비 ${pct((v / result.money!.investAmount) * 100)}`
                      : ""
                  }
                />
              </>
            ) : (
              <>
                <h2>자본 곡선 (복리, 시작 1.0)</h2>
                <EquityCurve
                  points={result.equityCurve.map((p) => ({ ts: p.ts, value: p.equity }))}
                  baseline={1}
                  format={(v) => v.toFixed(4)}
                  sub={(v) => `누적 ${pct((v - 1) * 100)}`}
                />
              </>
            )}
          </div>

          <div className="grid2">
            <div className="card">
              <h2>실패 원인별 분석</h2>
              <table>
                <thead>
                  <tr><th>청산 사유</th><th>손실 건수</th><th>평균 손익</th><th>누적 손익</th></tr>
                </thead>
                <tbody>
                  {Object.entries(result.failureByReason).length === 0 && (
                    <tr><td colSpan={4} className="muted">손실 거래가 없습니다.</td></tr>
                  )}
                  {Object.entries(result.failureByReason).map(([reason, st2]) => (
                    <tr key={reason}>
                      <td><span className="reason-tag">{REASON_LABEL[reason] ?? reason}</span></td>
                      <td>{st2.count}건</td>
                      <td className="trade-neg">{pct(st2.avgReturnPct)}</td>
                      <td className="trade-neg">{pct(st2.totalReturnPct)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="hint">가장 많은 실패가 발생한 청산 방식을 확인해 손절폭·시그널을 조정하세요.</p>
            </div>

            <div className="card">
              <h2>시간대별 실패 (진입 시각 기준)</h2>
              {Object.keys(result.failureByHour).length === 0 ? (
                <p className="muted">손실 거래가 없습니다.</p>
              ) : (
                Object.entries(result.failureByHour).map(([hour, h]) => (
                  <div className="bar-row" key={hour}>
                    <span className="muted">{hour}시</span>
                    <div className="bar-track">
                      <div className="bar-fill" style={{ width: `${(h.lossCount / hourMax) * 100}%` }} />
                    </div>
                    <span className="muted">{h.lossCount}건 ({pct(h.avgLossPct)})</span>
                  </div>
                ))
              )}
            </div>
          </div>

          <div className="card">
            <div className="section-title">
              <h2>거래 내역 ({trades.length.toLocaleString()}건)</h2>
              <div className="row">
                <span className="pill">최신 거래부터</span>
                <button className="ghost" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>이전</button>
                <span className="muted">{page + 1} / {totalPages}</span>
                <button className="ghost" disabled={page >= totalPages - 1} onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}>다음</button>
              </div>
            </div>
            <table>
              <thead>
                <tr><th>#</th>{hasInstrument && <th>종목</th>}<th>진입</th><th>청산</th><th>진입가</th><th>청산가</th>{hasMoney && <th>수량</th>}<th>수익률</th>{hasMoney && <th>손익금액</th>}<th>사유</th><th>결과</th></tr>
              </thead>
              <tbody>
                {pageTrades.map((t: StoredTrade, i) => (
                  <tr key={page * PER_PAGE + i}>
                    {/* Chronological trade number, so the newest row carries the highest #. */}
                    <td className="muted">{trades.length - (page * PER_PAGE + i)}</td>
                    {hasInstrument && (
                      <td>
                        {t.instrument === "LEVERAGE" ? <span className="trade-pos">레버리지</span>
                          : t.instrument === "INVERSE" ? <span className="trade-neg">인버스</span>
                          : <span className="muted">-</span>}
                      </td>
                    )}
                    <td className="muted">{fmtTs(t.entryTs)}</td>
                    <td className="muted">{fmtTs(t.exitTs)}</td>
                    <td>{t.entryPrice.toLocaleString()}</td>
                    <td>{t.exitPrice.toLocaleString()}</td>
                    {hasMoney && <td>{(t.quantity ?? 0).toLocaleString()}주</td>}
                    <td className={t.returnPct >= 0 ? "trade-pos" : "trade-neg"}>{pct(t.returnPct)}</td>
                    {hasMoney && (
                      <td className={(t.profitAmount ?? 0) >= 0 ? "trade-pos" : "trade-neg"}>
                        {wonSigned(t.profitAmount ?? 0)}
                      </td>
                    )}
                    <td><span className="reason-tag">{REASON_LABEL[t.exitReason] ?? t.exitReason}</span></td>
                    <td>{t.success ? <span className="trade-pos">성공</span> : <span className="trade-neg">실패</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <div className="card">
        <h2>지난 백테스트 ({history.length.toLocaleString()}건)</h2>
        {history.length === 0 ? (
          <p className="muted">아직 실행한 백테스트가 없습니다. 실행하면 DB에 저장되고 여기에 전부 남습니다.</p>
        ) : (
          <table>
            <thead>
              <tr><th>run</th><th>전략</th><th>데이터</th><th>기간</th><th>성공률</th><th>복리수익</th><th>수익금</th><th>실행 시각</th><th></th></tr>
            </thead>
            <tbody>
              {history.map((h) => (
                <tr key={h.runId}>
                  <td className="muted">{h.runId}</td>
                  <td>{h.strategyName}</td>
                  <td className="muted">{h.datasetSymbol}</td>
                  <td className="muted">
                    {h.params ? `${h.params.barFromTs.slice(2, 10)} ~ ${h.params.barToTs.slice(2, 10)}` : "-"}
                  </td>
                  <td className="trade-pos">{h.summary ? pct(h.summary.winRate) : "-"}</td>
                  <td className={h.summary && h.summary.compoundedReturnPct >= 0 ? "trade-pos" : "trade-neg"}>
                    {h.summary ? pct(h.summary.compoundedReturnPct) : "-"}
                  </td>
                  <td className={h.money && h.money.totalProfitAmount >= 0 ? "trade-pos" : "trade-neg"}>
                    {h.money ? wonSigned(h.money.totalProfitAmount) : "-"}
                  </td>
                  <td className="muted">{fmtTs(h.createdAt).slice(0, 19)}</td>
                  <td><button className="secondary" onClick={() => openRun(h.runId)}>결과 보기</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
