"use client";

import { useState } from "react";

export type CurvePoint = { ts: string; value: number };

/**
 * 균등 간격 점들을 지나는 단조 3차 에르미트(Fritsch–Carlson) 경로.
 * 부드럽지만 국소 최소/최대를 넘어서지 않습니다 — 일반 Catmull-Rom 스플라인은
 * 손절 계단 아래로 없던 저점을 지어내는데, 그건 일어난 적 없는 손실처럼 읽힙니다.
 */
function smoothPath(xs: number[], ys: number[]): string {
  const n = ys.length;
  if (n < 2) return "";
  const slope: number[] = [];
  for (let i = 0; i < n - 1; i++) slope.push((ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]));

  const m: number[] = new Array(n);
  m[0] = slope[0];
  m[n - 1] = slope[n - 2];
  for (let i = 1; i < n - 1; i++) {
    m[i] = slope[i - 1] * slope[i] <= 0 ? 0 : (slope[i - 1] + slope[i]) / 2;
  }
  // 각 구간이 단조를 유지하도록(넘어서지 않도록) 접선을 조입니다.
  for (let i = 0; i < n - 1; i++) {
    if (slope[i] === 0) {
      m[i] = 0;
      m[i + 1] = 0;
      continue;
    }
    const a = m[i] / slope[i];
    const b = m[i + 1] / slope[i];
    const s = a * a + b * b;
    if (s > 9) {
      const t = 3 / Math.sqrt(s);
      m[i] = t * a * slope[i];
      m[i + 1] = t * b * slope[i];
    }
  }

  let d = `M${xs[0].toFixed(1)},${ys[0].toFixed(1)}`;
  for (let i = 0; i < n - 1; i++) {
    const dx = (xs[i + 1] - xs[i]) / 3;
    d +=
      ` C${(xs[i] + dx).toFixed(1)},${(ys[i] + m[i] * dx).toFixed(1)}` +
      ` ${(xs[i + 1] - dx).toFixed(1)},${(ys[i + 1] - m[i + 1] * dx).toFixed(1)}` +
      ` ${xs[i + 1].toFixed(1)},${ys[i + 1].toFixed(1)}`;
  }
  return d;
}

/**
 * 호버 표시가 있는 가벼운 인라인 SVG 누적 곡선. 값의 종류를 가리지 않습니다:
 * 백테스트 화면은 누적 원화 수익금(기준선 0)을 넣고, 금액 기능 이전에 저장된 실행은
 * 엔진의 복리 자본배수(기준선 1)로 폴백합니다.
 * 성능을 위해 다운샘플링합니다.
 */
export default function EquityCurve({
  points,
  baseline,
  format,
  sub,
}: {
  points: CurvePoint[];
  baseline: number;
  format: (v: number) => string;
  sub?: (v: number) => string;
}) {
  const W = 900;
  const H = 220;
  const PAD = 8;
  const [hover, setHover] = useState<number | null>(null);
  if (!points || points.length < 2) {
    return <p className="muted">거래가 부족해 그래프를 표시할 수 없습니다.</p>;
  }

  // 최대 600개 정도로 다운샘플링합니다.
  const step = Math.max(1, Math.floor(points.length / 600));
  const sampled = points.filter((_, i) => i % step === 0);
  if (sampled[sampled.length - 1] !== points[points.length - 1]) {
    sampled.push(points[points.length - 1]);
  }

  const vals = sampled.map((p) => p.value);
  const min = Math.min(...vals, baseline);
  const max = Math.max(...vals, baseline);
  const range = max - min || 1;

  const x = (i: number) => PAD + (i / (sampled.length - 1)) * (W - 2 * PAD);
  const y = (v: number) => H - PAD - ((v - min) / range) * (H - 2 * PAD);

  const path = smoothPath(
    sampled.map((_, i) => x(i)),
    sampled.map((p) => y(p.value)),
  );
  const baselineY = y(baseline);
  const last = vals[vals.length - 1];
  const up = last >= baseline;
  const color = up ? "#38c793" : "#ff5c6c";

  // svg가 preserveAspectRatio="none"이라 viewBox 단위가 늘어납니다. 오버레이는 픽셀 대신
  // 퍼센트로 배치해서 찌그러지지 않게 합니다.
  const pct = (v: number, span: number) => `${(v / span) * 100}%`;

  const onMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width === 0) return;
    // x()를 역으로: 화면 px → viewBox 단위 → 샘플 인덱스.
    const vx = ((e.clientX - rect.left) / rect.width) * W;
    const ratio = (vx - PAD) / (W - 2 * PAD);
    const i = Math.round(ratio * (sampled.length - 1));
    setHover(Math.min(sampled.length - 1, Math.max(0, i)));
  };

  const hi = hover === null ? -1 : Math.min(hover, sampled.length - 1);
  const hp = hi < 0 ? null : sampled[hi];
  const hoverX = hi < 0 ? 0 : x(hi);
  const hoverPctPos = ((hoverX - PAD) / (W - 2 * PAD)) * 100;

  return (
    <div className="eq-wrap" onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" preserveAspectRatio="none" style={{ display: "block" }}>
        <line x1={PAD} y1={baselineY} x2={W - PAD} y2={baselineY} stroke="#2a3a57" strokeWidth={1} strokeDasharray="4 4" />
        <path d={`${path} L${x(sampled.length - 1)},${H - PAD} L${x(0)},${H - PAD} Z`} fill={color} opacity={0.12} />
        <path d={path} fill="none" stroke={color} strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {hp && (
        <>
          <div className="eq-guide" style={{ left: pct(hoverX, W) }} />
          <div className="eq-dot" style={{ left: pct(hoverX, W), top: pct(y(hp.value), H), background: color }} />
          <div className={`eq-tip${hoverPctPos > 60 ? " flip" : ""}`} style={{ left: pct(hoverX, W) }}>
            <div className="eq-tip-ts">{hp.ts.replace("T", " ").slice(0, 16)}</div>
            <div className={`eq-tip-v ${hp.value >= baseline ? "trade-pos" : "trade-neg"}`}>{format(hp.value)}</div>
            {sub && sub(hp.value) && <div className="eq-tip-r">{sub(hp.value)}</div>}
          </div>
        </>
      )}
    </div>
  );
}
