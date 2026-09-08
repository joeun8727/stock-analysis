import type {
  BacktestListItem,
  BacktestResponse,
  Dataset,
  EtfGroup,
  FeeSettingDto,
  LiveCandidate,
  LiveCheckResult,
  LiveConfigDto,
  LiveConfigUpdate,
  LiveSessionItem,
  LiveToday,
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
      /* 무시 */
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
  /** 데이터셋을 ETF 그룹으로 옮깁니다. null을 주면 연결을 끊습니다. */
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
  /** 종목별 편도 수수료율(%). */
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

  /** 실주문에 쓰는 종목코드. null을 주면 비웁니다. */
  async setDatasetTicker(id: number, ticker: string | null): Promise<Dataset> {
    return handle(
      await fetch(`${BASE}/api/datasets/${id}/ticker`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ticker }),
      }),
    );
  },

  // ── 실투자 ────────────────────────────────────────────────────────
  // 매매 규칙을 바꾸는 함수가 없다는 점에 주목: 규칙은 전략 화면에서만 바뀝니다.

  async liveConfig(): Promise<LiveConfigDto> {
    return handle(await fetch(`${BASE}/api/live/config`, { cache: "no-store" }));
  },
  async updateLiveConfig(update: LiveConfigUpdate): Promise<LiveConfigDto> {
    return handle(
      await fetch(`${BASE}/api/live/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(update),
      }),
    );
  },
  /** 실투자 대상이 될 수 있는 전략들. 각각 검증 근거와 함께. */
  async liveCandidates(): Promise<LiveCandidate[]> {
    return handle(await fetch(`${BASE}/api/live/candidates`, { cache: "no-store" }));
  },
  async liveToday(): Promise<LiveToday> {
    return handle(await fetch(`${BASE}/api/live/today`, { cache: "no-store" }));
  },
  async liveSessions(): Promise<LiveSessionItem[]> {
    return handle(await fetch(`${BASE}/api/live/sessions`, { cache: "no-store" }));
  },
  /** 하루 동안 매매를 켭니다. 전략이 검증 게이트를 통과하지 못하면 거부됩니다. */
  async liveArm(date?: string): Promise<LiveConfigDto> {
    return handle(
      await fetch(`${BASE}/api/live/arm`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ date: date ?? null }),
      }),
    );
  },
  async liveDisarm(): Promise<LiveConfigDto> {
    return handle(await fetch(`${BASE}/api/live/disarm`, { method: "POST" }));
  },
  async liveHalt(reason: string, closePosition: boolean): Promise<LiveToday> {
    return handle(
      await fetch(`${BASE}/api/live/halt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason, closePosition }),
      }),
    );
  },
  /** 연결과 설정 점검. 주문은 절대 내지 않습니다. */
  async liveCheck(): Promise<LiveCheckResult> {
    return handle(await fetch(`${BASE}/api/live/check`, { method: "POST" }));
  },
};
