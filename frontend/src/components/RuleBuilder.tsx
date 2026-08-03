"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type {
  CapitalSpec,
  Condition,
  ConditionGroup,
  ExitSpec,
  Meta,
  PremarketSpec,
  Strategy,
  StrategySpec,
  TimeBand,
} from "@/lib/types";

const OP_LABEL: Record<string, string> = {
  GT: "> (초과)",
  GTE: ">= (이상)",
  LT: "< (미만)",
  LTE: "<= (이하)",
  EQ: "= (같음)",
  CROSS_ABOVE: "상향돌파",
  CROSS_BELOW: "하향돌파",
};

/** KRX intraday defaults: volatile open, quiet midday, closing hour. */
const BAND_PRESET: TimeBand[] = [
  { startTime: "09:00", endTime: "10:00", takeProfitPct: 2.0, stopLossPct: 1.5 },
  { startTime: "10:00", endTime: "14:00", takeProfitPct: 1.0, stopLossPct: 0.7 },
  { startTime: "14:00", endTime: "15:20", takeProfitPct: 0.6, stopLossPct: 0.5 },
];

function newBand(bands: TimeBand[]): TimeBand {
  // Start where the last band ended, so consecutive bands are the easy path.
  const last = bands[bands.length - 1];
  return { startTime: last?.endTime || "09:00", endTime: "15:20", takeProfitPct: null, stopLossPct: null };
}

/**
 * Take-profit / stop-loss as one table: the fixed first row is the ExitSpec base ("전체"), and each
 * extra row is a time band. Bands are matched against the bar being checked, so the levels move
 * while a position is held — the per-row summary spells that out because it surprises people.
 */
function ExitLevels({ exit, onChange }: { exit: ExitSpec; onChange: (e: ExitSpec) => void }) {
  const bands = exit.bands ?? [];
  const setBands = (b: TimeBand[]) => onChange({ ...exit, bands: b });
  const updateBand = (i: number, next: TimeBand) => {
    const copy = bands.slice();
    copy[i] = next;
    setBands(copy);
  };
  const numOrNull = (v: string): number | null => (v === "" ? null : Number(v));
  const pctText = (v: number | null | undefined, baseV: number | null | undefined, sign: string) =>
    v == null || Number.isNaN(v) ? `기본 ${baseV == null ? "없음" : `${sign}${baseV}%`}` : `${sign}${v}%`;

  return (
    <div>
      <div className="band-row band-head">
        <span>적용 시간</span>
        <span>익절 %</span>
        <span>손절 %</span>
        <span />
      </div>

      <div className="band-row">
        <span className="band-base-label">
          전체 <span className="muted">(기본)</span>
        </span>
        <input
          type="number"
          step="0.1"
          placeholder="없음"
          value={exit.takeProfitPct ?? ""}
          onChange={(e) => onChange({ ...exit, takeProfitPct: numOrNull(e.target.value) })}
        />
        <input
          type="number"
          step="0.1"
          placeholder="없음"
          value={exit.stopLossPct ?? ""}
          onChange={(e) => onChange({ ...exit, stopLossPct: numOrNull(e.target.value) })}
        />
        <span />
      </div>
      <p className="band-summary">
        아래 시간대에 걸리지 않는 시각에 적용됩니다. 비우면 그 규칙은 쓰지 않습니다.
      </p>

      {bands.map((b, i) => (
        <div key={i}>
          <div className="band-row">
            <span className="band-time">
              <input type="time" value={b.startTime} onChange={(e) => updateBand(i, { ...b, startTime: e.target.value })} />
              <span className="muted">~</span>
              <input type="time" value={b.endTime} onChange={(e) => updateBand(i, { ...b, endTime: e.target.value })} />
            </span>
            <input
              type="number"
              step="0.1"
              placeholder="기본값"
              value={b.takeProfitPct ?? ""}
              onChange={(e) => updateBand(i, { ...b, takeProfitPct: numOrNull(e.target.value) })}
            />
            <input
              type="number"
              step="0.1"
              placeholder="기본값"
              value={b.stopLossPct ?? ""}
              onChange={(e) => updateBand(i, { ...b, stopLossPct: numOrNull(e.target.value) })}
            />
            <button type="button" className="ghost" onClick={() => setBands(bands.filter((_, j) => j !== i))}>
              삭제
            </button>
          </div>
          <p className="band-summary">
            {b.startTime || "??:??"} 이상 ~ {b.endTime || "??:??"} 미만 → 익절{" "}
            {pctText(b.takeProfitPct, exit.takeProfitPct, "+")} / 손절 {pctText(b.stopLossPct, exit.stopLossPct, "-")}
          </p>
        </div>
      ))}

      <div className="row" style={{ marginTop: 4 }}>
        <button type="button" className="secondary" onClick={() => setBands([...bands, newBand(bands)])}>
          + 시간대 추가
        </button>
        {bands.length === 0 && (
          <button type="button" className="ghost" onClick={() => setBands(BAND_PRESET.map((b) => ({ ...b })))}>
            예시로 3구간 채우기
          </button>
        )}
      </div>
      <p className="hint">
        시간대를 추가하면 그 시간에는 &lsquo;전체&rsquo; 대신 그 줄의 값이 쓰입니다(빈칸은 전체 값). 보유 중에도{" "}
        <b>지금 보고 있는 봉의 시각</b>으로 다시 판단하므로, 09:20에 산 뒤 10:00을 넘기면 그때부터 10시 줄의 익절·손절선이 적용됩니다.
        겹치는 시간대는 위에 있는 줄이 우선입니다. <Link href="/guide#time-bands" target="_blank">자세히</Link>
      </p>
    </div>
  );
}

/** Mirrors StrategyService.validateBands so the message shows without a round trip. */
function validateBands(bands: TimeBand[]): string | null {
  for (let i = 0; i < bands.length; i++) {
    const b = bands[i];
    const where = `${i + 1}번째 시간대: `;
    if (!b.startTime || !b.endTime) return `${where}시작·종료 시각을 입력해주세요.`;
    if (b.startTime >= b.endTime) {
      return `${where}종료 시각(${b.endTime})이 시작 시각(${b.startTime})보다 뒤여야 합니다.`;
    }
    if (b.takeProfitPct == null && b.stopLossPct == null) {
      return `${where}익절 % 또는 손절 % 중 최소 하나는 입력해주세요.`;
    }
  }
  return null;
}

function newCondition(indicators: string[]): Condition {
  const ind = indicators[0] ?? "CLOSE";
  return { left: { indicator: ind }, op: "GT", right: { indicator: ind } };
}

function emptyPremarket(): PremarketSpec {
  return { enabled: false, startTime: "08:45", endTime: "09:00", thresholdPct: 0.1 };
}

const CAPITAL_MODE_LABEL: Record<string, string> = {
  FIXED: "고정 금액 (매번 같은 금액으로 매수)",
  COMPOUND: "복리 재투자 (수익을 다시 투입)",
};

const AMOUNT_PRESETS = [1_000_000, 5_000_000, 10_000_000, 50_000_000];

/** 12,340,000 -> "1,234만원" — the raw number field is hard to read at this many digits. */
function krwWords(v: number): string {
  if (!Number.isFinite(v) || v <= 0) return "";
  const eok = Math.floor(v / 100_000_000);
  const man = Math.floor((v % 100_000_000) / 10_000);
  const rest = Math.round(v % 10_000);
  const parts: string[] = [];
  if (eok) parts.push(`${eok.toLocaleString()}억`);
  if (man) parts.push(`${man.toLocaleString()}만`);
  if (rest) parts.push(rest.toLocaleString());
  return `${parts.join(" ")}원`;
}

function emptyCapital(): CapitalSpec {
  return { amount: 10_000_000, mode: "FIXED" };
}

function emptySpec(): StrategySpec {
  return {
    position: "LONG",
    targetType: "SINGLE",
    entry: { logic: "AND", conditions: [] },
    exit: {
      takeProfitPct: 1.5,
      stopLossPct: 1.0,
      maxHoldBars: null,
      closeAtDayEnd: true,
      logic: "OR",
      conditions: [],
      bands: [],
    },
    premarket: emptyPremarket(),
    capital: emptyCapital(),
  };
}

function ConditionRows({
  conditions,
  indicators,
  operators,
  onChange,
}: {
  conditions: Condition[];
  indicators: string[];
  operators: string[];
  onChange: (c: Condition[]) => void;
}) {
  const update = (i: number, next: Condition) => {
    const copy = conditions.slice();
    copy[i] = next;
    onChange(copy);
  };
  const rightIsConst = (c: Condition) => c.right.const !== undefined && c.right.const !== null && !c.right.indicator;

  return (
    <div>
      {conditions.length === 0 && <p className="hint">아직 조건이 없습니다. 아래 버튼으로 추가하세요.</p>}
      {conditions.map((c, i) => (
        <div className="cond-row" key={i}>
          <select
            value={c.left.indicator ?? indicators[0]}
            onChange={(e) => update(i, { ...c, left: { indicator: e.target.value } })}
          >
            {indicators.map((ind) => (
              <option key={ind} value={ind}>{ind}</option>
            ))}
          </select>

          <select value={c.op} onChange={(e) => update(i, { ...c, op: e.target.value })}>
            {operators.map((op) => (
              <option key={op} value={op}>{OP_LABEL[op] ?? op}</option>
            ))}
          </select>

          <select
            value={rightIsConst(c) ? "const" : "indicator"}
            onChange={(e) =>
              e.target.value === "const"
                ? update(i, { ...c, right: { const: 0 } })
                : update(i, { ...c, right: { indicator: indicators[0] } })
            }
          >
            <option value="indicator">지표</option>
            <option value="const">숫자</option>
          </select>

          {rightIsConst(c) ? (
            <input
              type="number"
              value={c.right.const ?? 0}
              onChange={(e) => update(i, { ...c, right: { const: Number(e.target.value) } })}
            />
          ) : (
            <select
              value={c.right.indicator ?? indicators[0]}
              onChange={(e) => update(i, { ...c, right: { indicator: e.target.value } })}
            >
              {indicators.map((ind) => (
                <option key={ind} value={ind}>{ind}</option>
              ))}
            </select>
          )}

          <button type="button" className="ghost" onClick={() => onChange(conditions.filter((_, j) => j !== i))}>
            삭제
          </button>
        </div>
      ))}
      <button type="button" className="secondary" onClick={() => onChange([...conditions, newCondition(indicators)])}>
        + 조건 추가
      </button>
    </div>
  );
}

export default function RuleBuilder({
  meta,
  editing,
  onSaved,
  onCancel,
}: {
  meta: Meta;
  editing: Strategy | null;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [source, setSource] = useState("USER");
  const [targetType, setTargetType] = useState<"SINGLE" | "ETF">("SINGLE");
  const [premarket, setPremarket] = useState<PremarketSpec>(emptyPremarket());
  const [entry, setEntry] = useState<ConditionGroup>({ logic: "AND", conditions: [] });
  const [exit, setExit] = useState<ExitSpec>(emptySpec().exit);
  const [capital, setCapital] = useState<CapitalSpec>(emptyCapital());
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (editing) {
      setName(editing.name);
      setSource(editing.source);
      setTargetType(editing.spec.targetType ?? "SINGLE");
      setPremarket(editing.spec.premarket ?? emptyPremarket());
      setEntry(editing.spec.entry ?? { logic: "AND", conditions: [] });
      setExit(editing.spec.exit ?? emptySpec().exit);
      setCapital(editing.spec.capital ?? emptyCapital());
    } else {
      const s = emptySpec();
      setName("");
      setSource("USER");
      setTargetType("SINGLE");
      setPremarket(emptyPremarket());
      setEntry(s.entry);
      setExit(s.exit);
      setCapital(emptyCapital());
    }
    setError(null);
  }, [editing]);

  const premarketOn = targetType === "ETF" && premarket.enabled;

  const save = async () => {
    setError(null);
    if (!name.trim()) {
      setError("전략 이름을 입력해주세요.");
      return;
    }
    if (!premarketOn && entry.conditions.length === 0) {
      setError("매수 조건을 최소 1개 이상 추가해주세요.");
      return;
    }
    if (!(capital.amount > 0)) {
      setError("1회 투자금액을 0보다 크게 입력해주세요.");
      return;
    }
    const bandError = validateBands(exit.bands ?? []);
    if (bandError) {
      setError(bandError);
      return;
    }
    const spec: StrategySpec = { name, source, position: "LONG", targetType, entry, exit, premarket, capital };
    setSaving(true);
    try {
      if (editing) {
        await api.updateStrategy(editing.id, name, source, spec);
      } else {
        await api.createStrategy(name, source, spec);
      }
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const numOrNull = (v: string): number | null => (v === "" ? null : Number(v));

  return (
    <div className="card">
      <div className="section-title">
        <h2>{editing ? `전략 수정 #${editing.id}` : "새 전략 만들기"}</h2>
        {editing && (
          <button className="ghost" onClick={onCancel}>새 전략으로</button>
        )}
      </div>

      <div className="grid2">
        <div>
          <label>전략 이름</label>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="예: 골든크로스 단타" style={{ width: "100%" }} />
        </div>
        <div>
          <label>구분</label>
          <select value={source} onChange={(e) => setSource(e.target.value)} style={{ width: "100%" }}>
            <option value="USER">USER (내가 작성)</option>
            <option value="LLM">LLM (추천)</option>
          </select>
        </div>
      </div>

      <hr style={{ border: "none", borderTop: "1px solid var(--border)", margin: "16px 0" }} />

      <div className="grid2">
        <div>
          <label>대상 타입</label>
          <select
            value={targetType}
            onChange={(e) => setTargetType(e.target.value as "SINGLE" | "ETF")}
            style={{ width: "100%" }}
          >
            <option value="SINGLE">일반 종목 (단일 데이터)</option>
            <option value="ETF">ETF (레버리지/인버스 그룹)</option>
          </select>
        </div>
      </div>

      <div className="card" style={{ background: "var(--panel-2)", marginTop: 12 }}>
        <b>투자금액</b>
        <div className="grid2" style={{ marginTop: 10 }}>
          <div>
            <label>{capital.mode === "FIXED" ? "1회 매수 금액 (원)" : "시작 자금 (원)"}</label>
            <input
              type="number"
              step="100000"
              min={0}
              value={capital.amount}
              onChange={(e) => setCapital({ ...capital, amount: Number(e.target.value) })}
              style={{ width: "100%" }}
            />
            <p className="hint" style={{ marginBottom: 0 }}>{krwWords(capital.amount) || "금액을 입력하세요"}</p>
            <div className="row" style={{ marginTop: 6 }}>
              {AMOUNT_PRESETS.map((v) => (
                <button key={v} type="button" className="ghost" onClick={() => setCapital({ ...capital, amount: v })}>
                  {krwWords(v)}
                </button>
              ))}
            </div>
          </div>
          <div>
            <label>운용 방식</label>
            <select
              value={capital.mode}
              onChange={(e) => setCapital({ ...capital, mode: e.target.value as CapitalSpec["mode"] })}
              style={{ width: "100%" }}
            >
              {(meta.capitalModes ?? ["FIXED", "COMPOUND"]).map((m) => (
                <option key={m} value={m}>{CAPITAL_MODE_LABEL[m] ?? m}</option>
              ))}
            </select>
            <p className="hint" style={{ marginBottom: 0 }}>
              {capital.mode === "FIXED"
                ? "매수할 때마다 같은 금액을 넣습니다. 총 수익금은 각 거래 손익의 합계입니다."
                : "수익을 그대로 재투자해 굴립니다. 거래마다 매수 수량이 달라지고, 손실이 쌓이면 매수 규모도 줄어듭니다."}
            </p>
          </div>
        </div>
        <p className="hint">
          주식은 1주 단위로만 사므로 금액을 진입가로 나눈 <b>정수 주수</b>만 매수하고, 남는 현금은 투자되지 않습니다. 이 설정은 매매 시점을 바꾸지 않고 손익을 금액으로 환산하기만 합니다.
        </p>
        <p className="hint" style={{ marginTop: 0 }}>
          수수료는 전략이 아니라 <b>종목별</b>로 붙습니다 — <Link href="/fees">수수료 설정</Link>에서 종목마다 지정하세요.
        </p>
      </div>

      {targetType === "ETF" && (
        <div className="card" style={{ background: "var(--panel-2)", marginTop: 12 }}>
          <div className="row" style={{ marginBottom: premarket.enabled ? 12 : 0 }}>
            <input
              type="checkbox"
              checked={premarket.enabled}
              onChange={(e) => setPremarket({ ...premarket, enabled: e.target.checked })}
            />
            <b>장전 선물추세로 방향 선택 (레버리지/인버스)</b>
          </div>
          {premarket.enabled && (
            <>
              <p className="hint" style={{ marginTop: 0 }}>
                매일 선물의 장전 종가 변화율을 보고 상승이면 레버리지, 하락이면 인버스를 09:00에 매수합니다.
                (매수 조건 대신 이 게이트로 진입)
              </p>
              <div className="grid2">
                <div>
                  <label>장전 시작</label>
                  <input value={premarket.startTime} onChange={(e) => setPremarket({ ...premarket, startTime: e.target.value })} style={{ width: "100%" }} />
                </div>
                <div>
                  <label>장전 종료 (개장)</label>
                  <input value={premarket.endTime} onChange={(e) => setPremarket({ ...premarket, endTime: e.target.value })} style={{ width: "100%" }} />
                </div>
                <div>
                  <label>추세 임계 % (이 이상 상승→레버리지 / 이하 하락→인버스)</label>
                  <input type="number" step="0.05" value={premarket.thresholdPct} onChange={(e) => setPremarket({ ...premarket, thresholdPct: Number(e.target.value) })} style={{ width: "100%" }} />
                </div>
              </div>
            </>
          )}
        </div>
      )}

      <hr style={{ border: "none", borderTop: "1px solid var(--border)", margin: "16px 0" }} />

      {premarketOn ? (
        <p className="muted">
          🕘 장전 선물추세 모드에서는 <b>매수 조건 대신 08:45~09:00 선물 추세로 진입</b>합니다. 아래 매도(청산) 규칙은 그대로 적용됩니다.
        </p>
      ) : (
        <>
          <div className="section-title">
            <h2>매수 조건</h2>
            <select value={entry.logic} onChange={(e) => setEntry({ ...entry, logic: e.target.value as "AND" | "OR" })}>
              <option value="AND">모두 만족 (AND)</option>
              <option value="OR">하나라도 만족 (OR)</option>
            </select>
          </div>
          <ConditionRows
            conditions={entry.conditions}
            indicators={meta.indicators}
            operators={meta.operators}
            onChange={(c) => setEntry({ ...entry, conditions: c })}
          />
        </>
      )}

      <hr style={{ border: "none", borderTop: "1px solid var(--border)", margin: "16px 0" }} />

      <div className="section-title">
        <h2>매도 조건 (청산)</h2>
        <Link href="/guide" className="pill" target="_blank">📖 매도 조건이 어렵다면 · 가이드 열기</Link>
      </div>
      <h3 className="sub-h">익절 / 손절</h3>
      <ExitLevels exit={exit} onChange={setExit} />

      <h3 className="sub-h" style={{ marginTop: 18 }}>그 외 청산</h3>
      <div className="grid2">
        <div>
          <label>최대 보유 봉수 (비우면 없음)</label>
          <input type="number" value={exit.maxHoldBars ?? ""} onChange={(e) => setExit({ ...exit, maxHoldBars: numOrNull(e.target.value) })} style={{ width: "100%" }} />
          <p className="hint" style={{ marginBottom: 0 }}>
            1봉은 백테스트에 고르는 데이터셋의 봉 길이입니다 — 10을 넣으면 3분봉에서는 30분, 1분봉에서는 10분 뒤 청산.
          </p>
        </div>
        <div>
          <label>당일 청산</label>
          <div className="row" style={{ height: 36 }}>
            <input type="checkbox" checked={exit.closeAtDayEnd} onChange={(e) => setExit({ ...exit, closeAtDayEnd: e.target.checked })} />
            <span className="muted">장 마감 시 강제 청산 (단타 권장)</span>
          </div>
        </div>
      </div>

      <div className="section-title" style={{ marginTop: 18 }}>
        <h3 className="sub-h" style={{ marginBottom: 0 }}>
          매도 시그널 조건 (선택){" "}
          <Link href="/guide" className="muted" style={{ fontSize: 12, fontWeight: 400 }} target="_blank">
            설명 보기
          </Link>
        </h3>
        <select value={exit.logic} onChange={(e) => setExit({ ...exit, logic: e.target.value as "AND" | "OR" })}>
          <option value="OR">하나라도 만족 (OR)</option>
          <option value="AND">모두 만족 (AND)</option>
        </select>
      </div>
      <ConditionRows
        conditions={exit.conditions}
        indicators={meta.indicators}
        operators={meta.operators}
        onChange={(c) => setExit({ ...exit, conditions: c })}
      />

      {error && <p className="error">{error}</p>}
      <div className="row" style={{ marginTop: 16 }}>
        <button onClick={save} disabled={saving}>{saving ? "저장 중…" : editing ? "수정 저장" : "전략 저장"}</button>
        {editing && <button className="secondary" onClick={onCancel}>취소</button>}
      </div>
    </div>
  );
}
