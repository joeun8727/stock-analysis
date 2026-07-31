import type {
  BacktestListItem,
  BacktestResponse,
  Dataset,
  EtfGroup,
  FeeSettingDto,
  Meta,
  Strategy,
  StrategySpec,
} from "./types";

const BASE = process.env.NEXT_PUBLIC_API_BASE || "http://localhost:8080";

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let msg = `요청 실패 (${res.status})`;
    try {
      const body = await res.json();
      if (body?.error) msg = body.error;
    } catch {
      /* ignore */
    }
    throw new Error(msg);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export const api = {
  async meta(): Promise<Meta> {
    return handle(await fetch(`${BASE}/api/meta`, { cache: "no-store" }));
  },

  async listStrategies(): Promise<Strategy[]> {
    return handle(await fetch(`${BASE}/api/strategies`, { cache: "no-store" }));
  },
  async createStrategy(name: string, source: string, spec: StrategySpec): Promise<Strategy> {
    return handle(
      await fetch(`${BASE}/api/strategies`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, source, spec }),
      }),
    );
  },
  async updateStrategy(id: number, name: string, source: string, spec: StrategySpec): Promise<Strategy> {
    return handle(
      await fetch(`${BASE}/api/strategies/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, source, spec }),
      }),
    );
  },
  async deleteStrategy(id: number): Promise<void> {
    return handle(await fetch(`${BASE}/api/strategies/${id}`, { method: "DELETE" }));
  },

  async listDatasets(): Promise<Dataset[]> {
    return handle(await fetch(`${BASE}/api/datasets`, { cache: "no-store" }));
  },
  async uploadDataset(form: FormData): Promise<Dataset> {
    return handle(await fetch(`${BASE}/api/datasets`, { method: "POST", body: form }));
  },
  async deleteDataset(id: number): Promise<void> {
    return handle(await fetch(`${BASE}/api/datasets/${id}`, { method: "DELETE" }));
  },
  /** Moves a dataset into an ETF group; pass null to unlink it. */
  async setDatasetGroup(id: number, etfGroupId: number | null): Promise<Dataset> {
    return handle(
      await fetch(`${BASE}/api/datasets/${id}/group`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ etfGroupId }),
      }),
    );
  },

  async runBacktest(
    strategyId: number,
    opts: { datasetId?: number; groupId?: number; fromDate?: string | null; toDate?: string | null },
  ): Promise<BacktestResponse> {
    return handle(
      await fetch(`${BASE}/api/backtests`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ strategyId, ...opts }),
      }),
    );
  },
  async listEtfGroups(): Promise<EtfGroup[]> {
    return handle(await fetch(`${BASE}/api/etf-groups`, { cache: "no-store" }));
  },
  async createEtfGroup(name: string): Promise<EtfGroup> {
    return handle(
      await fetch(`${BASE}/api/etf-groups`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name }),
      }),
    );
  },
  async renameEtfGroup(id: number, name: string): Promise<EtfGroup> {
    return handle(
      await fetch(`${BASE}/api/etf-groups/${id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name }),
      }),
    );
  },
  async deleteEtfGroup(id: number): Promise<void> {
    return handle(await fetch(`${BASE}/api/etf-groups/${id}`, { method: "DELETE" }));
  },
  async getFee(): Promise<FeeSettingDto> {
    return handle(await fetch(`${BASE}/api/settings/fee`, { cache: "no-store" }));
  },
  async updateFee(feeRatePct: number): Promise<FeeSettingDto> {
    return handle(
      await fetch(`${BASE}/api/settings/fee`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ feeRatePct }),
      }),
    );
  },
  /** Per-symbol commission, in percent per side. */
  async setDatasetFee(id: number, feeRatePct: number): Promise<Dataset> {
    return handle(
      await fetch(`${BASE}/api/datasets/${id}/fee`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ feeRatePct }),
      }),
    );
  },
  async listBacktests(): Promise<BacktestListItem[]> {
    return handle(await fetch(`${BASE}/api/backtests`, { cache: "no-store" }));
  },
  async getBacktest(id: number): Promise<BacktestResponse> {
    return handle(await fetch(`${BASE}/api/backtests/${id}`, { cache: "no-store" }));
  },

  async recommend(datasetId: number): Promise<Strategy[]> {
    return handle(
      await fetch(`${BASE}/api/recommendations`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ datasetId }),
      }),
    );
  },
};
