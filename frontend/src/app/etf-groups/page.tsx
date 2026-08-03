"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Dataset, EtfGroup, EtfSlot } from "@/lib/types";

type SlotKey = "leverage" | "inverse" | "futures";

const SLOTS: { key: SlotKey; label: string; hint: string }[] = [
  { key: "leverage", label: "레버리지", hint: "선물이 상승 추세일 때 매수할 ETF" },
  { key: "inverse", label: "인버스", hint: "선물이 하락 추세일 때 매수할 ETF" },
  { key: "futures", label: "선물", hint: "장전 추세를 판단할 기준 선물" },
];

/** Which datasets may fill a given slot — the engine matches on market + kind. */
function eligible(datasets: Dataset[], slot: SlotKey): Dataset[] {
  if (slot === "futures") return datasets.filter((d) => d.market === "FUTURES");
  const kind = slot === "leverage" ? "LEVERAGE" : "INVERSE";
  return datasets.filter((d) => d.market === "ETF" && d.kind === kind);
}

/**
 * Bar lengths present in a group's filled slots. Mixing them is a real error: the pre-market
 * decision and any futures-referencing condition match the futures bar by exact timestamp, so a
 * 1-minute ETF against 3-minute futures silently finds nothing on two of every three bars.
 */
function slotIntervals(group: EtfGroup, datasets: Dataset[]): number[] {
  return SLOTS
    .map((s) => group[s.key] as EtfSlot)
    .filter((slot): slot is NonNullable<EtfSlot> => slot !== null)
    .map((slot) => datasets.find((d) => d.id === slot.datasetId)?.barIntervalMinutes)
    .filter((v): v is number => v !== undefined);
}

export default function EtfGroupsPage() {
  const [groups, setGroups] = useState<EtfGroup[]>([]);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [newName, setNewName] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editingName, setEditingName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      const [g, d] = await Promise.all([api.listEtfGroups(), api.listDatasets()]);
      setGroups(g);
      setDatasets(d);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const run = async (fn: () => Promise<unknown>, ok?: string) => {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await fn();
      if (ok) setSuccess(ok);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      await reload();
    } finally {
      setBusy(false);
    }
  };

  const create = () => {
    if (!newName.trim()) { setError("그룹 이름을 입력해주세요."); return; }
    run(async () => {
      const g = await api.createEtfGroup(newName.trim());
      setNewName("");
      setSuccess(`'${g.name}' 그룹을 만들었습니다. 아래에서 레버리지·인버스·선물을 지정하세요.`);
    });
  };

  const saveRename = (id: number) =>
    run(async () => {
      await api.renameEtfGroup(id, editingName.trim());
      setEditingId(null);
      setEditingName("");
    });

  const remove = (g: EtfGroup) => {
    const filled = SLOTS.filter((s) => g[s.key]).length;
    const note = filled > 0
      ? `\n\n지정된 데이터 ${filled}건은 삭제되지 않고 '그룹 없음' 상태가 됩니다.`
      : "";
    if (!confirm(`그룹 '${g.name}'을 삭제할까요?${note}`)) return;
    run(() => api.deleteEtfGroup(g.id), `'${g.name}' 그룹을 삭제했습니다.`);
  };

  /**
   * Sets a slot to `datasetId` (or clears it when null). A slot holds one dataset, so replacing
   * means unlinking the current occupant first — otherwise the server rejects the duplicate slot.
   */
  const setSlot = (g: EtfGroup, slot: SlotKey, datasetId: number | null) => {
    const currentId = (g[slot] as EtfSlot)?.datasetId ?? null;
    if (currentId === datasetId) return;
    run(async () => {
      if (currentId !== null) await api.setDatasetGroup(currentId, null);
      if (datasetId !== null) await api.setDatasetGroup(datasetId, g.id);
    });
  };

  const ungrouped = datasets.filter((d) => d.etfGroupId === null && d.market !== "NORMAL");

  return (
    <div>
      <h1>ETF 그룹 관리</h1>
      <p className="subtitle">
        장전 선물추세 백테스트는 <b>레버리지·인버스·선물 3개가 한 그룹</b>으로 묶여야 실행됩니다. 여기서 그룹을 만들고, <Link href="/datasets">업로드한 데이터</Link>를 각 자리에 지정하세요.
      </p>

      <div className="card">
        <h2>새 그룹 만들기</h2>
        <div className="row">
          <input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); create(); } }}
            placeholder="그룹 이름 (예: KODEX200, KODEX코스닥150)"
            style={{ minWidth: 300 }}
          />
          <button onClick={create} disabled={busy}>+ 만들기</button>
        </div>
        <p className="hint">
          어떤 ETF든 이름만 정하면 그룹이 됩니다. 새 ETF 짝을 추가할 때 여기서 그룹을 먼저 만들고, 업로드 화면에서 그 그룹을 골라 파일을 올리면 됩니다.
        </p>
      </div>

      {error && <p className="error">{error}</p>}
      {success && <p className="success">{success}</p>}

      {groups.length === 0 ? (
        <div className="card">
          <p className="muted" style={{ margin: 0 }}>아직 그룹이 없습니다. 위에서 첫 그룹을 만들어보세요.</p>
        </div>
      ) : (
        groups.map((g) => (
          <div className="card" key={g.id}>
            <div className="section-title">
              <h2 style={{ display: "flex", alignItems: "center", gap: 10 }}>
                {editingId === g.id ? (
                  <input
                    autoFocus
                    value={editingName}
                    onChange={(e) => setEditingName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") { e.preventDefault(); saveRename(g.id); }
                      if (e.key === "Escape") setEditingId(null);
                    }}
                    style={{ width: 220, fontSize: 15 }}
                  />
                ) : (
                  g.name
                )}
                {g.ready
                  ? <span className="badge llm">완성 · 백테스트 가능</span>
                  : <span className="badge market">미완성</span>}
              </h2>
              <div className="row" style={{ gap: 6 }}>
                {editingId === g.id ? (
                  <>
                    <button className="secondary" onClick={() => saveRename(g.id)} disabled={busy}>저장</button>
                    <button className="secondary" onClick={() => setEditingId(null)}>취소</button>
                  </>
                ) : (
                  <>
                    <button className="secondary" onClick={() => { setEditingId(g.id); setEditingName(g.name); }}>이름변경</button>
                    <button className="danger" onClick={() => remove(g)} disabled={busy}>그룹 삭제</button>
                  </>
                )}
              </div>
            </div>

            <table>
              <thead>
                <tr><th style={{ width: 110 }}>자리</th><th>지정된 데이터</th><th>설명</th></tr>
              </thead>
              <tbody>
                {SLOTS.map((s) => {
                  const current = g[s.key] as EtfSlot;
                  const options = eligible(datasets, s.key);
                  return (
                    <tr key={s.key}>
                      <td><b>{s.label}</b></td>
                      <td>
                        <select
                          value={current?.datasetId ?? ""}
                          disabled={busy}
                          onChange={(e) => setSlot(g, s.key, e.target.value ? Number(e.target.value) : null)}
                          style={{ minWidth: 280 }}
                        >
                          <option value="">— 지정 없음</option>
                          {options.map((d) => (
                            <option key={d.id} value={d.id}>
                              {d.symbol} ({d.barIntervalMinutes}분봉)
                              {d.etfGroupId !== null && d.etfGroupId !== g.id ? ` (현재: ${d.groupName})` : ""}
                            </option>
                          ))}
                        </select>
                        {options.length === 0 && (
                          <span className="hint" style={{ marginLeft: 8 }}>
                            해당 종류의 데이터가 없습니다 — 먼저 업로드하세요.
                          </span>
                        )}
                      </td>
                      <td className="muted">{s.hint}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {new Set(slotIntervals(g, datasets)).size > 1 && (
              <p className="error">
                봉 길이가 다른 데이터가 섞여 있습니다
                ({[...new Set(slotIntervals(g, datasets))].sort((a, b) => a - b).map((m) => `${m}분봉`).join(" / ")}) —
                장전 추세 판정과 선물 지표는 시각이 정확히 같은 봉끼리만 맞춰 보므로, 한 그룹은 봉 길이를 통일해주세요.
              </p>
            )}
            {!g.ready && (
              <p className="hint">
                비어 있는 자리: {SLOTS.filter((s) => !g[s.key]).map((s) => s.label).join(", ")} — 3개가 모두 채워지면 백테스트 화면에서 이 그룹을 선택할 수 있습니다.
              </p>
            )}
          </div>
        ))
      )}

      <div className="card">
        <h2>그룹에 속하지 않은 데이터 ({ungrouped.length})</h2>
        {ungrouped.length === 0 ? (
          <p className="muted" style={{ margin: 0 }}>모든 ETF·선물 데이터가 그룹에 지정되어 있습니다.</p>
        ) : (
          <table>
            <thead>
              <tr><th>종목</th><th>시장</th><th>종류</th><th>봉 길이</th><th>봉 수</th></tr>
            </thead>
            <tbody>
              {ungrouped.map((d) => (
                <tr key={d.id}>
                  <td>{d.symbol}</td>
                  <td><span className="badge market">{d.market === "FUTURES" ? "선물" : "ETF"}</span></td>
                  <td className="muted">{d.kind}</td>
                  <td><span className="badge market">{d.barIntervalMinutes}분봉</span></td>
                  <td>{d.barCount.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="hint">위 그룹의 자리 선택 목록에서 고르면 그룹에 편입됩니다.</p>
      </div>
    </div>
  );
}
