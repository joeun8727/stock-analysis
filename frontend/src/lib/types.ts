export type Operand = {
  indicator?: string;
  source?: string;
  const?: number | null;
};

export type Condition = {
  left: Operand;
  op: string;
  right: Operand;
};

export type ConditionGroup = {
  logic: "AND" | "OR";
  conditions: Condition[];
};

/**
 * Time-of-day override of take-profit / stop-loss, matched as [startTime, endTime) against the
 * bar being checked. A null percentage falls back to the ExitSpec base value.
 */
export type TimeBand = {
  startTime: string;
  endTime: string;
  takeProfitPct?: number | null;
  stopLossPct?: number | null;
};

export type ExitSpec = {
  takeProfitPct?: number | null;
  stopLossPct?: number | null;
  maxHoldBars?: number | null;
  closeAtDayEnd: boolean;
  logic: "AND" | "OR";
  conditions: Condition[];
  bands?: TimeBand[];
};

export type PremarketSpec = {
  enabled: boolean;
  startTime: string;
  endTime: string;
  thresholdPct: number;
};

export type CapitalMode = "FIXED" | "COMPOUND";

/** Default applied to newly uploaded symbols; per-symbol rates live on the dataset. */
export type FeeSettingDto = {
  feeRatePct: number;
  updatedAt: string;
};

export type CapitalSpec = {
  /** Per-trade budget in FIXED mode, starting balance in COMPOUND mode (KRW). */
  amount: number;
  mode: CapitalMode;
};

export type StrategySpec = {
  name?: string;
  source?: string;
  position?: string;
  targetType?: "SINGLE" | "ETF";
  entry: ConditionGroup;
  exit: ExitSpec;
  premarket?: PremarketSpec;
  capital?: CapitalSpec;
};

export type EtfGroup = {
  id: number;
  name: string;
  leverage: EtfSlot;
  inverse: EtfSlot;
  futures: EtfSlot;
  ready: boolean;
};

export type Strategy = {
  id: number;
  name: string;
  source: string;
  spec: StrategySpec;
  createdAt: string;
  updatedAt: string;
};

export type Dataset = {
  id: number;
  symbol: string;
  market: "FUTURES" | "ETF" | "NORMAL";
  kind: "LEVERAGE" | "INVERSE" | "SINGLE";
  etfGroupId: number | null;
  groupName: string | null;
  /** One-way commission for this symbol, in percent. */
  feeRatePct: number;
  originalFilename: string;
  barCount: number;
  /** Bar length of this file in minutes (3 for 3-minute data, 1 for 1-minute), inferred at upload. */
  barIntervalMinutes: number;
  fromTs: string | null;
  toTs: string | null;
  uploadedAt: string;
};

export type EtfSlot = { datasetId: number; symbol: string } | null;

export type Meta = {
  indicators: string[];
  operators: string[];
  logics: string[];
  sources: string[];
  markets: string[];
  kinds: string[];
  capitalModes: string[];
};

export type Summary = {
  totalTrades: number;
  wins: number;
  losses: number;
  winRate: number;
  lossRate: number;
  totalReturnPct: number;
  compoundedReturnPct: number;
  avgWinPct: number;
  avgLossPct: number;
  maxDrawdownPct: number;
  maxConsecutiveLosses: number;
};

export type MoneySummary = {
  mode: CapitalMode;
  investAmount: number;
  /** Rates actually applied, keyed by instrument (SINGLE, or LEVERAGE/INVERSE). */
  feeRatesPct: Record<string, number>;
  totalProfitAmount: number;
  totalFeeAmount: number;
  avgProfitPerTrade: number;
  finalBalance: number;
  returnOnCapitalPct: number;
  bestTradeAmount: number;
  worstTradeAmount: number;
  unaffordableTrades: number;
};

export type Severity = "HIGH" | "MEDIUM" | "INFO";

export type Finding = {
  severity: Severity;
  title: string;
  detail: string;
  suggestion: string;
};

/** Rule-based read of what went wrong, worst finding first. */
export type Diagnosis = {
  headline: string;
  findings: Finding[];
};

/** What a run was asked for, and what the range resolved to in bars. */
export type RunParams = {
  fromDate: string | null;
  toDate: string | null;
  barFromTs: string;
  barToTs: string;
  barCount: number;
};

export type StoredTrade = {
  entryTs: string;
  exitTs: string;
  entryPrice: number;
  exitPrice: number;
  returnPct: number;
  exitReason: string;
  success: boolean;
  instrument?: string | null;
  quantity?: number;
  profitAmount?: number;
  feeAmount?: number;
};

export type EquityPoint = { ts: string; equity: number };
export type ReasonStat = { count: number; avgReturnPct: number; totalReturnPct: number };
export type HourStat = { lossCount: number; avgLossPct: number };

export type BacktestResponse = {
  runId: number;
  strategyId: number;
  strategyName: string;
  datasetId: number;
  datasetSymbol: string;
  createdAt: string;
  params: RunParams | null;
  summary: Summary;
  money: MoneySummary | null;
  diagnosis: Diagnosis | null;
  equityCurve: EquityPoint[];
  failureByReason: Record<string, ReasonStat>;
  failureByHour: Record<string, HourStat>;
  worstTrades: StoredTrade[];
  trades: StoredTrade[];
};

export type BacktestListItem = {
  runId: number;
  strategyId: number;
  strategyName: string;
  datasetId: number;
  datasetSymbol: string;
  createdAt: string;
  params: RunParams | null;
  summary: Summary | null;
  money: MoneySummary | null;
};
