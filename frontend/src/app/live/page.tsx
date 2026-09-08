"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type {
  Dataset,
  EtfGroup,
  LiveCandidate,
  LiveCheckResult,
  LiveConfigDto,
  LiveToday,
  VerificationStatus,
} from "@/lib/types";

/** Poll fast enough that a state change is visible, slow enough to be polite. */
const REFRESH_MS = 3000;

const MODE_LABEL: Record<string, string> = {
  DRY_RUN: "드라이런 · 주문 안 나감",
  PAPER: "모의투자",
  REAL: "실전 계좌",
};

const STATE_LABEL: Record<string, string> = {
  IDLE: "대기",
  ARMED: "준비됨",
  WATCHING: "장전 선물 관측 중",
  SKIPPED: "오늘 거래 없음",
  ENTRY_PENDING: "매수 체결 대기",
  HOLDING: "보유 중",
  EXIT_PENDING: "매도 체결 대기",
  CLOSED: "청산 완료",
  HALTED: "중단됨",
};

const won = (v: number | null | undefined) =>
  v === null || v === undefined ? "—" : `${Math.round(v).toLocaleString()}원`;
const pct = (v: number | null | undefined, digits = 2) =>
  v === null || v === undefined ? "—" : `${v.toFixed(digits)}%`;
const clock = (ts: string | null) => (ts ? ts.slice(11, 19) : "—");

export default function LivePage() {
  const [today, setToday] = useState<LiveToday | null>(null);
  const [config, setConfig] = useState<LiveConfigDto | null>(null);
  const [candidates, setCandidates] = useState<LiveCandidate[]>([]);
  const [groups, setGroups] = useState<EtfGroup[]>([]);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [check, setCheck] = useState<LiveCheckResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [futuresDraft, setFuturesDraft] = useState("");
  const editing = useRef(false);

  const reloadStatus = useCallback(async () => {
    try {
      const t = await api.liveToday();
      setToday(t);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const reloadAll = useCallback(async () => {
    try {
      const [t, c, cand, g, d] = await Promise.all([
        api.liveToday(),
        api.liveConfig(),
        api.liveCandidates(),
        api.listEtfGroups(),
        api.listDatasets(),
      ]);
      setToday(t);
      setConfig(c);
      setCandidates(cand);
      setGroups(g);
      setDatasets(d);
      if (!editing.current) setFuturesDraft(c.futuresTicker ?? "");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    reloadAll();
  }, [reloadAll]);

  // Status refreshes on its own; the settings form does not, so typing isn't interrupted.
  useEffect(() => {
    const id = setInterval(reloadStatus, REFRESH_MS);
    return () => clearInterval(id);
  }, [reloadStatus]);

  const run = async (fn: () => Promise<unknown>, ok?: string) => {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await fn();
      if (ok) setSuccess(ok);
      await reloadAll();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      await reloadAll();
    } finally {
      setBusy(false);
    }
  };

  if (!today || !config) {
    return (
      <div>
        <h1>실투자</h1>
        {error && <p className="error">{error}</p>}
        {!error && <p className="muted">불러오는 중…</p>}
      </div>
    );
  }

  const v = today.verification;
  const canArm = v.verified && config.etfGroupId !== null && !!config.futuresTicker;
  const selectedGroup = groups.find((g) => g.id === config.etfGroupId);

  return (
    <div>
      <div className="section-title">
        <h1 style={{ margin: 0 }}>실투자</h1>
        <span className={`mode-badge ${today.mode}`}>
          {today.mode === "REAL" ? "⚠ " : ""}
          {MODE_LABEL[today.mode] ?? today.mode}
        </span>
      </div>
      <p className="subtitle">
        백테스트로 검증한 로직을 한국투자증권 계좌로 그대로 돌립니다. 매매 규칙은 여기서 바꿀 수 없고{" "}
        <Link href="/strategies" style={{ textDecoration: "underline" }}>
          로직 관리
        </Link>
        에서만 바꿉니다 — 그래야 실제로 돌아가는 규칙이 백테스트한 규칙과 같습니다.
      </p>

      {error && <p className="error">{error}</p>}
      {success && <p className="success">{success}</p>}

      {/* 1. 검증 근거: 무엇을 보고 이 로직에 돈을 넣는지 */}
      <div className="card">
        <div className="section-title">
          <h2 className="sub-h">검증 근거</h2>
          {v.runId && (
            <Link href="/backtest" className="pill">
              백테스트 #{v.runId} 보기
            </Link>
          )}
        </div>
        <VerificationPanel status={v} strategyName={today.strategyName} />
      </div>

      {/* 2. 오늘: 실제로 지금 무슨 일이 일어나고 있는지 */}
      <div className="card">
        <div className="section-title">
          <h2 className="sub-h">오늘 ({today.tradeDate})</h2>
          <span className={`live-state ${today.state.toLowerCase()}`}>
            {STATE_LABEL[today.state] ?? today.state}
          </span>
        </div>

        {today.state === "HALTED" && (
          <p className="error">
            <strong>중단됨:</strong> {today.haltedReason}
            <br />
            <span className="muted">
              자동으로 재개되지 않습니다. 증권사 화면에서 보유분과 미체결 주문을 직접 확인한 뒤 다시
              활성화하세요.
            </span>
          </p>
        )}

        <div className="row" style={{ marginBottom: 12 }}>
          <span className="pill">
            {today.armed ? `오늘(${today.armedDate}) 활성화됨` : "활성화 꺼짐"}
          </span>
          <span className="pill">
            시세 경로: {today.priceSource === "WEBSOCKET" ? "실시간 체결가" : "REST 폴링"}
          </span>
          <span className="pill">장 마감 청산 {today.dayEndExitTime}</span>
          <div className="spacer" />
          {!today.armed ? (
            <button
              disabled={busy || !canArm}
              title={canArm ? undefined : v.verified ? "ETF 그룹과 선물 종목코드를 먼저 설정하세요." : v.reason}
              onClick={() => run(() => api.liveArm(), "오늘 자동매매를 켰습니다.")}
            >
              오늘 자동매매 켜기
            </button>
          ) : (
            <button
              className="secondary"
              disabled={busy}
              onClick={() => run(() => api.liveDisarm(), "자동매매를 껐습니다.")}
            >
              끄기
            </button>
          )}
        </div>

        <PremarketPanel today={today} />

        {(today.state === "HOLDING" ||
          today.state === "EXIT_PENDING" ||
          today.state === "CLOSED") && <PositionPanel today={today} />}

        {today.orders.length > 0 && (
          <>
            <h3 className="sub-h" style={{ marginTop: 18 }}>
              주문
            </h3>
            <table>
              <thead>
                <tr>
                  <th>구분</th>
                  <th>종목</th>
                  <th style={{ textAlign: "right" }}>수량</th>
                  <th>상태</th>
                  <th style={{ textAlign: "right" }}>체결</th>
                  <th style={{ textAlign: "right" }}>체결가</th>
                  <th>요청</th>
                  <th>주문번호</th>
                </tr>
              </thead>
              <tbody>
                {today.orders.map((o, i) => (
                  <tr key={i}>
                    <td>{o.side === "BUY" ? "매수" : "매도"}</td>
                    <td>{o.ticker}</td>
                    <td style={{ textAlign: "right" }}>{o.quantity.toLocaleString()}</td>
                    <td>{o.status}</td>
                    <td style={{ textAlign: "right" }}>{o.filledQuantity.toLocaleString()}</td>
                    <td style={{ textAlign: "right" }}>{won(o.filledPrice)}</td>
                    <td className="muted">{clock(o.requestedAt)}</td>
                    <td className="muted">{o.brokerOrderNo ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}

        {today.events.length > 0 && (
          <>
            <h3 className="sub-h" style={{ marginTop: 18 }}>
              기록
            </h3>
            <div className="event-log">
              <table>
                <tbody>
                  {[...today.events].reverse().map((e, i) => (
                    <tr key={i}>
                      <td className="muted" style={{ whiteSpace: "nowrap", width: 70 }}>
                        {clock(e.ts)}
                      </td>
                      <td className="muted" style={{ whiteSpace: "nowrap", width: 130 }}>
                        {e.type}
                      </td>
                      <td>{e.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>

      {/* 3. 설정: 실행 환경만. 매매 규칙은 여기 없습니다. */}
      <div className="card">
        <h2 className="sub-h">설정</h2>
        <p className="hint" style={{ marginTop: 0, marginBottom: 12 }}>
          여기서 정하는 건 <strong>어느 계좌로 얼마나</strong>입니다. 익절·손절·시간대 밴드·장전 임계치
          같은 규칙은 전략 화면에만 있습니다.
        </p>

        <div className="grid2">
          <div>
            <label>전략 (백테스트한 ETF 장전추세 전략만)</label>
            <select
              value={config.strategyId ?? ""}
              disabled={busy}
              onChange={(e) =>
                run(
                  () => api.updateLiveConfig({ strategyId: Number(e.target.value) }),
                  "전략을 바꿨습니다. 검증 근거를 다시 확인하고 활성화하세요.",
                )
              }
            >
              <option value="">선택하세요</option>
              {candidates.map((c) => (
                <option key={c.strategyId} value={c.strategyId}>
                  {c.name}
                  {c.status.verified
                    ? ` — 검증됨 (승률 ${c.status.winRate?.toFixed(1)}%, ${c.status.totalTrades}건)`
                    : " — 백테스트 필요"}
                </option>
              ))}
            </select>
            {candidates.length === 0 && (
              <p className="hint">
                실투자에 쓸 수 있는 전략이 없습니다. 전략의 대상을 ETF로 두고 장전추세를 켠 뒤
                백테스트를 한 번 돌리세요.
              </p>
            )}
          </div>

          <div>
            <label>ETF 그룹 (레버리지·인버스 종목코드 출처)</label>
            <select
              value={config.etfGroupId ?? ""}
              disabled={busy}
              onChange={(e) =>
                run(() => api.updateLiveConfig({ etfGroupId: Number(e.target.value) }), "그룹을 지정했습니다.")
              }
            >
              <option value="">선택하세요</option>
              {groups.map((g) => (
                <option key={g.id} value={g.id} disabled={!g.ready}>
                  {g.name}
                  {g.ready ? "" : " (슬롯 미완성)"}
                </option>
              ))}
            </select>
            {selectedGroup && <TickerCheck group={selectedGroup} datasets={datasets} />}
          </div>

          <div>
            <label>선물 종목코드 (장전 추세를 읽을 최근월물)</label>
            <input
              value={futuresDraft}
              placeholder="예: 101W12"
              onFocus={() => (editing.current = true)}
              onChange={(e) => setFuturesDraft(e.target.value)}
              onBlur={() => {
                editing.current = false;
                if ((config.futuresTicker ?? "") !== futuresDraft.trim()) {
                  run(
                    () => api.updateLiveConfig({ futuresTicker: futuresDraft.trim() }),
                    "선물 종목코드를 저장했습니다.",
                  );
                }
              }}
            />
            <p className="hint">
              월물이 분기마다 바뀝니다. 만기가 지나면 시세가 멈추므로 갱신하세요.
            </p>
          </div>

          <div>
            <label>1회 최대 주문금액 (원)</label>
            <input
              type="number"
              defaultValue={config.maxOrderAmount}
              onBlur={(e) => {
                const value = Number(e.target.value);
                if (value !== config.maxOrderAmount) {
                  run(() => api.updateLiveConfig({ maxOrderAmount: value }), "주문금액 상한을 저장했습니다.");
                }
              }}
            />
            <p className="hint">
              전략의 투자금액보다 이 값이 작으면 이 값을 씁니다 — 오타 한 번이 계좌를 비우지 않게 하는
              마지막 방어선입니다.
            </p>
          </div>

          <div>
            <label>일일 손실 한도 (원)</label>
            <input
              type="number"
              defaultValue={config.maxDailyLoss}
              onBlur={(e) => {
                const value = Number(e.target.value);
                if (value !== config.maxDailyLoss) {
                  run(() => api.updateLiveConfig({ maxDailyLoss: value }), "손실 한도를 저장했습니다.");
                }
              }}
            />
            <p className="hint">평가손실이 이 금액을 넘으면 손절선과 무관하게 즉시 청산합니다.</p>
          </div>

          <div>
            <label>장전 시세 조회 주기 (초)</label>
            <input
              type="number"
              min={1}
              max={60}
              defaultValue={config.pollIntervalSec}
              onBlur={(e) => {
                const value = Number(e.target.value);
                if (value !== config.pollIntervalSec) {
                  run(() => api.updateLiveConfig({ pollIntervalSec: value }), "조회 주기를 저장했습니다.");
                }
              }}
            />
          </div>

          <div>
            <label>장 마감 청산 시각</label>
            <input
              defaultValue={config.dayEndExitTime}
              onBlur={(e) => {
                if (e.target.value !== config.dayEndExitTime) {
                  run(
                    () => api.updateLiveConfig({ dayEndExitTime: e.target.value }),
                    "청산 시각을 저장했습니다.",
                  );
                }
              }}
            />
            <p className="hint">
              종가 단일가에 주문이 걸리지 않도록 여유를 둡니다. 백테스트의 &quot;마지막 봉 종가&quot;와는
              다른 시점입니다.
            </p>
          </div>

          <div>
            <label>최소 검증 거래 수</label>
            <input
              type="number"
              min={0}
              defaultValue={config.minVerifiedTrades}
              onBlur={(e) => {
                const value = Number(e.target.value);
                if (value !== config.minVerifiedTrades) {
                  run(() => api.updateLiveConfig({ minVerifiedTrades: value }), "저장했습니다.");
                }
              }}
            />
          </div>

          <div>
            <label>최소 검증 기간 (일)</label>
            <input
              type="number"
              min={0}
              defaultValue={config.minVerifiedDays}
              onBlur={(e) => {
                const value = Number(e.target.value);
                if (value !== config.minVerifiedDays) {
                  run(() => api.updateLiveConfig({ minVerifiedDays: value }), "저장했습니다.");
                }
              }}
            />
          </div>
        </div>
      </div>

      {/* 4. 점검과 정지 */}
      <div className="card danger-zone">
        <h2 className="sub-h">점검 · 긴급 정지</h2>
        <div className="row">
          <button
            className="secondary"
            disabled={busy}
            onClick={() =>
              run(async () => {
                setCheck(await api.liveCheck());
              })
            }
          >
            연결 점검 (주문 없음)
          </button>
          <div className="spacer" />
          <button
            className="danger"
            disabled={busy || today.state === "HALTED"}
            onClick={() => {
              if (!confirm("자동매매를 즉시 중단합니다. 보유분은 그대로 남습니다. 계속할까요?")) return;
              run(() => api.liveHalt("사용자가 긴급 정지했습니다.", false), "중단했습니다.");
            }}
          >
            긴급 정지
          </button>
          <button
            className="danger"
            disabled={busy || today.state !== "HOLDING"}
            onClick={() => {
              if (!confirm("보유분을 시장가로 즉시 팔고 중단합니다. 계속할까요?")) return;
              run(() => api.liveHalt("사용자가 긴급 청산했습니다.", true), "청산 주문을 냈습니다.");
            }}
          >
            정지 + 즉시 청산
          </button>
        </div>
        {check && <CheckPanel result={check} />}
      </div>

      <SessionHistory />

      <p className="hint">
        백테스트는 손절가에 정확히 체결된다고 가정하지만 실전은 실제 체결가 기준입니다. 시장가 주문의
        슬리피지와 개장 직후 스프레드 때문에 <strong>실전 성적은 백테스트보다 나쁘게 나오는 것이
        정상</strong>입니다.
      </p>
    </div>
  );
}

function VerificationPanel({
  status,
  strategyName,
}: {
  status: VerificationStatus;
  strategyName: string | null;
}) {
  if (!status.verified) {
    return (
      <div className="verify-box blocked">
        <p style={{ margin: 0 }}>
          <strong>{strategyName ?? "전략 미선택"}</strong> — 실투자를 시작할 수 없습니다.
        </p>
        <p className="muted" style={{ margin: "6px 0 0", fontSize: 13 }}>
          {status.reason}
        </p>
        <p style={{ margin: "10px 0 0" }}>
          <Link href="/backtest" className="pill">
            백테스트 하러 가기 →
          </Link>
        </p>
      </div>
    );
  }
  return (
    <div className="verify-box">
      <p style={{ margin: 0 }}>
        <strong>{strategyName}</strong> — 백테스트 #{status.runId} 결과로 검증됨
        <span className="muted" style={{ fontSize: 12 }}>
          {" "}
          ({status.fromTs?.slice(0, 10)} ~ {status.toTs?.slice(0, 10)}, {status.spanDays}일)
        </span>
      </p>
      <div className="verify-metrics">
        <div>
          <div className="k">승률</div>
          <div className="v">{pct(status.winRate, 1)}</div>
        </div>
        <div>
          <div className="k">손익비</div>
          <div className="v">
            {status.profitLossRatio === null ? "—" : status.profitLossRatio.toFixed(2)}
          </div>
        </div>
        <div>
          <div className="k">최대 낙폭</div>
          <div className="v">{pct(status.maxDrawdown, 1)}</div>
        </div>
        <div>
          <div className="k">거래 수</div>
          <div className="v">{status.totalTrades ?? "—"}건</div>
        </div>
        <div>
          <div className="k">수익금</div>
          <div className="v">{won(status.totalProfitAmount)}</div>
        </div>
      </div>
    </div>
  );
}

function PremarketPanel({ today }: { today: LiveToday }) {
  const threshold = today.thresholdPct ?? 0;
  const trend = today.trendPct;
  const ticks = today.premarketTicks;

  return (
    <div>
      <h3 className="sub-h">
        장전 선물 추세 ({today.premarketStart ?? "—"}–{today.premarketEnd ?? "—"})
      </h3>
      <div className="row" style={{ marginBottom: 8 }}>
        <span className="pill">선물 {today.futuresTicker ?? "미설정"}</span>
        <span className="pill">관측 {today.premarketSamples}건</span>
        <span className="pill">임계치 ±{threshold}%</span>
        {trend !== null && (
          <span className="pill">
            변화율 {trend >= 0 ? "+" : ""}
            {trend.toFixed(3)}%
          </span>
        )}
        {today.chosenInstrument && (
          <span className="badge llm">
            {today.chosenInstrument === "LEVERAGE" ? "레버리지 선택" : "인버스 선택"}
          </span>
        )}
      </div>
      {ticks.length >= 2 ? (
        <TrendSparkline ticks={ticks} threshold={threshold} />
      ) : (
        <p className="muted" style={{ fontSize: 13 }}>
          {today.state === "WATCHING"
            ? "선물 시세를 모으고 있습니다…"
            : "이 세션에 기록된 장전 시세가 없습니다."}
        </p>
      )}
    </div>
  );
}

/**
 * Percent change from the first sample, drawn against the threshold lines — the same first-to-last
 * measurement the decision uses, so the picture and the decision cannot disagree.
 */
function TrendSparkline({
  ticks,
  threshold,
}: {
  ticks: { ts: string; price: number }[];
  threshold: number;
}) {
  const w = 640;
  const h = 90;
  const pad = 4;
  const base = ticks[0].price;
  const changes = ticks.map((t) => ((t.price - base) / base) * 100);
  const bound = Math.max(threshold * 1.6, ...changes.map(Math.abs), 0.05);

  const x = (i: number) => pad + (i / Math.max(1, ticks.length - 1)) * (w - 2 * pad);
  const y = (c: number) => h / 2 - (c / bound) * (h / 2 - pad);
  const path = changes.map((c, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(c).toFixed(1)}`).join(" ");
  const last = changes[changes.length - 1];
  const fired = Math.abs(last) >= threshold;

  return (
    <svg viewBox={`0 0 ${w} ${h}`} style={{ width: "100%", height: h }}>
      <line x1={pad} y1={y(0)} x2={w - pad} y2={y(0)} stroke="var(--border)" strokeWidth="1" />
      <line
        x1={pad}
        y1={y(threshold)}
        x2={w - pad}
        y2={y(threshold)}
        stroke="var(--accent-2)"
        strokeWidth="1"
        strokeDasharray="4 4"
      />
      <line
        x1={pad}
        y1={y(-threshold)}
        x2={w - pad}
        y2={y(-threshold)}
        stroke="var(--danger)"
        strokeWidth="1"
        strokeDasharray="4 4"
      />
      <path
        d={path}
        fill="none"
        stroke={fired ? (last > 0 ? "var(--accent-2)" : "var(--danger)") : "var(--muted)"}
        strokeWidth="2"
      />
      <circle
        cx={x(changes.length - 1)}
        cy={y(last)}
        r="3"
        fill={fired ? (last > 0 ? "var(--accent-2)" : "var(--danger)") : "var(--muted)"}
      />
    </svg>
  );
}

function PositionPanel({ today }: { today: LiveToday }) {
  const profit = today.state === "CLOSED" ? today.profitAmount : today.unrealisedProfit;
  const sign = profit === null || profit === undefined ? "" : profit >= 0 ? "pos" : "neg";
  return (
    <>
      <h3 className="sub-h" style={{ marginTop: 18 }}>
        포지션 ({today.chosenTicker})
      </h3>
      <div className="stat-grid">
        <div className="stat">
          <div className="k">진입가 × 수량</div>
          <div className="v" style={{ fontSize: 16 }}>
            {won(today.entryPrice)} × {today.quantity?.toLocaleString() ?? "—"}
          </div>
        </div>
        <div className="stat">
          <div className="k">{today.state === "CLOSED" ? "청산가" : "현재가"}</div>
          <div className="v" style={{ fontSize: 16 }}>
            {won(today.state === "CLOSED" ? today.exitPrice : today.currentPrice)}
          </div>
        </div>
        <div className="stat">
          <div className="k">{today.state === "CLOSED" ? "실현 손익" : "평가 손익"}</div>
          <div className={`v ${sign}`} style={{ fontSize: 16 }}>
            {won(profit)}
          </div>
        </div>
        <div className="stat">
          <div className="k">{today.state === "CLOSED" ? "청산 사유" : "적용 중 손절 / 익절"}</div>
          <div className="v" style={{ fontSize: 16 }}>
            {today.state === "CLOSED"
              ? (today.exitReason ?? "—")
              : `${won(today.stopPrice)} / ${won(today.takeProfitPrice)}`}
          </div>
        </div>
      </div>
      {today.state === "HOLDING" && (
        <p className="hint">
          손절·익절 가격은 시간대 밴드에 따라 시간이 지나면서 움직입니다 — 지금 시각 기준입니다.
        </p>
      )}
    </>
  );
}

/** Live orders need exchange codes; the display symbol can't be ordered against. */
function TickerCheck({ group, datasets }: { group: EtfGroup; datasets: Dataset[] }) {
  const rows = [
    { label: "레버리지", slot: group.leverage },
    { label: "인버스", slot: group.inverse },
  ];
  const missing = rows.filter(
    (r) => r.slot && !datasets.find((d) => d.id === r.slot!.datasetId)?.ticker,
  );
  if (missing.length === 0) {
    return (
      <p className="hint">
        {rows
          .map((r) => {
            const t = datasets.find((d) => d.id === r.slot?.datasetId)?.ticker;
            return `${r.label} ${t ?? "—"}`;
          })
          .join(" · ")}
      </p>
    );
  }
  return (
    <p className="error" style={{ fontSize: 12 }}>
      {missing.map((m) => m.label).join(", ")} 데이터셋에 종목코드가 없어 주문할 수 없습니다.{" "}
      <Link href="/datasets" style={{ textDecoration: "underline" }}>
        데이터 화면
      </Link>
      에서 6자리 코드를 입력하세요.
    </p>
  );
}

function CheckPanel({ result }: { result: LiveCheckResult }) {
  return (
    <div style={{ marginTop: 12 }}>
      <div className="row">
        <span className="pill">계좌 {result.account}</span>
        <span className="pill">인증 {result.tokenOk ? "OK" : "실패"}</span>
        <span className="pill">잔고 {result.balanceOk ? "OK" : "실패"}</span>
        {result.cashAvailable !== null && result.cashAvailable < 1e12 && (
          <span className="pill">주문가능현금 {won(result.cashAvailable)}</span>
        )}
        {result.futuresPrice !== null && (
          <span className="pill">
            선물 {result.futuresTicker} {result.futuresPrice.toLocaleString()}
            {result.futuresEstimated ? " (예상체결가)" : ""}
          </span>
        )}
      </div>
      {result.problems.length > 0 && (
        <ul className="hint" style={{ marginTop: 8, paddingLeft: 18 }}>
          {result.problems.map((p, i) => (
            <li key={i}>{p}</li>
          ))}
        </ul>
      )}
      {result.problems.length === 0 && <p className="success">문제 없습니다.</p>}
    </div>
  );
}

function SessionHistory() {
  const [rows, setRows] = useState<Awaited<ReturnType<typeof api.liveSessions>>>([]);

  useEffect(() => {
    api.liveSessions().then(setRows).catch(() => setRows([]));
  }, []);

  if (rows.length === 0) return null;
  return (
    <div className="card">
      <h2 className="sub-h">실투자 이력</h2>
      <table>
        <thead>
          <tr>
            <th>날짜</th>
            <th>모드</th>
            <th>상태</th>
            <th>전략</th>
            <th style={{ textAlign: "right" }}>장전 변화율</th>
            <th>선택</th>
            <th style={{ textAlign: "right" }}>진입</th>
            <th style={{ textAlign: "right" }}>청산</th>
            <th>사유</th>
            <th style={{ textAlign: "right" }}>손익</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.sessionId}>
              <td>{s.tradeDate}</td>
              <td>
                <span className={`mode-badge ${s.mode}`} style={{ fontSize: 10, padding: "2px 8px" }}>
                  {s.mode}
                </span>
              </td>
              <td>
                <span className={`live-state ${s.state.toLowerCase()}`}>
                  {STATE_LABEL[s.state] ?? s.state}
                </span>
              </td>
              <td className="muted">{s.strategyName ?? "—"}</td>
              <td style={{ textAlign: "right" }}>
                {s.trendPct === null ? "—" : `${s.trendPct >= 0 ? "+" : ""}${s.trendPct.toFixed(3)}%`}
              </td>
              <td>{s.chosenInstrument ?? "—"}</td>
              <td style={{ textAlign: "right" }}>{won(s.entryPrice)}</td>
              <td style={{ textAlign: "right" }}>{won(s.exitPrice)}</td>
              <td className="muted">{s.exitReason ?? s.haltedReason ?? "—"}</td>
              <td
                style={{ textAlign: "right" }}
                className={
                  s.profitAmount === null ? "" : s.profitAmount >= 0 ? "" : "error"
                }
              >
                {won(s.profitAmount)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
