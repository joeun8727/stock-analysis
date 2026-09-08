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
 * 시간대별 익절/손절 덮어쓰기. 지금 확인하는 봉에 대해 [startTime, endTime)으로 맞춥니다.
 * 퍼센트가 null이면 ExitSpec의 기본값으로 폴백합니다.
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

/** 새로 업로드하는 종목에 적용되는 기본값. 종목별 요율은 데이터셋에 있습니다. */
export type FeeSettingDto = {
  feeRatePct: number;
  updatedAt: string;
};

export type CapitalSpec = {
  /** FIXED 모드에서는 거래당 예산, COMPOUND 모드에서는 시작 잔고 (원). */
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
  /** 실주문용 종목코드(예: "122630"). 백테스트는 쓰지 않지만 매매에는 반드시 필요합니다. */
  ticker: string | null;
  market: "FUTURES" | "ETF" | "NORMAL";
  kind: "LEVERAGE" | "INVERSE" | "SINGLE";
  etfGroupId: number | null;
  groupName: string | null;
  /** 이 종목의 편도 수수료율(%). */
  feeRatePct: number;
  originalFilename: string;
  barCount: number;
  /** 이 파일의 봉 길이(분). 3분봉이면 3, 1분봉이면 1이며 업로드 시 추론합니다. */
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
  /** 실제로 적용된 요율. instrument(SINGLE, 또는 LEVERAGE/INVERSE)별로 담습니다. */
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

/** 무엇이 잘못됐는지에 대한 규칙 기반 해석. 가장 심각한 것부터. */
export type Diagnosis = {
  headline: string;
  findings: Finding[];
};

/** 실행을 무엇으로 요청했고, 그 구간이 봉 기준으로 무엇으로 해석됐는지. */
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
