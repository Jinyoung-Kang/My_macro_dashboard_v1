-- =============================================================================
-- V8__paper_trading.sql
-- 모의 알고리즘 매매(명세 docs/specs/2026-10-24-paper-trading-spec.md, 설계 …-design-plan.md §2) —
-- 백엔드가 쓰는 테이블(ADR 0008). JDBC는 백엔드 store.PaperStore 한 곳에만 있습니다.
--
-- - 금액은 NUMERIC(18,4)(달러), 시각은 TIMESTAMPTZ, 거래일은 DATE(미국 동부 기준 세션 날짜)입니다.
-- - 같은 일을 두 번 해도 결과가 한 번이도록 유일 제약을 둡니다: 결정은 (책, 거래일, 종류), 체결은 주문당 하나,
--   순자산은 (책, 거래일), 게이트 지표는 거래일, 트랙레코드는 (기준일, CIK).
-- - 수집기는 이 테이블들을 읽지도 쓰지도 않습니다(collector/tests/test_table_ownership.py).
-- - 책 하나를 지우면(paper_books) 그 책의 상태·보유·주문·체결·순자산이 함께 지워집니다(ON DELETE CASCADE).
--   초기화(reset)는 책 행을 남기고 나머지만 지웁니다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS paper_books (
    code         TEXT          PRIMARY KEY,
    kind         TEXT          NOT NULL CHECK (kind IN ('RULES', 'CLAUDE', 'BENCHMARK')),
    label        TEXT          NOT NULL,
    initial_cash NUMERIC(18,4) NOT NULL,
    started_on   DATE,                          -- 첫 체결(사후 체결 포함) 거래일. 시작 전이면 NULL
    status       TEXT          NOT NULL DEFAULT 'NOT_STARTED' CHECK (status IN ('NOT_STARTED', 'ACTIVE', 'PAUSED')),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS paper_book_state (
    book_code                   TEXT          PRIMARY KEY REFERENCES paper_books (code) ON DELETE CASCADE,
    cash                        NUMERIC(18,4) NOT NULL,
    nav                         NUMERIC(18,4) NOT NULL,
    equity_value                NUMERIC(18,4) NOT NULL DEFAULT 0,
    exposure_pct                NUMERIC(9,4)  NOT NULL DEFAULT 0,
    last_observed_at            TIMESTAMPTZ,   -- 평가에 쓴 관측치의 수집 시각
    last_processed_collected_at TIMESTAMPTZ,   -- 이 저장본까지 처리함(멱등 키)
    last_nav_trading_date       DATE,
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS paper_positions (
    book_code      TEXT          NOT NULL REFERENCES paper_books (code) ON DELETE CASCADE,
    ticker         TEXT          NOT NULL,
    qty            INTEGER       NOT NULL CHECK (qty > 0),   -- 0주가 되면(전량 매도·역분할 단주 버림) 행을 지웁니다
    avg_cost       NUMERIC(18,4) NOT NULL,
    opened_on      DATE          NOT NULL,
    opened_at      TIMESTAMPTZ   NOT NULL,
    high_watermark NUMERIC(18,4) NOT NULL,
    watermark_at   TIMESTAMPTZ   NOT NULL,
    last_price     NUMERIC(18,4),
    last_price_at  TIMESTAMPTZ,
    price_stale    BOOLEAN       NOT NULL DEFAULT false,
    fill_ref_date  DATE          NOT NULL,      -- 분할 감지 기준(체결에 쓴 봉 날짜)과 그날 종가
    fill_ref_close NUMERIC(18,6) NOT NULL,
    split_note     TEXT,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (book_code, ticker)
);

CREATE TABLE IF NOT EXISTS paper_lockouts (
    book_code          TEXT        NOT NULL REFERENCES paper_books (code) ON DELETE CASCADE,
    ticker             TEXT        NOT NULL,
    until_trading_date DATE        NOT NULL,
    reason             TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (book_code, ticker)
);

CREATE TABLE IF NOT EXISTS paper_decisions (
    id                  BIGSERIAL   PRIMARY KEY,
    book_code           TEXT        NOT NULL REFERENCES paper_books (code) ON DELETE CASCADE,
    as_of_trading_date  DATE        NOT NULL,
    kind                TEXT        NOT NULL CHECK (kind IN ('SCHEDULED', 'MANUAL', 'INITIAL', 'REBALANCE')),
    decided_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    status              TEXT        NOT NULL CHECK (status IN ('OK', 'NO_TRADE', 'REFUSED', 'FORMAT_ERROR', 'API_ERROR', 'SKIPPED')),
    model               TEXT,
    framework_version   INTEGER,
    settings_version    INTEGER     NOT NULL,
    selection_as_of     DATE,
    target_exposure_pct NUMERIC(9,4),
    packet              JSONB,
    packet_hash         TEXT,
    response_raw        TEXT,
    response            JSONB,
    usage               JSONB,
    error               TEXT,
    note_ids            BIGINT[],
    prices_final        BOOLEAN     NOT NULL DEFAULT true,
    latency_ms          BIGINT,
    retry_count         INTEGER     NOT NULL DEFAULT 0,
    UNIQUE (book_code, as_of_trading_date, kind)
);
CREATE INDEX IF NOT EXISTS paper_decisions_recent ON paper_decisions (book_code, as_of_trading_date DESC);

-- 수량·비중·매도 비율에는 범위 검사를 두지 않습니다 — 검증기가 거절한 제안(REJECTED)도 받은 값 그대로 남겨야
-- "무엇을 왜 거절했나"가 기록에 보이기 때문입니다. 범위는 체결 전에 코드(검증기·장부)가 지킵니다.
CREATE TABLE IF NOT EXISTS paper_orders (
    id                 BIGSERIAL   PRIMARY KEY,
    book_code          TEXT        NOT NULL REFERENCES paper_books (code) ON DELETE CASCADE,
    decision_id        BIGINT      REFERENCES paper_decisions (id) ON DELETE SET NULL,
    ticker             TEXT        NOT NULL,
    side               TEXT        NOT NULL CHECK (side IN ('BUY', 'SELL')),
    qty                INTEGER,
    target_weight_pct  NUMERIC(9,4),
    sell_fraction      NUMERIC(9,6),
    timing             TEXT        NOT NULL CHECK (timing IN ('NEXT_OBSERVATION', 'NEXT_CLOSE', 'IMMEDIATE', 'BACKFILL')),
    source             TEXT        NOT NULL CHECK (source IN ('RULE', 'CLAUDE', 'STOP', 'BENCHMARK', 'INITIAL')),
    status             TEXT        NOT NULL CHECK (status IN ('PROPOSED', 'REJECTED', 'PENDING', 'FILLED', 'EXPIRED', 'CANCELLED')),
    reject_reason      TEXT,
    reason             JSONB       NOT NULL DEFAULT '{}',
    as_of_trading_date DATE        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_on         DATE,
    filled_at          TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS paper_orders_open ON paper_orders (book_code, status);
CREATE INDEX IF NOT EXISTS paper_orders_recent ON paper_orders (book_code, created_at DESC);

CREATE TABLE IF NOT EXISTS paper_fills (
    id               BIGSERIAL     PRIMARY KEY,
    order_id         BIGINT        NOT NULL UNIQUE REFERENCES paper_orders (id) ON DELETE CASCADE,
    book_code        TEXT          NOT NULL REFERENCES paper_books (code) ON DELETE CASCADE,
    ticker           TEXT          NOT NULL,
    side             TEXT          NOT NULL CHECK (side IN ('BUY', 'SELL')),
    qty              INTEGER       NOT NULL CHECK (qty > 0),
    price            NUMERIC(18,6) NOT NULL,     -- 슬리피지를 얹은 체결가
    raw_price        NUMERIC(18,6) NOT NULL,     -- 관측치 그대로
    slippage_bp      NUMERIC(9,4)  NOT NULL,
    gross            NUMERIC(18,4) NOT NULL,     -- qty × price
    observed_at      TIMESTAMPTZ   NOT NULL,     -- 관측치 저장본의 수집 시각(사후 체결은 그날 16:00 ET)
    observation_date DATE          NOT NULL,     -- 봉 날짜
    price_source     TEXT          NOT NULL,
    is_backfill      BOOLEAN       NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS paper_fills_recent ON paper_fills (book_code, created_at DESC);

CREATE TABLE IF NOT EXISTS paper_nav_daily (
    book_code     TEXT          NOT NULL REFERENCES paper_books (code) ON DELETE CASCADE,
    trading_date  DATE          NOT NULL,
    nav           NUMERIC(18,4) NOT NULL,
    cash          NUMERIC(18,4) NOT NULL,
    equity_value  NUMERIC(18,4) NOT NULL,
    exposure_pct  NUMERIC(9,4)  NOT NULL,
    prices_final  BOOLEAN       NOT NULL,     -- 확정 종가(마감 + 15분 뒤 관측치)로 평가했는가. 확정값은 미확정값으로 덮지 않습니다
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (book_code, trading_date)
);

CREATE TABLE IF NOT EXISTS paper_gate_log (
    trading_date DATE        PRIMARY KEY,
    inputs       JSONB       NOT NULL,
    level        TEXT        NOT NULL CHECK (level IN ('L0', 'L1', 'L2', 'UNKNOWN')),
    streaks      JSONB       NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS paper_track_record (
    as_of_date        DATE        NOT NULL,
    cik               TEXT        NOT NULL,
    window_years      INTEGER     NOT NULL,
    metric            TEXT        NOT NULL,
    periods_used      INTEGER     NOT NULL,
    periods_total     INTEGER     NOT NULL,
    first_filing_date DATE,
    last_filing_date  DATE,
    cum_return_pct    DOUBLE PRECISION,
    cagr_pct          DOUBLE PRECISION,
    vol_pct           DOUBLE PRECISION,
    mdd_pct           DOUBLE PRECISION,
    sharpe            DOUBLE PRECISION,
    excess_vs_spy_pct DOUBLE PRECISION,
    coverage_min      DOUBLE PRECISION,
    coverage_avg      DOUBLE PRECISION,
    eligible          BOOLEAN     NOT NULL,
    ineligible_reason TEXT,
    rank              INTEGER,
    periods           JSONB       NOT NULL DEFAULT '[]',
    settings_version  INTEGER     NOT NULL,
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (as_of_date, cik)
);

CREATE TABLE IF NOT EXISTS paper_selection_log (
    as_of_date       DATE        PRIMARY KEY,
    selected_ciks    TEXT[]      NOT NULL,
    ranking          JSONB       NOT NULL DEFAULT '[]',
    settings_version INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS paper_framework_versions (
    version             INTEGER     PRIMARY KEY,
    body                JSONB       NOT NULL,
    rendered            TEXT        NOT NULL,
    rendered_tokens_est INTEGER     NOT NULL,
    changelog           TEXT        NOT NULL,
    source_proposal_id  BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS paper_notes (
    id          BIGSERIAL   PRIMARY KEY,
    kind        TEXT        NOT NULL CHECK (kind IN ('MARKET', 'INSIGHT')),
    as_of_date  DATE        NOT NULL,
    expires_on  DATE,
    title       TEXT        NOT NULL,
    body        TEXT        NOT NULL,
    archived    BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS paper_framework_proposals (
    id             BIGSERIAL   PRIMARY KEY,
    note_id        BIGINT      REFERENCES paper_notes (id) ON DELETE SET NULL,
    base_version   INTEGER     NOT NULL,
    request_text   TEXT        NOT NULL,
    model          TEXT        NOT NULL,
    response       JSONB,
    response_raw   TEXT,
    status         TEXT        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'FAILED')),
    error          TEXT,
    result_version INTEGER,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at     TIMESTAMPTZ
);

-- 설정은 한 행(id = 1)뿐이고, 바꿀 때마다 version을 올려 이력(paper_settings_history)에 통째로 남깁니다.
CREATE TABLE IF NOT EXISTS paper_settings (
    id         SMALLINT    PRIMARY KEY CHECK (id = 1),
    version    INTEGER     NOT NULL,
    body       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS paper_settings_history (
    version      INTEGER     PRIMARY KEY,
    body         JSONB       NOT NULL,
    changed_keys TEXT[]      NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 정기 작업 기록. 작업(job)마다 최근 200건만 남깁니다(PaperStore.recordJobRun).
CREATE TABLE IF NOT EXISTS paper_job_runs (
    id          BIGSERIAL   PRIMARY KEY,
    job         TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    outcome     TEXT        NOT NULL,
    detail      JSONB       NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS paper_job_runs_recent ON paper_job_runs (job, started_at DESC);

-- 출발일 종가를 처음 초기화할 때 한 번 얼려 둡니다(유니버스 전체). 사후 체결(벤치마크, 뒤 단계의 규칙·Claude 책)과 리셋 뒤
-- 다시 사후 체결은 모두 이 값을 씁니다. 이력(equity_close)·관측치(paper.quotes)는 수집기가 수정주가(auto_adjust)로 받아,
-- 출발일 뒤 배당락이 지나면 출발일 '종가'가 낮게 다시 쓰입니다 — 나중에 그 값으로 사면 주식 수가 늘어 배당이 수익률에
-- 섞이고(장부는 가격 수익률), 같은 날 같은 책을 언제 리셋했느냐에 따라 출발점이 달라집니다.
CREATE TABLE IF NOT EXISTS paper_start_prices (
    ticker       TEXT          PRIMARY KEY,
    trading_date DATE          NOT NULL,      -- 출발 거래일(dashboard.paper.start-date)
    close        NUMERIC(18,6) NOT NULL,      -- 그날 종가(얼린 시점의 값 그대로)
    source       TEXT          NOT NULL,      -- equity_close | paper.quotes (paper_fills.price_source와 같은 말)
    captured_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 마지막에 만듭니다 — 백엔드가 이 테이블로 "스키마 준비됨"을 봅니다(SchemaCheck).
CREATE TABLE IF NOT EXISTS paper_api_usage (
    day                DATE        PRIMARY KEY,
    calls              INTEGER     NOT NULL DEFAULT 0,
    input_tokens       BIGINT      NOT NULL DEFAULT 0,
    output_tokens      BIGINT      NOT NULL DEFAULT 0,
    cache_read_tokens  BIGINT      NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT      NOT NULL DEFAULT 0
);
