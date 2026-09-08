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
  /** Exchange code for live orders (e.g. "122630"). Unused by backtests; required to trade. */
  ticker: string | null;
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

// ── 실투자 (한국투자증권) ──────────────────────────────────────────────
// 매매 규칙 타입이 여기 없는 것에 주목하세요. 익절·손절·시간대 밴드·장전 임계치는
// StrategySpec 하나에만 있고, 실투자는 그것을 읽기만 합니다.

/** DRY_RUN: 주문 미전송, PAPER: 모의투자 서버, REAL: 실계좌. 환경변수로만 바뀝니다. */
export type LiveMode = "DRY_RUN" | "PAPER" | "REAL";

export type LiveState =
  | "IDLE"
  | "ARMED"
  | "WATCHING"
  | "SKIPPED"
  | "ENTRY_PENDING"
  | "HOLDING"
  | "EXIT_PENDING"
  | "CLOSED"
  | "HALTED";

/** 이 전략을 실투자에 써도 되는지, 그리고 그 근거가 된 백테스트. */
export type VerificationStatus = {
  verified: boolean;
  reason: string;
  runId: number | null;
  runCreatedAt: string | null;
  totalTrades: number | null;
  winRate: number | null;
  profitLossRatio: number | null;
  maxDrawdown: number | null;
  totalProfitAmount: number | null;
  fromTs: string | null;
  toTs: string | null;
  spanDays: number | null;
};

export type LiveCandidate = {
  strategyId: number;
  name: string;
  source: string;
  status: VerificationStatus;
};

export type LiveConfigDto = {
  strategyId: number | null;
  etfGroupId: number | null;
  futuresTicker: string | null;
  verifiedRunId: number | null;
  specVerified: boolean;
  maxOrderAmount: number;
  maxDailyLoss: number;
  pollIntervalSec: number;
  dayEndExitTime: string;
  minVerifiedTrades: number;
  minVerifiedDays: number;
  armedDate: string | null;
  updatedAt: string | null;
};

export type LiveConfigUpdate = Partial<{
  strategyId: number;
  etfGroupId: number;
  futuresTicker: string;
  maxOrderAmount: number;
  maxDailyLoss: number;
  pollIntervalSec: number;
  dayEndExitTime: string;
  minVerifiedTrades: number;
  minVerifiedDays: number;
}>;

export type LiveOrderView = {
  side: "BUY" | "SELL";
  ticker: string;
  quantity: number;
  status: string;
  filledQuantity: number;
  filledPrice: number | null;
  requestedAt: string | null;
  filledAt: string | null;
  brokerOrderNo: string | null;
};

export type LiveEventView = { ts: string | null; type: string; message: string };

export type LiveTickPoint = { ts: string; price: number };

export type LiveToday = {
  mode: LiveMode;
  armed: boolean;
  armedDate: string | null;
  state: LiveState;
  tradeDate: string;
  sessionId: number | null;
  strategyName: string | null;
  verifiedRunId: number | null;
  futuresTicker: string | null;
  trendPct: number | null;
  thresholdPct: number | null;
  premarketStart: string | null;
  premarketEnd: string | null;
  premarketSamples: number;
  premarketTicks: LiveTickPoint[];
  chosenInstrument: string | null;
  chosenTicker: string | null;
  entryPrice: number | null;
  quantity: number | null;
  currentPrice: number | null;
  unrealisedProfit: number | null;
  /** 지금 적용 중인 손절·익절 가격 (시간대 밴드 반영). */
  stopPrice: number | null;
  takeProfitPrice: number | null;
  exitPrice: number | null;
  exitReason: string | null;
  profitAmount: number | null;
  feeAmount: number | null;
  haltedReason: string | null;
  /** WEBSOCKET이면 실시간 체결가, REST면 폴링으로 대체 중. */
  priceSource: "WEBSOCKET" | "REST";
  dayEndExitTime: string;
  orders: LiveOrderView[];
  events: LiveEventView[];
  verification: VerificationStatus;
};

export type LiveSessionItem = {
  sessionId: number;
  tradeDate: string;
  mode: LiveMode;
  state: LiveState;
  strategyName: string | null;
  verifiedRunId: number | null;
  trendPct: number | null;
  chosenInstrument: string | null;
  chosenTicker: string | null;
  entryPrice: number | null;
  exitPrice: number | null;
  quantity: number | null;
  exitReason: string | null;
  profitAmount: number | null;
  feeAmount: number | null;
  haltedReason: string | null;
};

export type LiveCheckResult = {
  mode: LiveMode;
  account: string;
  tokenOk: boolean;
  balanceOk: boolean;
  cashAvailable: number | null;
  futuresTicker: string | null;
  futuresPrice: number | null;
  futuresEstimated: boolean;
  problems: string[];
};
