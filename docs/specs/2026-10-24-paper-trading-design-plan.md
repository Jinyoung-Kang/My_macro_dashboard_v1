# 설계 계획서 — 모의 알고리즘 매매

> 명세: [2026-10-24-paper-trading-spec.md](2026-10-24-paper-trading-spec.md). 결정에 따라 정해진 곳은 "(D#)"로 표시했습니다.
>
> - 상태: **구현 중**(2026-10-24, 4차 — 단계 0 결과 반영: 가격 저장 방식(D23), CUSIP 매핑표(D25), 선행 CIK, 구조화 출력 제약 / 사용자 추가 결정: 두 기관 그룹 섞기(D27)). 기준 코드 `main@590260f`, 브랜치 `claude/paper-trading-2026-10`(push 금지, D0).
> - 단계 0 근거 표: [2026-10-24-paper-trading-step0.md](2026-10-24-paper-trading-step0.md). 요약은 §13.
> - 이 문서는 **어떻게, 어떤 순서로** 만들지 정합니다. 파일·클래스 이름은 구현 때 바뀔 수 있지만 배치 규칙(§1)은 바뀌지 않습니다.

---

## 0. 구조상 바뀌는 것 여섯 가지

### 0-1. 기능 모듈 `feature/paper` 추가

컨트롤러 3개 + 서비스 10여 개가 한 폴더에 들어간다. 다른 기능의 **서비스**는 쓰되(`feature.ai.AiService`) 컨트롤러는 쓰지 않는다(`ArchitectureRulesTest.featuresShareServicesNotControllers`). 컨트롤러는 자기 기능의 서비스 + `support` + `config`만 쓰고(`controllersGoThroughTheirOwnServices`), 생성자 인자는 5개 이하(`RouteInventoryTest.controllersStaySmall`).

### 0-2. 백엔드 소유 테이블 `paper_*` 추가 (ADR 0008 연장)

`db/migrations/V8__paper_trading.sql` 한 파일. 쓰는 프로세스는 백엔드만, JDBC는 `store.PaperStore`에만(`jdbcOnlyInStore`). 수집기 테이블은 읽기만 한다(`backendDoesNotWriteCollectorTables`). 테이블이 없으면 `SchemaCheck.tableExists("paper_api_usage")`로 전 API가 `available:false`+"make up 후 다시 보세요"를 돌려준다.

### 0-3. 정기 작업 2개 추가

`PaperDecisionJob`(10분 주기 폴링, 거래일마다 1회 결정)과 `PaperRiskJob`(5분 주기, 새 저장본이 있을 때만 일함). 둘 다 `@Scheduled(fixedDelayString)` + `StoreReader.quietly` + DB 유일 제약으로 멱등. `dashboard.paper.enabled`(기본 true, 통합 테스트는 false)로 끈다.

### 0-4. AI 호출 경로에 "구조화 출력" 추가

`AiService`에 메서드 하나를 더한다: 모델·시스템 프롬프트·사용자 프롬프트·JSON 스키마·시간 한도를 받아 `OutputConfig.format(JsonOutputFormat)`으로 호출하고 텍스트·토큰 사용량·중단 사유를 돌려준다. **리포트 기능의 `ENGINES`·`FAILOVER_ORDER`·번역 경로는 건드리지 않는다.**

### 0-5. 전달 방식이 다르다 (D0)

이 기능의 모든 커밋은 `claude/paper-trading-2026-10`에만 있다. `make test`는 로컬에서 돌리고, 운영은 이 브랜치에서 `make up`. main이 앞서가면 브랜치를 `git rebase main`으로 따라간다. 절차는 §12-3.

### 0-6. 수집기 변경 — 후보 기관 13F 30분기, 모의 매매 유니버스의 시세·10년 이력 (D6·D23·D25)

이 기능이 수집기를 바꾸는 유일한 곳이다. **기존 태스크·저장본(`equity.price_history` 5년·138종목, `sec.13f.{cik}.q8`)과 그 화면은
그대로 둔다** — 단계 0에서 10년·550여 종목을 한 저장본에 넣으면 16MB 기준과 백엔드 메모리를 넘는다는 것을 쟀다(D23, §13).

| 변경 | 내용 | 영향 범위 |
|---|---|---|
| 새 태스크 `sec_13f_track_record` (`weekly`) | `indicators.TRACK_RECORD_FILERS`(후보 27곳, 선행 CIK 포함)의 13F를 기관당 **30분기**까지 받아 `sec.13f.{cik}.q30`에 저장. 실행마다 **예산**(기본 400건, 공시 하나 약 0.4초) 안에서 아직 없는 공시만 채우고 나머지는 다음 실행에. 선행 CIK가 있으면 선행 CIK의 마지막 분기까지 + 현재 CIK의 그 뒤 분기를 잇는다 | 기존 `sec_13f`(12곳·q8)와 13F 화면은 그대로. 같은 서비스(`services/sec13f.collect_13f`)를 쓴다 |
| `collect_13f`에 `reuse_stored=True`·`list_count` 옵션 | 저장본에 있는 `filingDate`는 다시 받지 않는다. 목록은 `count=100`(단계 0: 27곳 모두 한 페이지에 원본 30건), 모자라면 `start=` 다음 페이지(최대 3쪽). 기존 호출은 기본값으로 동작 변화 없음 | 서비스 한 곳 |
| 새 매핑표 `equities_cusip.TRACK_RECORD_CUSIPS` (CUSIP → 야후 티커, D25) | 후보 기관 보유 상위 600개 CUSIP을 OpenFIGI·SEC `company_tickers.json`·야후 이력으로 대조해 채택한 498개(§4-0 ③). **`q30` 저장본에만** 붙인다(`attach_tickers(…, cusip_map=…)`) | 기존 `EQUITY_MAP`·`q8` 티커는 그대로 |
| 새 태스크 `paper_quotes` (`slow`, 1시간) | 모의 매매 유니버스(`EQUITY_MAP` 티커 + `TRACK_RECORD_CUSIPS` 티커 + 유니버스 ETF 17 + SPY·QQQ) 최근 3개월 일봉 → 스냅샷 `paper.quotes`. 장중에는 마지막 봉이 지연 시세 | 장부 관측치의 유일한 출처(§3-1) |
| 새 태스크 `paper_price_history` (`weekly`, 12시간) | 같은 유니버스 10년 일봉(수정주가) → `timeseries` 데이터셋 `equity_close`(종목 = `series_id`). `put_timeseries` upsert라 배당·분할로 바뀐 과거 값만 다시 쓴다 | 트랙레코드·모멘텀·분할 감지의 이력(백엔드는 필요한 종목만 범위로 읽음) |
| `catalog.TRACK_RECORD_QUARTERS = 30`·`SNAP_PAPER_QUOTES`·`TS_EQUITY_CLOSE` + `Datasets` 같은 상수 | 이름 상수를 같이(`DatasetsParityTest`) | |
| `registry.py` 등록, `docs/DATA_SOURCES.md` 태스크 23개 | | |

---

## 1. 전체 구조

```
┌──────────────────────────── Collector ─────────────────────────────────────────┐
│ paper.quotes(1h, 3개월, 유니버스) · timeseries equity_close(12h, 10y) · macro.collected(5m)│
│ fred(1h) · fx_history(1h) · sec.13f.{cik}.q30(후보 27곳, 새 태스크) · regime_log(백엔드)│
└───────────────────────────────┬────────────────────────────────────────────────┘
                                ▼  (읽기만, quietly)
┌──────────────────────────── Backend feature/paper ─────────────────────────────┐
│                                                                                │
│  PaperRiskJob(5m) ──▶ PaperMarketData.latestObservation() ──▶ PaperLedgerService│
│                                                                 ├ 대기 주문 체결 │
│                                                                 ├ 최고가·스탑    │
│                                                                 ├ 분할 감지      │
│                                                                 └ 평가·순자산    │
│                                                                                │
│  PaperDecisionJob(10m) ── 거래일 확정 종가 확인 ──┬─▶ GateIndicator → paper_gate_log│
│                                                  ├─▶ PaperSelectionService(재선정일)│
│                                                  │     TrackRecord13F → 순위·선정 │
│                                                  ├─▶ RuleBookService → 주문       │
│                                                  └─▶ ClaudeBookService           │
│                                                        ├ PacketBuilder            │
│                                                        ├ AiService.structured(…)  │
│                                                        ├ OrderValidator           │
│                                                        └ 주문 + 결정 기록         │
│                                                                                │
│  FrameworkService · NoteService · PaperSettingsService · PaperQueryService      │
│  PaperAdminService(초기화·일시정지·리셋)                                        │
│                                   ▼                                            │
│                          store.PaperStore (JDBC, paper_* 테이블만)              │
└────────────────────────────────────────────────────────────────────────────────┘
                                   │ /api/paper/* (OWNER)
                                   ▼
   Frontend /paper (개요 · 기관 선정 · 보유·체결 · 결정 로그 · 프레임·메모 · 설정)
```

### 1-1. 패키지 배치

| 패키지 | 클래스 | 책임 |
|---|---|---|
| `analytics` (순수, `java.*`만) | `UsMarketCalendar` | NYSE 휴장일 규칙, 거래일 판정, 직전·다음 거래일, 마감 시각, **13F 공시 기한 다음 거래일**(R5) |
| | `PaperLedger` | 체결 적용, 수량 내림, 슬리피지, 평가·노출 |
| | `TrailingStop` | 최고가 갱신, 스탑 판정, 재진입 금지 |
| | `SplitDetector` | 분할 판정·수량 조정 |
| | `GateIndicator` | OS 문자 그대로의 게이트 단계(L0/L1/L2)와 연속 일수 — **지표 전용**(D7) |
| | `TrackRecord13F` | 공시일 기준 복제 수익률·커버리지·창 통계·자격·순위(R7·R9·R10) |
| | `Signal13F` | 선정 기관의 분기 비교 → 매수 후보·매도 신호(R2), 배분(R4) |
| | `OrderValidator` | Claude 제안 주문의 하드 규칙(C4, §5-4) |
| | `PaperStats` | 누적·CAGR·변동성·MDD·샤프·초과·베타(L10) |
| `store` | `PaperStore` | `paper_*` 읽기·쓰기 전부. `TransactionTemplate`로 `atomically(Supplier)`. `ready()` |
| `feature/paper` | `PaperMarketData` | 저장본 읽기: 관측치(`paper.quotes` 마지막 봉), 가격 이력(`timeseries` `equity_close`, 기준일 이전으로 자르기), 매크로 카드, FRED, 환율, 13F 원본(`q30`, 저장본의 `ticker`) |
| | `PaperSelectionService` | 후보 기관 전부의 트랙레코드 계산·캐시(`paper_track_record`)·선정(`paper_selection_log`)·수집 진행률. 기존 `feature/insight/TrackRecordService`(국면 판정 성과)와 빈 이름이 겹치지 않게 이름을 바꿈 |
| | `PaperLedgerService` | 관측치 하나를 네 책에 적용. 멱등 |
| | `RuleBookService` | 규칙 책 일일 결정(R1~R6) |
| | `ClaudeBookService`, `PacketBuilder` | 패킷→호출→검증→주문→기록(C1~C11) |
| | `FrameworkService`, `NoteService`, `PaperSettingsService`, `PaperAdminService`, `PaperQueryService` | 프레임·메모·설정·관리·화면 응답 |
| | `PaperDecisionJob`, `PaperRiskJob` | 정기 작업(§6) |
| | `PaperController`, `PaperFrameworkController`, `PaperSettingsController` | HTTP 경계(§7) |
| `feature/ai` | `AiService.structured(...)` 추가 | §5-1 |
| `web`/`config` | `AccessPolicy`에 `owner(...)` 29개, `dashboard.paper.*` `@Value` | |
| collector | `tasks/filings.py::task_sec_13f_track_record`, `tasks/paper.py::task_paper_quotes`·`task_paper_price_history`, `indicators.TRACK_RECORD_FILERS`·`PAPER_ETFS`, `equities_cusip.TRACK_RECORD_CUSIPS`, `catalog.TRACK_RECORD_QUARTERS`·`SNAP_PAPER_QUOTES`·`TS_EQUITY_CLOSE` | §0-6 |

`analytics` 하위 패키지도 `analyticsIsPure`는 통과하지만(같은 접두), 기존 관례대로 `analytics/`에 평평하게 둔다.

### 1-2. ADR 준수 표

| ADR | 지키는 방식 |
|---|---|
| 0001 의존 규칙 | `web → feature/paper → read → store`, `feature → analytics·support`. 순환 없음 |
| 0002 Jackson 3 | 백엔드는 `tools.jackson`. SDK 스키마는 Jackson 3로 읽은 `Map`을 `JsonValue.from(...)`으로 |
| 0004 화면 계층 | 화면은 계산하지 않음. `endpoints.paper.*`, `useAsyncAction`, `lib/paper.ts` 순수 함수 + 테스트 |
| 0008 백엔드 테이블·정기 작업 | `paper_*`는 백엔드만. V8. `quietly`. 유일 제약. `SchemaCheck` |
| 0009 Point-in-Time | 트랙레코드는 공시일 **다음 영업일**부터 복제. 10-23 초기 패킷은 기준일 이전 값만. FRED는 `PointInTime.asKnownOn` |
| 0010 접근 정책 | 29개 경로 모두 `OWNER`. `OWNER_ONLY_MENUS`에 `/paper` |
| 0015 13F 증권 식별 | 기존 두 단계 규칙(정확 일치 → 법인격 제거 일치)은 그대로. `q30`에만 **CUSIP 정확 일치 매핑표**를 앞에 더한다(D25) — 같은 CUSIP = 같은 증권(결정 1)이라 이름 규칙보다 엄격하다. 채권·우선주·워런트 배제 규칙은 그대로 먼저 적용 |
| 0016 로컬 무로그인 | 변화 없음 |

---

## 2. 데이터 모델 — `V8__paper_trading.sql`

모두 `IF NOT EXISTS`, 금액 `NUMERIC(18,4)`, 시각 `TIMESTAMPTZ`, 거래일 `DATE`(미국 동부). V7 양식.

| 테이블 | 열(요지) | 제약·비고 |
|---|---|---|
| `paper_books` | `code TEXT PK`, `kind` CHECK(RULES·CLAUDE·BENCHMARK), `label`, `initial_cash`, `started_on`, `status` CHECK(NOT_STARTED·ACTIVE·PAUSED), `created_at` | 네 행. 규칙·Claude 책은 사용자가 "시작"을 누르기 전 `NOT_STARTED`(L2) |
| `paper_book_state` | `book_code PK FK`, `cash`, `nav`, `equity_value`, `exposure_pct`, `last_observed_at`, `last_processed_collected_at`, `last_nav_trading_date`, `updated_at` | 멱등 키 `last_processed_collected_at` |
| `paper_positions` | `book_code`, `ticker`, `qty INTEGER`, `avg_cost`, `opened_on`, `opened_at`, `high_watermark`, `watermark_at`, `last_price`, `last_price_at`, `price_stale`, `fill_ref_date`, `fill_ref_close`, `split_note`, `updated_at` | `PK (book_code, ticker)` |
| `paper_lockouts` | `book_code`, `ticker`, `until_trading_date`, `reason`, `created_at` | `PK (book_code, ticker)` |
| `paper_orders` | `id`, `book_code`, `decision_id FK NULL`, `ticker`, `side`, `qty NULL`, `target_weight_pct NULL`, `sell_fraction NULL`, `timing` CHECK(NEXT_OBSERVATION·NEXT_CLOSE·IMMEDIATE·BACKFILL), `source` CHECK(RULE·CLAUDE·STOP·BENCHMARK·INITIAL), `status` CHECK(PROPOSED·REJECTED·PENDING·FILLED·EXPIRED·CANCELLED), `reject_reason`, `reason JSONB`, `as_of_trading_date`, `created_at`, `expires_on`, `filled_at` | 인덱스 `(book_code, status)` |
| `paper_fills` | `id`, `order_id FK`, `book_code`, `ticker`, `side`, `qty`, `price`, `raw_price`, `slippage_bp`, `gross`, `observed_at`, `observation_date`, `price_source`, `is_backfill`, `created_at` | `UNIQUE (order_id)` |
| `paper_nav_daily` | `book_code`, `trading_date`, `nav`, `cash`, `equity_value`, `exposure_pct`, `prices_final`, `created_at` | `PK (book_code, trading_date)` |
| `paper_gate_log` | `trading_date PK`, `inputs JSONB`, `level` CHECK(L0·L1·L2·UNKNOWN), `streaks JSONB`, `created_at` | **지표**. Claude 책·규칙 책 어느 쪽도 구속하지 않음(D7·D19) |
| `paper_track_record` | `as_of_date`, `cik`, `window_years`, `periods_used`, `periods_total`, `first_filing_date`, `last_filing_date`, `cum_return_pct`, `cagr_pct`, `vol_pct`, `mdd_pct`, `sharpe`, `excess_vs_spy_pct`, `coverage_min`, `coverage_avg`, `eligible BOOL`, `ineligible_reason`, `rank INT NULL`, `periods JSONB`(구간별 시작·끝·수익률·커버리지), `computed_at`, `group_code`(V9 — `A` 후보 7년, `B` 기존 메뉴 2년), `data_source`(V9 — 계산에 쓴 저장본 `q30`·`q8`, 단계 1b 행은 NULL) | `PK (as_of_date, group_code, cik)`(V9). 재선정일마다 한 세트, 수동 재계산은 같은 날 덮어씀 |
| `paper_selection_log` | `as_of_date PK`, `selected_ciks TEXT[]`, `ranking JSONB`(상위 10 요약), `settings_version`, `created_at` | 선정 이력 |
| `paper_decisions` | `id`, `book_code`, `as_of_trading_date`, `kind` CHECK(SCHEDULED·MANUAL·INITIAL·REBALANCE), `decided_at`, `status` CHECK(OK·NO_TRADE·REFUSED·FORMAT_ERROR·API_ERROR·SKIPPED), `model`, `framework_version`, `settings_version`, `selection_as_of DATE NULL`, `target_exposure_pct NULL`, `packet JSONB`, `packet_hash`, `response_raw`, `response JSONB`, `usage JSONB`, `error`, `note_ids BIGINT[]`, `prices_final`, `latency_ms`, `retry_count INT` | `UNIQUE (book_code, as_of_trading_date, kind)` |
| `paper_framework_versions` | `version PK`, `body JSONB`, `rendered TEXT`, `rendered_tokens_est`, `changelog`, `source_proposal_id NULL`, `created_at` | |
| `paper_framework_proposals` | `id`, `note_id NULL`, `request_text`, `model`, `response JSONB`, `response_raw`, `status` CHECK(PENDING·APPROVED·REJECTED·FAILED), `created_at`, `decided_at`, `result_version NULL`, `error` | |
| `paper_notes` | `id`, `kind` CHECK(MARKET·INSIGHT), `as_of_date`, `expires_on NULL`, `title`, `body`, `created_at`, `archived` | |
| `paper_settings` / `paper_settings_history` | 한 행 JSONB + 버전 이력(`changed_keys TEXT[]`) | |
| `paper_job_runs` | `id`, `job`, `started_at`, `finished_at`, `outcome`, `detail JSONB` | 최근 200건 유지 |
| `paper_start_prices` | `ticker PK`, `trading_date`, `close`, `source`, `captured_at` | 출발일 종가를 처음 초기화 때 유니버스 전체로 한 번 얼림. 사후 체결·리셋은 이 값만 씀 — 수정주가 이력은 배당락 뒤 출발일 종가가 낮게 다시 쓰여 배당이 장부에 섞이기 때문 |
| `paper_api_usage` | `day PK`, `calls`, `input_tokens`, `output_tokens`, `cache_read_tokens` | 일일 상한·월 비용 |

---

## 3. 장부 엔진 (`analytics` 순수 함수 + `PaperLedgerService`)

### 3-1. 관측치와 체결 (L3·L4)

**관측치** = `paper.quotes` 저장본에서 티커별 `(barDate = dates[-1], price = close[-1], isStale)`과 저장본 `collected_at`(D23 — 종목·ETF 모두 이 저장본 하나). 분할 감지·모멘텀의 과거 종가는 `timeseries` `equity_close`.

**체결 가능 조건**(모두 만족):
1. `observation.collected_at > order.created_at`.
2. `barDate > order.as_of_trading_date` — 주말·휴장일에 수집기가 직전 종가를 매시간 다시 저장하므로 조건 1만으로는 결정이 이미 본 종가로 체결되는 룩어헤드가 생긴다.
3. `NEXT_CLOSE`면 추가로 **확정 종가**: `barDate`가 세션 날짜이고 `collected_at ≥ 마감 + 15분`.
4. `isStale` 티커는 다음 관측치를 기다린다.

**체결가** = `price × (1 ± bp/10000)`. BUY 수량 = `floor(목표금액 / 체결가)`, 목표금액 = `target_weight_pct × 체결 시점 순자산`. 현금 부족이면 가능한 수량까지(`reason`에 기록). SELL = `sell_fraction × 보유` 내림, 1 미만이면 전량.

**만료**: 기준 거래일 + 3거래일 → `EXPIRED`.

**관측치 처리 순서**(책마다 한 트랜잭션): 만료·지난 재진입 금지 정리 → 분할 감지(가격 갱신) → 매도 체결 → 최고가 갱신·스탑 생성·즉시 체결(+ 재진입 금지) → 매수 체결 → 평가 → 그 세션의 마감 뒤 관측치면 `paper_nav_daily` upsert → `last_processed_collected_at` 갱신. 분할 감지를 먼저 하는 것은 분할 뒤 반값 관측가가 분할 전 최고가와 비교돼 가짜 스탑이 나지 않게, 스탑을 매수 앞에 두는 것은 이번 관측치에서 스탑에 걸린 종목의 대기 매수가 재진입 금지로 취소되게(사자마자 같은 관측치의 스탑으로 팔리지 않게) 하려는 것이다. 순자산 줄은 관측 시각의 세션(`currentOrLastSession`)이 다수 티커의 봉 날짜와 같을 때만 쓰고(다음 날 개장 직후 관측치가 전날 줄을 덮지 않게), 보유가 모두 그 세션의 확정 봉으로 평가됐을 때만 `prices_final = true`다.

**BACKFILL 체결**은 `PaperAdminService`가 10-23 종가로 직접 만든다(`observed_at = 10-23 16:00 ET`). 종가는 `paper_start_prices`에 얼린 값이다(§2).

### 3-2. 주문 상태

```
PROPOSED ─검증 통과─▶ PENDING ─관측치─▶ FILLED
    └─검증 거절─▶ REJECTED     └─3거래일─▶ EXPIRED
STOP: 생성과 동시에 FILLED      일시정지·리셋: PENDING → CANCELLED
```

### 3-3. 트레일링 스탑 (L7, D10) — 변경 없음

`high_watermark = max(…, price)` 관측치마다. `price ≤ hw × 0.92` → 전량 매도. `paper_lockouts(until = 관측일 + 20거래일)`. SHY·벤치마크 제외.

### 3-4. 분할 감지 (L8) — 변경 없음

`r = fill_ref_close / 최신 시계열의 fill_ref_date 종가`. `0.9~1.1`은 무시(배당 조정), 밖이면 단순 분수로 반올림해 수량·평단·최고가 조정 + 기록 + 배지. 참조일이 사라지면 `price_stale`.

### 3-5. 게이트 **지표** (`GateIndicator`, D7)

이전 설계의 `ExposureGate`를 **지표**로 바꾼다. 계산은 같다(G1 10Y<4.80, G2 10Y≤4.75, G3 브렌트≤95, G4 WTI≤90, G5 USD/JPY≥155, 연속 일수, L0/L1/L2). 쓰는 곳은 세 군데뿐이다.
- `paper_gate_log`에 거래일마다 기록(우리가 쌓는 이력).
- 개요 화면의 "OS 문자 그대로 게이트" 카드.
- Claude 패킷 §5-2 섹션 5에 "참고 지표(구속하지 않음)"로.

규칙 책·검증기 어느 쪽도 이 값을 읽지 않는다. 임계치는 설정의 `gate.*`에 "참고"로 남는다.

### 3-6. 배당·가격 없음 (D12) — 변경 없음

장부는 가격 수익률. 트랙레코드(§4-0)는 수정주가 수익률(배당 포함). 화면 한계 문구 3이 두 숫자를 섞지 말라고 적는다.

### 3-7. 미국 달력 (`UsMarketCalendar`)

휴장일 규칙(화면과 동일), 직전·다음 거래일, 마감 시각(`America/New_York` 16:00), **13F 공시 기한**: 분기 말 + 45일, 주말·휴일이면 다음 영업일(2026-11-16, 2027-02-16, 2027-05-17, 2027-08-16). 재선정일 = 기한 다음 거래일(R5·D24).

### 3-8. 통계 (`PaperStats`, L10) — 변경 없음

---

## 4. 규칙 책

### 4-0. 기관 선정 — 7년 롤링 트랙레코드 (R1·R7~R10, D6·D20~D24)

#### ① 후보 기관 (D21) — CIK는 단계 0에서 EDGAR로 확인(27곳, [단계 0 표 A](2026-10-24-paper-trading-step0.md))

| 분류 | 후보 | 비고 |
|---|---|---|
| 가치·집중 | Berkshire Hathaway, Baupost Group, Pershing Square, Third Point, Greenlight Capital, Appaloosa, Akre Capital, Ruane Cunniff(Sequoia), Dodge & Cox, Davis Selected Advisers, Markel, Himalaya Capital, Gardner Russo & Quinn, Scion Asset Management | 사이언은 2025-11 뒤 공시 없음 → 재선정일에 최신 공시가 없으면 그 분기 제외(R5) |
| 성장·테크 | Tiger Global, Lone Pine, Viking Global, Coatue, Altimeter, D1 Capital, Maverick Capital, Baillie Gifford, Duquesne Family Office, Fisher Asset Management | |
| 매크로 | Bridgewater Associates, Soros Fund Management, Tudor Investment | 매크로 펀드의 13F는 주식 롱 사이드만 보인다 — 롱온리 한계 문구 |
| 선행 CIK | Greenlight Capital = DME Capital Management(0001489933, 2024-03-31 분기부터) ← Greenlight Capital Inc(0001079114, 2023-12-31까지). Pershing Square = Pershing Square Inc.(0002026053, 2026-06-30 분기부터) ← Pershing Square Capital Management(0001336528, 2026-03-31까지) | 선행 CIK의 마지막 보고 분기까지 + 현재 CIK의 그 뒤 분기. 겹치는 분기는 선행 CIK(퍼싱의 2025-06-30~2026-03-31 PSI 공시는 하워드 휴스 지분 한 종목뿐) |
| **제외** | 뱅가드·블랙록·스테이트스트리트·노르웨이·국민연금·CPPIB·APG(인덱스·준인덱스), 시타델·밀레니엄·르네상스·투시그마·DE쇼·서스쿼해나·제인스트리트(퀀트·멀티전략·마켓메이커) | 13F 롱온리가 실체를 왜곡 |

기존 12곳 중 액티브 5곳은 후보에 포함되므로 `sec.13f.{cik}.q8`(기존)과 `q30`(새)이 함께 존재한다. 백엔드는 트랙레코드·신호에 `q30`을, 기존 13F 화면은 `q8`을 읽는다. 커버리지 미달 4곳(단계 0 표 C)도 후보 목록에 남겨, 기관 선정 탭에 미달 사유와 함께 보인다(R9) — 자격이 없으니 선정되지 않는다.

#### ② 수집 (`task_sec_13f_track_record`, §0-6)

```
for filer in TRACK_RECORD_FILERS (저장본이 가장 적은 기관부터):
    stored = previous_payload(snap_sec_13f(cik, 30))
    quarters = collect_13f(cik, max_quarters=30, previous_quarters=stored.quarters,
                           reuse_stored=True, list_count=100)   # 저장된 filingDate는 재다운로드 없음
    for pred in filer.predecessors: quarters = chain(quarters, collect_13f(pred, …))   # 선행 CIK 잇기
    attach_tickers(quarters, cusip_map=TRACK_RECORD_CUSIPS)
    put_snapshot(..., {"cik", "quarters", "predecessors", "complete": len(quarters) >= 30 or no_more})
    budget -= 새로 받은 공시 수;  if budget <= 0: break
```
- 예산 기본 400건/실행(`TRACK_RECORD_FETCH_BUDGET`), SEC 10req/s 제한은 기존 `sec_rate_limit`. 단계 0 측정: 기관당 30분기 10~18초(공시 하나 약 0.4초), 27곳 약 810건 ≈ 6분 → 두 번의 실행으로 다 채운다(태스크 상한 `weekly` 1,800초). 화면 "기관 선정" 탭에 기관별 `저장 분기/30`·`complete` 진행률.
- 실패·빈 공시 처리는 기존 규칙(`UNUSABLE_FILING` 건너뜀, 일시 실패는 저장본 유지).
- `complete=false`인 기관은 트랙레코드에서 "수집 중"으로 표시되고 자격 판정에서 빠진다(구간 수 부족).

#### ③ 티커 매핑 확장 (R9, ADR 0015)

단계 0에서 바꿨다(D25). 이름(`EQUITY_MAP`)으로는 같은 증권의 표기가 너무 많고('FACEBOOK INC'·'META PLATFORMS, INC., CLASS A' — 같은 CUSIP 30303M102), 'ISHARES TR' 한 이름 아래 ETF가 수백 개라 **CUSIP으로 붙인다**. 절차: ① 7년 창 분기에서 지금 매핑표로 덮이지 않는 지분 증권을 CUSIP별로 모아 기관 내 비중 합으로 순위 → ② 상위 600개를 OpenFIGI `v3/mapping`(CUSIP → US 합성 티커·이름·종류)으로 조회 → ③ SEC `company_tickers.json` 등록 이름과 야후 10년 이력(첫·마지막 날짜)을 붙여 이름 토큰을 대조 → ④ 사람(에이전트 검토 포함)이 "다른 증권인 행"을 찾아 뺀다 → `equities_cusip.TRACK_RECORD_CUSIPS`(CUSIP → 야후 티커)에 적는다. 외국 발행사 CUSIP(CINS)은 `ID_CINS`로 묻는다. OpenFIGI가 US 티커를 주지 않는 CUSIP(상장폐지·인수, 역분할·합병 전의 옛 CUSIP 101개)은 붙이지 않는다 — 가격도 구할 수 없다. 이 표는 `q30`에만 쓰고, 채권·우선주·워런트 배제(`classify_holding`)를 먼저 적용한다. 결과: 600개 중 498개 채택, 커버리지 기준(D22) 통과 27곳 중 23곳(단계 0 표 C·E).

#### ④ 계산 (`TrackRecord13F`, R7)

입력: 기관의 공시 목록(`filingDate`, `reportDate`, `holdings[{ticker|null, value}]`), 티커별 수정주가 `NavigableMap<LocalDate,Double>`, SPY 시계열, 기준일 `asOf`, 설정(창 7년, 자격 기준).

```
filings F_1 < F_2 < … < F_n  (filingDate 오름차순, filingDate ≤ asOf)
for i in 1..n:
    start_i = 다음 영업일(F_i)                  # 공시를 본 뒤 살 수 있는 첫날 (PointInTime)
    end_i   = i < n ? 다음 영업일(F_{i+1}) : asOf
    covered = holdings_i with ticker and price at start_i and end_i
    coverage_i = Σ value(covered) / Σ value(all)
    w_j = value_j / Σ value(covered)
    R_i = Σ_j w_j × (P_j(end_i) / P_j(start_i) − 1)       # valueAsOf(floor) 조회
    R_spy_i = SPY(end_i)/SPY(start_i) − 1
window = periods with end_i > asOf − 7y   (첫 구간은 asOf−7y에서 시작하도록 start를 자르고 가격도 그 날짜로)
cum   = Π(1+R_i) − 1 ;  days = end_last − start_first ;  cagr = (1+cum)^(365/days) − 1
vol   = sd(R_i) × √(365/평균 구간 일수) ; sharpe = mean(R_i)/sd(R_i) × √(365/평균 구간 일수)
mdd   = 구간 말 누적 경로의 최대 낙폭 ; excess = cum − cum_spy(같은 창)
eligible = periods_used ≥ 24 && min(coverage) ≥ 0.5 && avg(coverage) ≥ 0.7
```
- 공시 전 보유를 쓰지 않는다(룻어헤드 금지). 정정 공시는 수집 단계에서 이미 제외.
- 수정주가라 배당이 들어간다. 구간 안에 사라진 티커(상장폐지)는 그 구간에서 마지막 가격으로 끝낸 것으로 보고 `coverage`에 반영한다.
- 결과 구간 목록을 `periods JSONB`에 남겨 화면에서 "어느 분기가 왜 빠졌나"를 볼 수 있게 한다.

#### ⑤ 순위·선정 (R10·R1·D20·D24)

- `eligible`인 기관을 `metric`(기본 `CAGR`, 선택 `EXCESS_VS_SPY`·`SHARPE`) 내림차순으로 → 상위 `topN`(5) → `paper_selection_log(as_of_date)`.
- 재선정일 = 각 분기 13F 공시 기한 다음 거래일. 그날의 `asOf`로 계산한다. 그날까지 최신 분기를 공시하지 않은 후보는 "최신 공시 없음"으로 그 분기 선정에서 제외(R5).
- 10-23 초기 선정은 `asOf=2026-10-23`(2026-06-30 분기 공시까지).
- 수동 재계산(`POST /run/rerank`)은 같은 `as_of_date`를 덮어쓰고 선정은 바꾸지 않는다(기록용). 선정은 재선정일에만 바뀐다.
- 자격 기관이 5곳 미만이면 있는 만큼으로 가고 결정 기록에 적는다. 0곳이면 규칙 책은 매수 없이 SHY만.

#### ⑥ 그룹 B — 기존 13F 메뉴의 최근 성과 상위 4곳 (R1·D27)

사용자 결정(2026-10-24 3차): 그룹 A(위 ⑤의 상위 5)만이 아니라, 이미 지켜보던 '기관 13F 포트폴리오 분석' 메뉴 12곳(`indicators.INSTITUTIONS`)에서
수익률 상위 4곳의 전략·패턴·장점을 섞는다.
- 지표: ④와 **같은 방법**(공시일 다음 거래일 복제, 수정주가)의 **최근 2년** 창(`blend.groupB.windowYears` 2) CAGR. 기존 메뉴 저장본이 8분기(q8)라 2년이 공통으로 덮는 길이다.
  그 기관의 `q30`(CUSIP 매핑 티커)이 있으면 그것을, 없으면 `q8`(이름 매핑 티커)을 쓴다.
- 자격: ④와 같은 커버리지 기준(최소 50%·평균 70%)과 R5(최신 분기 공시), 구간 ≥ `blend.groupB.minPeriods`(6 — 2년 창 8구간의 75%).
  **인덱스·연기금은 덮인 대형주 쪽만 보여 복제 수익률이 부풀므로** 커버리지 기준으로 빠진다(단계 1b 원형: 노르웨이 18.5%·블랙록 18.1%·뱅가드 17.2%이지만 평균 커버리지 57~62%).
- 2026-10-23 원형: 자격 4곳 = Duquesne 26.8%·Bridgewater 16.0%·Fisher 15.4%·Berkshire 13.8%(Scion 27.5%는 최신 분기 공시 없음).
- 선정 시점·Point-in-Time은 그룹 A와 같다(재선정일·초기 선정, 그날까지의 공시·가격만). 결과는 `paper_track_record`에 `group_code='B'`로(V9), 선정은 같은
  `paper_selection_log` 행의 `ranking.groupB`에 남긴다. `selected_ciks`는 신호 풀(A ∪ B).
- 구현 메모(단계 2): 그룹 B에는 "수집 중" 자격 규칙을 두지 않는다(q8에는 완료 표시가 없음 — 짧은 저장본은 구간 수 기준이 거름). 후보 목록
  저장본이 없어 그룹 A를 계산하지 못하는 기준일은 그룹 B도 계산하지 않는다(그룹 B만으로 선정하지 않으므로). 출처는 `data_source` 열에 남겨
  화면이 계산 때의 출처를 보인다. D27 전에 남긴 선정 행(순위표 배열)은 그룹 A만의 풀(그룹 A 순위 가중)로 읽는다. ETF 상한은
  `universeExtraTickers`와 SPY·QQQ에만 적용한다(13F의 다른 ETF는 종류를 몰라 종목 상한).

### 4-1. 신호 추출 (R2·D27)

신호 풀(A ∪ B) 각 기관의 저장본(그룹 A·B 모두 `q30`이 있으면 `q30`, 아니면 `q8` — **선정 기준일 이전 공시만**)에서 최신 분기 vs 바로 앞 분기
(`HoldingsDiff.previousQuarterEnd`). 같은 분기 같은 티커 여러 줄(A·C주 등)은 평가액·주식 수를 더한다. 매수 이벤트 = `NEW` 또는 `ADDED`(주식 수 +30% 이상).
매도 이벤트 = `CLOSED`. 매수 후보 = 서로 다른 `minHolders`(2)곳 이상의 매수 이벤트.

**기관 가중**(합 1): `w_f = g · a(rank_A) + (1 − g) · b(rank_B)`, `a(r) = (6 − r)/15`(A 1~5위), `b(r) = (5 − r)/10`(B 1~4위), 그 그룹에 없으면 0,
`g = blend.groupWeightA`(0.5). 두 그룹에 모두 있는 기관(2026-10-23 기준 Duquesne)은 둘 다 받는다. 그룹이 모자라면(자격 기관 < topN) 있는 순위로만 정규화.

**확신도**: `c_f(t) = min(weight_f(t), blend.convictionCapPct) / blend.convictionCapPct`(10%) — 그 종목이 그 기관 13F 총액에서 차지하는 비중.
**후보 점수**: `S_t = Σ_{f ∈ buyers(t)} w_f · c_f(t)`. 정렬은 (점수 ↓, 매수 기관 수 ↓, 티커 ↑). 매도 신호 = 보유 종목을 풀 기관 중 `minHolders`곳 이상 `CLOSED`.

### 4-2. 배분 (R3·R4, D19·D27)

상위 `rulesMaxNames`(10) 후보(유니버스 안·재진입 금지 밖)의 목표 비중 = `maxWeightStockPct`(10%; ETF면 `maxWeightEtfPct`) × `S_t / S_max`.
1% 미만은 "확신도 낮음"으로 뺀다. 집중형 기관의 고확신 종목은 상한까지, 분산형 기관의 작은 신규 편입은 작게 — 두 스타일을 섞는 규칙이다(`blend.allocation`
`CONVICTION`, 대안 `EQUAL`은 예전 동일 비중). **예산 없음**: 이미 보유한 후보는 목표 비중까지만 추가. 남는 현금은 SHY(`parkCashInShy`).
**매일 남는 현금**(R3·R5 정리): 재선정일이 아닌 날도 남는 현금(현금 + 대기·이번 매도 대금 − 대기 매수 금액)이 `rules.parkCashThresholdPct`(2%)를 넘으면 SHY 한 건(− 0.5%p, 근거 `park_cash` — 현금 관리)을 내고, 매수는 `minOrderNotional`(500달러) 미만이면 내지 않는다.

### 4-3. 일일 결정 흐름 (R5·R6)

```
decideRules(asOf):
  gate = GateIndicator.evaluate(inputs(asOf), prevLog)             → paper_gate_log  (지표만)
  if isReselectionDay(asOf):                                        # 공시 기한 다음 거래일
      tr  = PaperSelectionService.compute(asOf)                     → paper_track_record
      sel = select(tr)                                              → paper_selection_log
  sel = latest selection                                            # 신호 풀 A ∪ B와 기관 가중
  sig = Signal13F.collect(sel.pool, asOf)                           # 선정 기준일 이전 공시만
  orders = [SELL 전량 for held if sig.sellSignal(held)]
  if isReselectionDay(asOf): orders += Signal13F.allocate(sig.candidates, nav, positions, caps)   # 놓쳤으면 따라잡는 날(§6-2)
  record paper_decisions(book=rules, kind=SCHEDULED|REBALANCE, selection_as_of,
                         response={gate(참고), selection, signals, orders, skipped})
```

### 4-4. 설정 키 (`paper_settings.body`)

| 키 | 기본 | 영향 |
|---|---|---|
| `model` / `effort` | `claude-sonnet-5-5` / `high` | Claude 책·제안 (D5) |
| `decisionTimeKst` / `decisionDeadlineKst` | `07:30` / `12:00` | C1 |
| `slippageBp` | 5 | 전 책 (D9) |
| `maxWeightStockPct` / `maxWeightEtfPct` / `maxPositions` | 10 / 20 / 20 | 검증기·규칙 책 (D8) |
| `trailingStopPct` / `lockoutTradingDays` | 8 / 20 | 스탑 (D10) |
| `maxDiscretionaryOrdersPerMonth` / `minHoldTradingDaysForSell` / `minOrderNotional` | 20 / 5 / 500 | 검증기 (D11) |
| `trackRecord.windowYears` | 7 | §4-0 (D6) |
| `trackRecord.topN` | 5 | |
| `trackRecord.metric` | `CAGR` (`EXCESS_VS_SPY`·`SHARPE`) | D20 |
| `trackRecord.minPeriods` / `minCoverage` / `avgCoverage` | 24 / 0.5 / 0.7 | D22 |
| `trackRecord.candidateCiks` | §4-0 ① 표(단계 0 확인 뒤) | D21 — 바꾸면 경고 |
| `minHolders` / `rulesMaxNames` / `parkCashInShy` | 2 / 10 / true | 규칙 책 |
| `rules.parkCashThresholdPct` | 2 (0~20) | 재선정일이 아닌 날 남는 현금을 SHY에 두는 기준(순자산 %) — 이보다 크면 SHY 한 건(§4-2). `minOrderNotional`(500)은 규칙 책 매수에도 적용 |
| `blend.groupB.topN` / `blend.groupB.windowYears` / `blend.groupB.minPeriods` | 4 / 2 / 6 | 그룹 B(D27) |
| `blend.groupWeightA` / `blend.convictionCapPct` / `blend.allocation` | 0.5 / 10 / `CONVICTION`(`EQUAL`) | 섞기 가중·확신도·배분(D27) |
| `blend.minTargetPct` | 1.0 (0~10) | 확신도 비례 배분의 하한 — 목표 비중이 이보다 작으면 '확신도 낮음'으로 뺌(D27) |
| `universeExtraTickers` / `cashLikeTickers` | XL*·SHY·UUP·GLD·DBC·TLT·IEF / SHY | D8 |
| `gate.*` (us10yStage1 4.80, us10yFull 4.75, brentMax 95, wtiMax 90, usdjpyMin 155, confirmDays 3) | | **참고 지표만** (D7) |
| `notesDefaultExpiryTradingDays` / `notesMaxCharsInPacket` | 3 / 6000 | 메모 |
| `apiDailyCallCap` | 6 | D18 |
| `frameworkTokenBudget` | 8000 | F4 |

규칙 책 영향 키(`trackRecord.*`·`blend.*`·`minHolders`·`rulesMaxNames`·`trailingStopPct`·`maxWeight*`)는 응답 `warning`으로 화면이 확인 문구를 보인다. `exposure.*` 키는 없다(D7·D19).

---

## 5. Claude 재량 책

### 5-1. 호출 방식 (`AiService.structured`, C2·C3)

`MessageCreateParams.builder().model(model).maxTokens(16_000).systemOfTextBlockParams([TextBlockParam(system, cacheControl=ephemeral)]).addUserMessage(packet).outputConfig(OutputConfig.builder().effort(effort).format(JsonOutputFormat(schema)))`. 스키마는 `src/main/resources/paper/*.schema.json`을 Jackson 3로 읽어 키마다 `JsonOutputFormat.Schema.builder().putAdditionalProperty(키, JsonValue.from(값))`으로 넣는다(단계 0 확인). `thinking`·`temperature`·`fallbacks` 없음(D5 자동 전환 없음). 오류 분류는 기존 표. `REFUSAL`은 `stopDetails`. `Usage.cacheReadInputTokens`·`cacheCreationInputTokens` 기록.

**스키마 제약은 둘로 나눈다(단계 0).** API는 숫자 `minimum`·`maximum`을 400으로 거절하고, 문자열 `minLength`·`maxLength`·배열 크기 제한도 지원하지 않는다(모든 객체에 `additionalProperties: false` 필요). 그래서 스키마 파일은 전체 제약을 그대로 담고, 보낼 때는 `StructuredSchema.forApi`가 지원하지 않는 키를 걷어 낸다. 응답은 같은 스키마 파일 전체로 `StructuredSchema.validate`가 검사하고(형·enum·const·required·추가 키·범위·길이·개수·pattern), 어기면 C3 "응답 형식 오류"다.

### 5-2. 입력 패킷 (C5·C11)

| # | 섹션 | 내용 | 상한 |
|---|---|---|---|
| 0 | 지시 | "아래 자료만으로 판단. 메모의 숫자는 참고, 패킷의 숫자가 우선. **현금 비중은 네가 정한다**(`targetEquityExposurePct`). 출력은 스키마대로. 주문이 없어도 `noTradeReason`" | 고정 |
| 1 | 기준 | `asOfTradingDate`, 다음 세션, 휴장일, `pricesFinal`, 결정 종류, 프레임·설정 버전, **선정 기준일** | |
| 2 | 매크로 카드 | 18개 카드(key·price·prevValue·pct·delayMinutes·lastTs·isStale·source). 초기 결정은 `asOfMismatch` | 18행 |
| 3 | FRED | 9개 시리즈 마지막 값·날짜·20거래일 변화·1년 백분위(`asKnownOn`) | 9행 |
| 4 | 국면 | `RegimeLogStore.recent(1)` | 1행 |
| 5 | **OS 게이트 지표(참고)** | 단계·조건별 값·연속 일수 + "과거 OS가 쓴 트리거 수준이며 **구속하지 않음**. 현금 비중은 네 판단" | |
| 6 | **기관 트랙레코드** | 그룹 A 상위 10(7년)·그룹 B 12곳(2년): 이름·순위·CAGR·초과·MDD·샤프·커버리지·구간 수·자격, 신호 풀 표시, 선정 기준일, "공시일 기준 복제 수익률" | 22행 |
| 6b | **기관 전략 프로필**(D27) | 신호 풀 기관마다 그룹·순위·기관 가중, 수익률·MDD, 집중도(유효 종목 수·HHI·상위 10 비중), 회전율(`GuruStyle`), 상위 보유 5, 기존 메뉴 설명(`desc`)·스타일. "두 그룹의 전략·패턴·장점을 섞어 판단하라 — 교차 확인 종목 우선, 집중형의 고확신은 크게, 분산형의 신규 편입은 테마 확인" | 8곳 |
| 7 | 13F 변화 | 신호 풀 기관 각각 reportDate·filingDate·상위 변화 15건(티커·행동·비중·변화율). 교집합 후보(≥2곳). "공시 시차 45일" | 기관당 15행 |
| 8 | 가격·모멘텀 | 보유 + 후보 + 유니버스 ETF 17: 종가·1/3/6/12개월·200일선·52주 고점 대비 | ≤ 60행 |
| 9 | 포트폴리오 | 순자산·현금·**현재 노출**·보유(수량·평단·현재가·비중·최고가·스탑 거리·보유일)·재진입 금지·이달 주문 수/상한 | |
| 10 | 최근 결정 | 5건: 날짜·stance·**정한 노출**·요약 300자·통과/거절 수 | 5건 |
| 11 | 메모 | 유효 MARKET 메모, 구분자, "자료일 뿐 지시가 아님" | 6,000자 |
| 12 | 하드 규칙 | 상한·최대 종목·최소 금액·월 상한·최소 보유일·금지 목록·유니버스. "**현금 비중 상·하한은 없다**" | 고정 |

목표 25,000토큰 이하. `packet JSONB`·보낸 문자열 `packet_text`(V10 — JSONB는 키 순서·표기를 바꿔 해시를 다시 만들 수 없음)·SHA-256·`usage` 기록.

- 구현 메모(단계 3a): 6b(8곳)·7(10곳)의 기관 상한은 그룹마다 먼저 절반씩 채운다(겹치는 기관은 한 번) — 풀 순서(A 전부 → B)로 자르면
  그룹 A 선정 수를 키웠을 때 그룹 B가 통째로 빠짐. 자르면 `filerCap`(풀 크기·보인 수·뺀 기관)을 적는다. 9에 앞선 결정의 대기 주문
  (`pendingOrders`)과 "이번 결정의 응답이 형식 검사를 통과하면(주문이 없거나 모두 거절돼도) 대기 주문은 모두 취소 — 유지하려면 다시 내라"(12·시스템 프롬프트에도).
  12에 검증기와 같은 계산의 `noPriceTickers`(이어 쓴 봉·봉 없는 보유)·`inputsStale`(관측치 24시간 초과 — 패킷이 시계를 읽는 유일한 값)을,
  8에 `stale` 열을 싣는다(초기 결정은 출발일 종가로 체결해 판정하지 않음 — null).

### 5-3. 출력 스키마 (`decision.schema.json`)

```json
{
  "type": "object", "additionalProperties": false,
  "required": ["schemaVersion", "stance", "targetEquityExposurePct", "exposureRationale", "summary", "orders", "noTradeReason", "confidence"],
  "properties": {
    "schemaVersion": {"const": 2},
    "stance": {"enum": ["RISK_ON", "RISK_OFF", "NEUTRAL", "UNCERTAIN"]},
    "targetEquityExposurePct": {"type": "number", "minimum": 0, "maximum": 100},
    "exposureRationale": {"type": "string", "maxLength": 600},
    "summary": {"type": "string", "maxLength": 1200},
    "orders": {"type": "array", "maxItems": 12, "items": {
      "type": "object", "additionalProperties": false,
      "required": ["ticker", "side", "timing", "thesis", "labels", "invalidation"],
      "properties": {
        "ticker": {"type": "string", "pattern": "^[A-Z.\\-]{1,8}$"},
        "side": {"enum": ["BUY", "SELL"]},
        "targetWeightPct": {"type": ["number", "null"], "minimum": 0, "maximum": 100},
        "sellFraction": {"type": ["number", "null"], "minimum": 0, "maximum": 1},
        "timing": {"enum": ["NEXT_OBSERVATION", "NEXT_CLOSE"]},
        "thesis": {"type": "string", "maxLength": 600},
        "labels": {"type": "array", "items": {"enum": ["사실", "해석", "가설", "확인 필요", "결정"]}},
        "invalidation": {"type": "string", "maxLength": 300}
      }}},
    "noTradeReason": {"type": ["string", "null"], "maxLength": 600},
    "watchlist": {"type": "array", "maxItems": 10, "items": {"type": "object", "additionalProperties": false,
      "required": ["ticker", "condition"], "properties": {"ticker": {"type": "string"}, "condition": {"type": "string", "maxLength": 300}}}},
    "confidence": {"type": "number", "minimum": 0, "maximum": 1}
  }
}
```
`targetEquityExposurePct`는 **기록·표시용**이다(개요 탭 "Claude 노출 추이"). 주문과 어긋나면(예: 노출 30%라 했는데 매수 합이 90%) 검증기는 주문을 기준으로 집행하고 결정 기록에 `exposureMismatch=true`를 남긴다 — 숫자를 임의로 맞추지 않는다.

### 5-4. 검증기 (`OrderValidator`, C4·C9·C11)

| 규칙 | 거절 사유 |
|---|---|
| 유니버스 밖 | `OUT_OF_UNIVERSE` |
| 중복 티커 | `DUPLICATE_TICKER` |
| BUY에 `targetWeightPct` 없음 / SELL인데 보유 없음 | `MALFORMED` / `NOT_HELD` |
| 재진입 금지 중 | `LOCKED_OUT` |
| 목표 비중 > 종목 10 / ETF 20 | `WEIGHT_CAP` |
| 매수 뒤 종목 수 > 20 | `MAX_POSITIONS` |
| **매수 목표 합 + 매도 뒤 남는 보유 비중 > 100%** | `OVER_ALLOCATED` — 큰 비중부터 받고 넘치는 것 거절 |
| 매수 금액 < $500 | `MIN_NOTIONAL` |
| 이달 재량 주문 누계 ≥ 20 | `MONTHLY_CAP` |
| 재량 SELL인데 보유 5거래일 미만 | `MIN_HOLD` |
| 종목 저장본 24시간 초과 또는 `pricesFinal=false`&&`blockWhenNotFinal` | `STALE_INPUT` (그날 전부) |
| 가격 없음·stale 티커 | `NO_PRICE` |

**없는 것**: 노출 예산·현금 하한·매크로 조건(D7). 결정 `response.validation`에 전부 기록.

- 구현 메모(단계 3a): `OVER_ALLOCATED`의 "매도 뒤 남는 보유"는 매수가 체결될 때 이미 체결된 매도만 센다 — `NEXT_CLOSE` 매도는
  `NEXT_OBSERVATION` 매수보다 늦게 체결돼 그 매수의 현금을 만들지 못한다(그대로 받으면 매수가 현금 부족으로 줄어든 채 체결로 끝남).
  `MONTHLY_CAP`의 "이달 누계"는 이달 **체결된** 정기·수동 결정 주문만 센다 — 초기 배분(`BACKFILL`, 출발일 전 거래일 기준이라 출발일이
  그달 첫 거래일인지에 따라 결과가 달라짐)·대기(새 결정이 같은 트랜잭션에서 취소)·취소·만료는 세지 않는다.
  `MAX_POSITIONS`도 같은 두 시점으로 센다 — `NEXT_CLOSE` 전량 매도가 비울 자리를 `NEXT_OBSERVATION` 신규 매수가 쓰지 못한다.
  표에 없는 사유 `BELOW_ONE_SHARE`: `sellFraction`이 1 미만인데 보유 수량 × 비율이 1주 미만이면 거절한다(장부는 '1주 미만이면 전량'으로
  집행해 트림이 청산이 됨, §3-1 — 전량이면 1). 초기 결정의 유니버스는 패킷이 보인 `12_hardRules.universe`이고, 제안 종목의 출발일
  종가는 그 안에서만 얼린다(얼린 뒤 세면 패킷 밖 종목이 통과).

### 5-5. 결정 흐름 — 변경 없음(`target_exposure_pct` 저장 추가)

- 구현 메모(단계 3a): Claude 호출은 장부 잠금 밖이라 그사이 책이 바뀔 수 있다. 일시정지됐으면 결정은 응답·검증과 함께 `SKIPPED`,
  통과 주문은 `CANCELLED`("결정 중 일시정지 — 집행 안 함")로 남기고 앞선 대기 주문을 대신하지 않는다. 초기화(뒤 재시작)됐으면 기록 없이
  400. 응답을 받은 뒤(과금됨) 검증·저장이 예외로 끝나면 원문·사용량·오류를 `API_ERROR`·`retry_count = 1`(다시 부르지 않음)로 남긴다.

### 5-6. 10-23 초기 결정 (C6)

가격·FRED·환율·13F·**트랙레코드**를 `≤ 2026-10-23`으로. 매크로 카드는 `asOfMismatch`. 게이트 지표는 10-23에 알려진 FRED DGS10(10-22 관측 4.96%, `asKnownOn`)으로 참고 계산. 통과 주문은 `BACKFILL` 체결.

### 5-7. 프레임 저장 구조와 렌더링 (F1~F5)

구조는 이전 설계와 같다(`principles[]`, `engines[8].rules[]`). 버전 1에서:
- **빼는 것**: "현재 활성 컨텍스트"(실제 계좌 상태, 2026-09-25 수치), 입력 유형별 처리 절차 A~D(스키마가 대신).
- **남기되 지침으로 바꾸는 것**(D7): 엔진 1·8의 매크로 판단을 "할인율(10Y·TIPS)·비용 물가(유가·디젤)·외환(USD/JPY)의 세 축으로 현금 비중을 정한다. 과거 OS가 쓴 트리거 예시: 10Y 4.75/4.80, 브렌트 95, WTI 90, USD/JPY 155. **예시이며 구속하지 않는다**. 매일 패킷의 실물 데이터로 다시 판단한다"로.
- −8% 트레일링 스탑·비중 상한 등 **코드가 집행하는 수치**는 렌더링 끝의 `## 하드 규칙(코드가 집행)`에 설정에서 읽어 넣는다.
- 제안·승인 흐름(`proposal.schema.json`, `operations` 적용)은 변경 없음.
- 구현 메모(단계 3b): 사용자 원문을 받기 전 판은 **v0**(임시, status INTERIM — 명세·설계의 지침으로 만든 판)이고, 사용자 OS 원문을 옮긴 판이 **v1**
  (status USER, 수동 편집 F3)이다 — "버전 1 = 사용자 OS"(F1)를 그대로 지키려고. 원문 전체는 INSIGHT 메모에 두고 규칙의 `sourceNoteId`가 가리킨다(F4).
  결정의 시스템 프롬프트는 그 판의 body를 **결정 시점의 설정으로 다시 렌더링**한다(하드 규칙 수치가 설정과 어긋나지 않게 — 결정 기록의 framework_version·
  settings_version으로 재현). 렌더링 = 머리 + 원칙(`P1.` id 표기 — 지워도 id와 번호가 어긋나지 않게) + 엔진 + 지침 + 하드 규칙(설정값) + 프레임과 무관한
  고정 문단(근거·주문 필드 뜻·대기 주문 취소·출력 형식). 승인한 제안의 판은 기준 판의 status를 잇는다. 성과 차트에 판 경계(F5, `frameworkBoundaries`).
  시황 메모 기준일 기본값은 마지막으로 끝난 미국 세션(결정 전 아침에 쓴 메모가 그날 결정에), 패킷 11은 구분자로 감싼 본문 한 벌(목록에는 본문 없음).

### 5-8. 메모 (F6) · 5-9. 비용 (C8)

- 구현 메모(단계 3a→3b): 개요에 오늘(KST) 호출 수/상한·토큰과 이달 누적을 보인다(`/api/paper/status`의 `apiUsage`). 3b부터 **추정 비용**도 보인다 —
  단가는 설정 키 `pricing.sonnet.*`·`pricing.haiku.*`(USD/MTok, 공식 가격표 2026-10-25 확인값: Sonnet 5.5 입력 2·출력 10·캐시 읽기 0.10·5분 캐시 쓰기 2.50,
  Haiku 5.5는 프롬프트 100K 이하 0.10·0.50·0.01·0.125)라 가격이 바뀌면 설정만 고친다. 호출할 때 그 단가로 계산해 `paper_api_usage.est_cost_usd`에
  더하고(V11), 추정한 호출 수(`est_cost_calls`)를 함께 보여 단가 도입 전·토큰 모름 호출이 빠진 것을 드러낸다(`isEstimated`). 결정 호출과 프레임 제안 호출이
  같은 상한·사용량을 쓴다(`PaperApiCalls`, F7). 버튼의 확인 문구는 아래 표의 1회 어림(약 $0.1)을 쓴다.

| 모델 | 1회(입력 25K·출력 4K 추정) | 월(결정 1회/일 + 제안 4회) |
|---|---|---|
| Sonnet 5.5 | 약 $0.09 | 약 $3 |
| Haiku 5.5 | 약 $0.005 | 약 $0.2 |

---

## 6. 정기 작업

### 6-1. 시간 계산 — 변경 없음

`lastCompletedSession(nowEt)`, `closeInstant(tradingDate)=16:00 ET`, 확정 종가 = `dates[-1]==tradingDate && collected_at ≥ close+15m`.

### 6-2. `PaperDecisionJob` — `fixedDelay 10분`

```
run():
  if !store.ready() → SCHEMA_NOT_READY
  asOf = lastCompletedSession(nowEt)
  if nowKst < decisionTimeKst → TOO_EARLY
  if decided(rules, asOf) && decided(claude, asOf) → ALREADY_DECIDED
  obs = marketData.latestObservation(); final = isFinalClose(obs, asOf)
  if !final && nowKst < deadlineKst → PRICES_NOT_FINAL
  ledger.processObservation(obs); ledger.settleNav(asOf, final)
  gate = GateIndicator… → paper_gate_log                          (지표)
  r = reselectionDay(latestQuarterEndReselectedBy(asOf))           (이 분기 재선정일)
  buyMissing = rules 시작일 < r && r 선정(selection_as_of = r)으로 낸 REBALANCE·INITIAL 없음   (r 당일도 참)
  if asOf == r || buyMissing: selection.select(r)                  (재선정 — asOf가 아니라 r 기준)
  rules.decide(asOf, buyMissing ? REBALANCE : SCHEDULED)
  claude.decide(asOf, SCHEDULED)
```

따라잡기: 재선정일 r의 결정 창 동안 작업이 끝나지 못했거나(맥 꺼짐·오류) r에 선정을 건너뛰었으면(수집 중·후보 목록 없음 — r의
REBALANCE는 지난 선정이라 매수 없이 끝나 세지 않음), 다음 결정 날마다 `select(r)`를 다시 불러 r 선정이 생긴 날 한 번 REBALANCE로
그 분기 매수를 낸다. 선정은 늘 r 기준이라 언제 따라잡아도 같은 선정이다. 다음 재선정일이 지나면 그 분기 따라잡기는 끝난다.

- 구현 메모(단계 3a): 정기 실행은 작업 잠금을 기다리지 않는다 — 수동 실행이 쥐고 있으면 `BUSY`로 그 주기를 건너뛴다. 스케줄러 스레드는
  4개(`spring.task.scheduling.pool.size`, Spring Boot 기본 1개면 Claude 호출 몇 분 동안 리스크 점검·알림이 멈춤). 그날 오류 3번이면 포기하는
  횟수는 책마다 세고(관측치·게이트 단계 오류는 두 책 모두에), 수동 실행은 그 책의 횟수만 지운다.
- 구현 메모: 정기 결정은 두 책 모두 출발일 **뒤** 거래일만 낸다 — 출발일은 초기 결정(INITIAL)이 그날 결정이다(같은 거래일 두 번이면 C1에 어긋남).

### 6-3. `PaperRiskJob` — `fixedDelay 5분` — 변경 없음

### 6-4. 트랙레코드 수집 진행과의 관계

재선정일에 `complete=false`인 후보가 있으면 그 후보는 "수집 중"으로 제외되고 결정 기록에 적힌다. 수집이 끝난 뒤 사용자가 `POST /run/rerank`로 같은 `as_of_date`를 다시 계산해 볼 수 있지만 **선정은 바꾸지 않는다**(기록의 일관성). 10-23 초기 선정은 수집이 끝난 뒤 규칙 책 "시작"(`POST /run/initial-decision`, 사용자가 누름 — 명세 L2)에서 한 번 계산한다(신호 풀 A ∪ B). 수집 중인 후보가 있으면 시작하지 않는다 — 그래서 단계 1b(수집)가 단계 2(규칙 책 시작)보다 먼저다.

---

## 7. API 설계 (전부 `OWNER`, 29개)

이전 설계의 27개에 둘을 더한다. 나머지 행은 변경 없음(개요 응답에 `selection`·`claudeExposureSeries` 추가).

| 메서드 | 경로 | 요청 | 응답(요지) |
|---|---|---|---|
| GET | `/api/paper/track-record?asOf=` | | `{asOfDate, windowYears, metric, candidates:[{cik,name,style,rank,eligible,ineligibleReason,cagrPct,cumReturnPct,excessVsSpyPct,volPct,mddPct,sharpe,periodsUsed,periodsTotal,coverageMin,coverageAvg,firstFilingDate,lastFilingDate,storedQuarters,complete}], selected:[cik…], selectionHistory:[{asOfDate,selectedCiks}], collection:{filers,completeCount,storedFilings,targetFilings,lastRunAt}}` | `groupB:{windowYears, candidates:[…같은 모양 + source(q30·q8)], selected:[cik…]}`, `pool:[{cik, name, groups, rankA, rankB, weight}]`(D27)
| POST | `/api/paper/run/rerank` | `{asOf?}` | `{asOfDate, computed, selectedUnchanged:true}` |

**함께 고칠 것**: `AccessPolicy` 29개 · `RouteInventoryTest.EXPECTED` · `docs/API.md`(DELETE는 세 칸 형식) · `scripts/qa/routes.json` · `endpoints.ts`·`endpoints.test.ts`(CASES, 함수 수, `documentedForm` 치환 `{code}`·`{id}`·`{version}`).

---

## 8. 화면 설계 (`/paper`)

이전 설계에 **기관 선정 탭**을 더한다.

| 파일 | 역할 |
|---|---|
| `paper/SelectionSection.tsx` | 후보 기관 표(순위·CAGR·초과·변동성·MDD·샤프·구간·커버리지·자격·선정 배지·수집 진행 `n/30`), 선정 이력, "지금 재계산" 버튼(`useAsyncAction`), 한계 문구(복제 수익률·생존 편향·커버리지) |
| `paper/OverviewSection.tsx` | 네 책 곡선 + **Claude 노출 비율 추이**(결정 기록의 `target_exposure_pct`와 실제 `exposure_pct`) + OS 게이트 지표 카드("참고") |
| 나머지 | 변경 없음 |

---

## 9. 단계 계획

단계마다 `make test`(JDK 21 판 포함) → 브랜치 커밋 → `make up`. 규모 S < M < L.

| 단계 | 내용 | 규모 | 끝났다는 기준 |
|---|---|---|---|
| **0. 선행 확인** | 명세 §7 여덟 항목: 장중 저장본, **후보 CIK 확인**, **EDGAR 목록 깊이·수집 시간 측정**, **커버리지 집계**, **10년 가격 저장본 크기**, Sonnet 호출, SDK 스키마 주입, 10-23 값 | S~M | 결과를 §13에 기록. 커버리지 미달 기관 목록과 매핑 추가 후보 200개 표 확보 |
| **1. 장부 코어** | 수집기 `paper_quotes`·`paper_price_history`(D23, 유니버스는 지금 `EQUITY_MAP` + ETF로 시작하고 1b에서 CUSIP 매핑 티커를 더함), V8, `PaperStore`, `UsMarketCalendar`(공시 기한 포함)·`PaperLedger`·`TrailingStop`·`SplitDetector`·`PaperStats`·`GateIndicator`, `PaperMarketData`, `PaperLedgerService`, `PaperRiskJob`, `PaperAdminService.init`(네 책 + 벤치마크 10-23 사후 체결, 규칙·Claude 책은 현금만), `PaperQueryService`, API(overview·positions·orders·fills·nav·status·init·books status/reset·run/risk), 화면 개요·보유 탭 | M | 벤치마크 순자산이 SPY·SHY와 맞음. 체결 룩어헤드 테스트 통과. 스탑·분할 테스트 통과 |
| **1b. 13F 트랙레코드** | 수집기: `TRACK_RECORD_FILERS`(선행 CIK)·`TRACK_RECORD_QUARTERS`·`task_sec_13f_track_record`·`collect_13f(reuse_stored, list_count)`·`TRACK_RECORD_CUSIPS`(D25)·유니버스 확장·`Datasets` 상수·DATA_SOURCES. 백엔드: `TrackRecord13F`·`PaperSelectionService`·`paper_track_record`·`paper_selection_log`·API 2개·기관 선정 탭. 수집을 돌려 후보 전원 `complete` | L | 수집 완료. 손계산 테스트(구간 수익률·CAGR·룩어헤드·커버리지·자격). 2026-10-23 기준 순위표가 화면에 보이고 상위 5가 기록됨. 단계 0 원형 계산(단계 0 표 D)과 대조 |
| **2. 규칙 책** | V9(`paper_track_record.group_code`), 그룹 B 선정(D27), `Signal13F`(기관 가중 × 확신도, 확신도 비례 배분), `RuleBookService`, `PaperDecisionJob`(규칙 책까지, 재선정일 분기·놓친 재선정일 따라잡기), 규칙 책 "시작"(`POST /run/initial-decision`)에 10-23 사후 체결(신호 풀 A ∪ B, 최대 9곳), 결정 로그 탭(규칙 책) | M | 10-23 초기 포트폴리오가 10-23 이전 공시·선정만으로 계산되고 종목마다 기관·순위·행동 기록. 재선정일 외 매수 없음 테스트 |
| **3a. Claude 책 — 결정** | `AiService.structured`, 스키마 2개, `PacketBuilder`(게이트 참고·트랙레코드 섹션 포함), `OrderValidator`(예산 규칙 없음), `ClaudeBookService`, `paper_api_usage`, 설정 테이블·API·탭, 결정 로그 탭(패킷·응답·검증·노출), `run/decision`·`run/initial-decision` | L | 가짜 서버 4종. 검증기 규칙별 테스트 + "노출 0%·100% 모두 통과" 테스트. 초기 결정 패킷에 10-23 이후 값 없음. 실제 키로 10-23 초기 결정 미리보기 1회(장부를 바꾸지 않음 — 책 시작은 사용자가 누름, L2) |
| **3b. 프레임·메모** | `paper_framework_*`·`paper_notes`, `FrameworkService`(v1 시드 — 매크로 축은 지침 문장으로)·`NoteService`, API, 탭 | M | 승인 전후 테스트. 만료 메모 제외. v1 규칙 목록 사람이 검토 |
| **4. 마무리** | ADR 0018, ARCHITECTURE·API.md·DATA_SOURCES·README·`.env.example`·compose(`PAPER_ENABLED`), 이 문서 구현 결과 표 | S | `make test` 통과, `/paper` 여섯 탭 동작, `git branch -r --contains HEAD` 빈 결과 |

---

## 10. 테스트 계획

**`analytics` 순수 함수**
- `UsMarketCalendarTest`: 2024~2027 휴장일, 직전·다음 거래일, DST 전환, **13F 공시 기한**(2026-11-16, 2027-02-16, 2027-05-17, 2027-08-16).
- `PaperLedgerTest`·`TrailingStopTest`·`SplitDetectorTest`·`PaperStatsTest`: 이전 설계와 같음.
- `GateIndicatorTest`: 단계 전이·연속 일수·입력 부재→UNKNOWN(L0가 아니라 UNKNOWN으로 표시).
- `TrackRecord13FTest`: 두 공시·세 종목의 손계산 구간 수익률; 공시 **당일**이 아니라 다음 영업일 가격을 쓰는지(룩어헤드); 커버리지 재정규화; 7년 창 자르기; 자격 미달 사유 3종; 상장폐지 티커 처리; SPY 초과.
- `Signal13FTest`: NEW·+30%·+29%·CLOSED, minHolders, 기관 가중(두 그룹 겹침 포함)·확신도 점수 손계산, 확신도 비례 배분·1% 미만 제외·상한·SHY 잔여.
- `PaperSelectionServiceTest`(그룹 B): q30 우선·q8 대체, 2년 창, 커버리지 미달(인덱스형) 제외, R5, 신호 풀 합집합·가중.
- `OrderValidatorTest`: §5-4 규칙마다 1건 + `OVER_ALLOCATED` 큰 비중 우선 + 노출 0%·100% 통과.

**서비스(모의 저장소)**
- `PaperSelectionServiceTest`: `complete=false` 제외, 재선정일 판정, 수동 재계산이 선정을 바꾸지 않음, 10-23 기준 자르기.
- `PaperMarketDataTest`·`PacketBuilderTest`(게이트 참고 문구·트랙레코드 섹션·10-23 자르기)·`ClaudeBookServiceTest`·`RuleBookServiceTest`(재선정일 분기)·`FrameworkServiceTest`·`PaperDecisionJobTest`·`PaperRiskJobTest`.

**AI**: `AiServiceStructuredTest`(가짜 서버) — 이전과 같음.

**수집기**: `test_filings_track_record.py` — 예산 안에서 멈춤, `reuse_stored`로 재다운로드 없음(고정본 2기관), `list_count=100` 요청 파라미터, `complete` 판정; `test_equities.py` 매핑 추가 섹터 대조; `test_catalog`/`DatasetsParityTest`에 `TRACK_RECORD_QUARTERS`.

**통합(`macrodash_test`)**: `PaperStoreDbTest`(결정 유일 제약·체결 원자성·순자산 upsert·트랙레코드 PK 덮어쓰기), `ApiIntegrationTest` 403/200.

**규약**: `ArchitectureRulesTest`·`AccessPolicyTest`·`RouteInventoryTest`·`DatasetsParityTest`, 프런트 `endpoints.test.ts`·`access.test.ts`·`paper.test.ts`·`paperStateHandling.test.tsx`·`Sidebar.test.tsx`.

**외부 호출 없음**: 테스트는 Anthropic·Yahoo·EDGAR를 부르지 않는다. 실제 호출은 단계 0·1b·3a의 수동 확인.

---

## 11. 위험과 대응

| 위험 | 대응 |
|---|---|
| **후보 기관 보유의 티커 커버리지가 낮아 복제 수익률이 그 기관의 수익률이 아니게 됨** | 자격 기준(최소 50%·평균 70%)으로 순위에서 제외. 단계 0에서 측정해 매핑표를 늘린다(정확 일치만). 화면은 커버리지를 결과보다 먼저 보인다 |
| **후보 목록의 생존 편향**(오늘 살아 있는 운용사만) | 없앨 수 없다. 화면 한계 문구 6. 성과가 나쁜 것으로 알려진 운용사도 후보에 넣어 "승자만 고르기"를 줄인다 |
| EDGAR 30분기 수집이 느림(750건) | 실행당 예산·증분 저장. `make collect-all` 반복으로 하루 안에. 진행률 표시. 끝나기 전 재선정일이 오면 그 후보는 제외하고 기록 |
| `browse-edgar` 목록 100행으로도 30분기가 안 잡힘 | 단계 0에서 확인. 모자라면 `start=` 페이지(같은 방법 안) |
| 10년 가격 저장본이 커져 화면이 느려짐 | 단계 0 측정. 16MB 넘으면 `timeseries` 테이블로 옮기는 설계 변경(ARCHITECTURE 3-1) |
| 트랙레코드 재계산 비용(27곳 × 30구간 × 수백 종목) | 재선정일(연 4회)과 수동 때만. 결과는 `paper_track_record`에 캐시 |
| 주말 재저장 룩어헤드 | 체결 조건 2. 테스트 고정 |
| Claude가 노출을 과격하게 바꿈(0%↔100% 반복) | 하드 규칙으로 막지 않는다(D7). 월 주문 상한·최소 보유일·최소 금액이 간접 제동. 노출 추이를 개요에 그려 사용자가 본다 |
| Claude 비용 폭주 | 일일 상한 6회(DB로 셈) |
| 메모 프롬프트 주입 | 자료 구분자 + 검증기 |
| 같은 날 결정 두 번 | `UNIQUE (book_code, as_of_trading_date, kind)` |
| 스키마 없이 백엔드 선기동 | `SchemaCheck` → `available:false` |
| 분할 오탐 | ±10% 밖만, 기록·배지 |
| main 충돌 | §12-3. 기존 파일 수정은 접근 정책·경로 목록·사이드바·API.md·수집기 네 파일로 최소화 |
| 결정 시각에 확정 종가 미도착 | 12:00까지 10분 재확인, 넘으면 `pricesFinal=false` |

---

## 12. 남길 기록과 운영 절차

### 12-1. ADR 후보

| 번호 | 내용 |
|---|---|
| 0018 | 모의 매매 장부와 LLM 재량 결정의 가드레일 — 제안은 모델, 검증·집행은 코드; 현금 비중·매크로 판단은 모델의 재량이고 하드 규칙은 집중도·회전율·신선도만; 체결은 "결정 뒤 첫 관측치(봉 날짜 > 기준 거래일)"; 신호 기관은 공시일 기준 복제 트랙레코드로 분기마다 재선정; 프레임은 승인된 버전만; 규칙 책은 동결된 대조군; Claude 책은 백테스트하지 않음; 로컬 브랜치 전용 전달 |

### 12-2. 함께 고칠 문서 (브랜치 안)

| 문서 | 고칠 곳 |
|---|---|
| `docs/ARCHITECTURE.md` | §3 `paper_*` 행, §4-1 기능 목록 `paper`(누락 `alert`도), `analytics` 표 새 클래스 |
| `docs/API.md` | §1 "🧪 모의 매매(운영자)" 29행 |
| `docs/DATA_SOURCES.md` | 태스크 표에 `sec_13f_track_record`(21개), 13F 소절에 "후보 기관 30분기·예산·증분", `PRICE_HISTORY_PERIOD` 10y, 매핑표 확장 절차, "이 기능이 쓰는 저장본과 한계" |
| `README.md` | 주요 기능 한 줄 + "로컬 전용" |
| `.env.example`·`docker-compose.yml` | `PAPER_ENABLED`(기본 true), `TRACK_RECORD_FETCH_BUDGET`(기본 60) |
| `docs/adr/README.md` | 0018 행 |
| 이 문서 | §13, 구현 결과 표 |

### 12-3. 브랜치 운영 (D0) — 변경 없음

운영은 이 브랜치에서 `make up`. main 갱신은 "브랜치 커밋 → `git switch main && make update` → `git switch claude/paper-trading-2026-10 && git rebase main` → `make test` → `make up`". `git push`·main 병합·`compose.aws.yml` 변경 금지. 장부·트랙레코드 캐시는 `make backup`에 포함.

---

## 13. 단계 0 결과 (2026-10-24)

근거 표·측정 방법은 [2026-10-24-paper-trading-step0.md](2026-10-24-paper-trading-step0.md). 운영 DB에는 쓰지 않았다.

| 항목 | 결과 | 영향 |
|---|---|---|
| 미국 장중 저장본의 마지막 봉 | 토요일이라 직접 비교 불가, 지난 장중 저장본도 남아 있지 않음. 같은 수집 함수로 열린 시장(BTC-USD)을 75초 간격으로 받으면 마지막 봉이 오늘 날짜이고 값이 바뀜 | 설계 그대로(D3). 장중 봉이 없어도 체결 조건 2로 `NEXT_OBSERVATION`이 다음 확정 종가 체결이 됨. **[확인 필요]** 10-26(월) KST 22:30 뒤 `PaperRiskJob` 기록으로 확인 |
| 후보 기관 CIK (27곳) | 27곳 모두 13F-HR 이력 있음. 선행 CIK가 필요한 곳 2(Greenlight → DME Capital Management, Pershing PSCM → Pershing Square Inc.). Scion은 2025-09-30 분기가 마지막 | 후보 27곳 그대로(D21). `TRACK_RECORD_FILERS`에 선행 CIK(§4-0 ①·②) |
| EDGAR 목록 깊이·기관당 30분기 수집 시간 | `count=100` 한 페이지에서 27곳 모두 원본 30건. 기관당 10.0~17.5초, 27곳 약 6분 | 예산 기본 400건/실행(§4-0 ②). 다음 페이지(`start=`)는 안전장치로만 |
| 후보 기관 티커 커버리지 (기관별 %) | 지금 매핑표로는 D22 자격 1곳(버크셔). CUSIP 매핑 498개를 더하면 23곳 자격, 미달 4곳(Baupost·Scion·Soros·Tudor) | **D22 그대로.** 매핑은 이름이 아니라 CUSIP으로(D25, §4-0 ③). 미달 4곳은 목록에 남기고 사유를 보임 |
| 10년 가격 저장본 크기·개요 응답 시간 | 138종목 10년 JSONB 4.49MB, 응답 시간 비슷, 백엔드 메모리 +130MiB. 550여 종목이면 약 18MB·힙 초과 예상 | **D23 변경**: `equity.price_history`는 그대로(5년·138종목), 10년 이력은 `timeseries` `equity_close`, 관측치는 `paper.quotes`(§0-6·§3-1) |
| Sonnet 5.5 호출 | `claude-sonnet-5-5`·`claude-haiku-5-5` 모두 구조화 출력으로 정상(4.6초·2.4초) | 모델 ID 그대로(D5) |
| SDK 스키마 주입 | Jackson 3 `Map` → `Schema.putAdditionalProperty` + `JsonValue.from` 동작. 단 `minimum`·`maximum`은 400, 길이·개수 제약도 미지원 | 스키마 제약을 API용·검증용으로 나눔(§5-1) |
| 10-23 FRED·환율 값 | `DGS10` 10-23 값 아직 없음(10-22 = 4.96%), `T10Y3M`·`T10YIE`는 10-23까지, USD/JPY 10-23 = 156.814 | 초기 패킷은 `asKnownOn`으로 10-22 관측치(10-23에 알려진 최신)를 씀(§5-6) |
| 2026-10-23 기준 예상 상위 5(원형 계산) | Duquesne(CAGR 27.5%)·Appaloosa(21.9%)·Greenlight(19.1%)·Viking(18.0%)·Himalaya(16.8%). 같은 창 SPY 누적 180%. 매핑을 445개에서 498개로 늘리자 Coatue(6위)와 Greenlight(3위)가 자리를 바꿈 — 순위가 커버리지에 민감 | 단계 1b의 자바 계산과 대조(단계 0 표 D) |
| 단계 1b 자바 계산(운영 저장본, 2026-10-24) | 27곳 30분기 수집 완료(실행 3번, 400+365+60건). 2026-10-23 기준 23곳 자격, 원형과 같은 숫자(소수 첫째 자리)이고 5·6위만 바뀜: 5위 Coatue 16.8%(누적 196.4%), 6위 Himalaya 16.7%(195.8%) — 거래일 달력·상장폐지 처리 차이. 재계산 2.2초, 백엔드 메모리 213→226MiB | 그룹 A = Duquesne·Appaloosa·Greenlight·Viking·Coatue |
| 그룹 B 원형(D27, 2년 창) | 기존 메뉴 12곳 중 자격 통과 4곳: Duquesne 26.8%·Bridgewater 16.0%·Fisher 15.4%·Berkshire 13.8%. 노르웨이·블랙록·뱅가드·CPPIB는 17~19%지만 평균 커버리지 51~62%로 미달(덮인 대형주만 보여 부풂), Scion 27.5%는 최신 분기 공시 없음 | 신호 풀 8곳(Duquesne 겹침) |

---

## 14. 2단계 이후

1. **SEC XBRL Company Facts 수집** → FCF 전환율·듀퐁 ROE·RIM 필터(검증기·규칙 책), Point-in-Time(`filed`).
2. 수집기 OHLC·배당(`actions=True`) → `NEXT_OPEN` 체결, 현금 배당 반영.
3. 규칙 책 Walk-forward 백테스트(트랙레코드 선정 + 신호, 2019~2026) — 스탑·재진입·minHolders·topN 재검토.
4. 트랙레코드 창 7년 → 10년(저장 분기 42), 브린슨 분해(`nameMap.sector`), 섹터 로테이션 패턴.
5. 메모를 넣고 바로 받는 "OS 절차 C 9단계 시황 해석" 리포트(수동 호출).
6. 가격 없음 보유의 수동 정리, 분할 수동 교정.
7. 다섯 번째 책 `rules_gated`(OS 문자 그대로 게이트를 건 규칙 책) — 명세 §9 대안이 필요해지면.
