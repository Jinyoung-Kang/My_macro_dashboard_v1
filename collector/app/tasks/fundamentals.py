"""
app/tasks/fundamentals.py
weekly 그룹 — 모의 매매(운영자 전용 기능) Claude 책 패킷의 미국 종목 재무(SEC XBRL Company Facts, 명세 D28·설계 §4-5).

태스크는 "받아서 저장"만 합니다. 시점(filed ≤ 기준일)·TTM·지표 계산과 패킷 섹션 ``8b_fundamentals``는 백엔드가 이
저장본을 읽어 합니다(ADR 0008 — observations·snapshots는 수집기만 씀). 규칙 책·검증기는 이 자료를 읽지 않습니다(D15 동결 대조군).

[저장]
  observations ``us.fundamentals``   : entity = 티커, obs_date = 공시일(filed), payload ``{"cik": [...], "facts": [...]}``.
                                        받은 회사마다 upsert, 지우지 않음(사실은 공시로 쌓이기만 함). 실패한 회사는 이전 행 그대로.
                                        예외: 받은 회사의 지금 CIK 목록에 없는 CIK만으로 쌓인 행은 지움(티커 재배정·별칭 제거).
  스냅샷 ``us.fundamentals.status``  : 회사별 수집 상태(받은 시각·최신 공시일·최신 기간 end·분류체계·통화·사실 수·오류)와
                                        실행 집계(universe·mapped·fetchedThisRun·failedThisRun).

[예산·순서] 실행당 companyfacts 요청(= CIK) ``FUNDAMENTALS_FETCH_BUDGET``(기본 200)건. 유니버스 약 570곳을 세 번(약 36시간)에
한 바퀴 돕니다. 순서는 ① 한 번도 받지 않은 종목(CIK 목록이 바뀐 종목 포함) ② 새 공시가 나왔을 법한데 그 뒤로 아직 받지 않은
종목 ③ 가장 오래전에 받은 종목, 같은 순위는 티커 순(결정적).
"""
from __future__ import annotations

import logging
import time
from collections import Counter
from datetime import date, datetime, timedelta, timezone

from .. import catalog, equities_cusip, http, indicators, paper_universe, settings, store
from ..services import sec_fundamentals as fundamentals_service

from .base import EmptyResult, _reason_suffix, carried_suffix, carry_over_missing, previous_payload

logger = logging.getLogger(__name__)

# 새 회사를 더 시작하지 않는 경과 시간(초). weekly 상한(runner.TASK_DEADLINE_SECONDS["weekly"] = 1800초)을 넘기면 태스크가
# '시간 초과'로 끝나 상태 스냅샷을 못 남기므로, 상한 전에 멈추고 받은 만큼 기록합니다. 남은 600초는 진행 중인 요청 하나가
# 재시도(세션 5회, 백오프 1.5, Retry-After ≤ 5초)와 읽기 상한 60초를 다 쓰는 경우의 여유입니다. 정상이면 200건에 75~120초.
TIME_LIMIT_SECONDS = 1200.0

# 이만큼 연속으로 실패하면(404 제외) 나머지를 이번 실행에서 시도하지 않습니다 — SEC 쪽 장애·차단(403)일 때 회사마다 재시도를
# 다 쓰며 시간 상한까지 같은 실패를 쌓고, 13F 두 태스크와 나눠 쓰는 토큰 버킷을 붙잡지 않게.
#
# 상한은 '처음 겪는 SEC 장애'를 잡으려는 것이라 두 가지를 더합니다. 그러지 않으면 특정 회사 5곳이 계속 실패할 때 태스크가 영영
# 멈췄습니다 — 실패한 회사는 옛 fetchedAt을 이어받아(또는 fetchedAt 없는 오류 줄이 되어) 다음 실행 순서의 맨 앞에 서고, 그 5곳에서
# 상한에 걸리면 나머지는 시도되지 않고, 한 곳도 못 받았으니 EmptyResult라 상태가 그대로여서 다음 실행도 같은 5곳에서 멈춥니다.
#   ① 직전 상태가 이미 실패로 적은 회사(_failed_before)의 실패는 세지 않습니다 — 그 회사만의 문제일 가능성이 큽니다.
#   ② 아직 한 곳도 받지 못한 채 상한에 걸리면, 순서 끝(가장 최근에 받은 정상 회사) 하나로 SEC가 살아 있는지 한 번 확인합니다.
#      되면 그 실패들은 회사 문제라 이어서 받고(상태에 실패로 남아 다음부터 ①), 안 되면 SEC 장애로 보고 멈춥니다(요청 1건 더).
CONSECUTIVE_FAILURE_LIMIT = 5

# '새 공시가 나왔을 때'(순서 ②)의 기준 — 다음 기간 end는 마지막 기간 end에서 분기(91일)씩 내다보고, 그 기간의 공시 마감은
# 분기 + 40일, 회계연도 말이면(10-K) + 75일로 봅니다(명세 D28 '최신 기간 end + 40일, 10-K면 + 75일').
_QUARTER_DAYS = 91
_QUARTERLY_LAG_DAYS = 40
_ANNUAL_LAG_DAYS = 75
_LOOKAHEAD_QUARTERS = 8

# 상태 스냅샷에 이름까지 적는 '매핑 안 됨' 티커 수(DART 상태와 같은 50).
_UNMAPPED_SHOWN = 50


def task_sec_fundamentals() -> str:
    """
    모의 매매 유니버스(ETF 제외 — PAPER_ETFS·equities_cusip.TRACK_RECORD_ETPS) 미국 종목의 SEC XBRL 재무
    → observations ``us.fundamentals`` + 상태 ``us.fundamentals.status``.

    [실패 규칙 — 수집기 공통]
      - 한 회사도 받지 못하면 EmptyResult — 상태 스냅샷을 덮지 않고, 관측 행은 그대로입니다.
      - 일부만 실패하면 받은 회사만 쓰고, 실패한 회사의 상태 줄은 직전 저장본의 값을 이어받아 ``isStale``을 붙입니다
        (carry_over_missing — 이어받은 지 3일이 지나면 빠지고 오류 줄만 남음). 관측 행은 이전 것 그대로입니다.
      - 예산·시간 상한·연속 실패로 이번에 시도하지 않은 회사는 직전 상태 줄을 그대로 둡니다(실패가 아님).
      - 연속 실패 상한은 직전에 이미 실패한 회사를 세지 않고, 한 곳도 못 받은 채 걸리면 정상 회사 하나로 SEC를 확인한 뒤에만
        멈춥니다(CONSECUTIVE_FAILURE_LIMIT — 계속 실패하는 회사 몇 곳이 태스크를 영영 멈추지 않게).
      - 404(companyfacts 없음)는 답입니다 — ``error: "no companyfacts"``, 사실 0개로 적습니다. 다만 전에 받은 자료가 있는
        회사가 404를 받으면 일시 장애로 보고 이어받습니다.
    """
    # 설정이 없으면 570곳에 같은 실패를 만들지 않고 여기서 한 번 말합니다. 값(소유자 이메일)은 싣지 않습니다.
    try:
        http.sec_user_agent()
    except http.SecUserAgentMissing:
        raise EmptyResult("SEC_USER_AGENT 미설정 — .env에 본인 이메일을 넣으세요(SEC는 연락처 없는 요청을 403으로 막음)") from None
    except http.SecUserAgentInvalid:
        raise EmptyResult("SEC_USER_AGENT 값을 쓸 수 없습니다(예시 값·한글·이메일 없음) — .env를 확인하세요") from None

    # ETF는 재무제표가 없습니다 — 설정 ETF(PAPER_ETFS)와 13F CUSIP 매핑표의 ETP(IVV·IWM 등)를 함께 뺍니다. 빼지 않으면
    # 상태의 universe·unmapped에 커버될 수 없는 종목이 섞이고, CIK가 잡히는 상장 신탁(SLV 등)은 예산만 씁니다.
    etfs = set(indicators.PAPER_ETFS) | equities_cusip.TRACK_RECORD_ETPS
    universe = [ticker for ticker in paper_universe.tradable() if ticker not in etfs]
    previous = previous_payload(catalog.SNAP_US_FUNDAMENTALS_STATUS)
    previous_companies = previous.get("companies") if isinstance(previous.get("companies"), dict) else {}

    started = _clock()
    reasons: list[str] = []
    try:
        mapping = fundamentals_service.fetch_company_tickers()
    except fundamentals_service.FetchError as exc:
        # 목록을 못 받으면 직전 상태의 CIK로 갑니다 — 티커↔CIK는 거의 바뀌지 않고, 목록 한 건 때문에 회사 200곳을 멈추지 않게.
        reasons.append(f"company_tickers.json: {exc}")
        mapping = None
    if mapping is None:
        targets = {ticker: list(entry["ciks"]) for ticker, entry in previous_companies.items()
                   if ticker in universe and isinstance(entry, dict) and entry.get("ciks")}
        if not targets:
            raise EmptyResult(f"0/{len(universe)} 종목 — CIK 목록을 받지 못했고 직전 상태도 없음" + _reason_suffix(reasons))
    else:
        targets = {}
        for ticker in universe:
            ciks = fundamentals_service.resolve_ciks(ticker, mapping)
            if ciks:
                targets[ticker] = ciks
    unmapped = [ticker for ticker in universe if ticker not in targets]
    if not targets:
        raise EmptyResult(f"0/{len(universe)} 종목 — SEC CIK로 잡히는 종목이 없음" + _reason_suffix(reasons))

    budget = settings.fundamentals_fetch_budget()
    order = priority_order(targets, previous_companies, _today())

    fresh: dict[str, dict] = {}
    failed: dict[str, str] = {}
    # 같은 CIK를 쓰는 주식 종류(GOOG·GOOGL, BRK-A·BRK-B 등)는 한 번만 받아 두 티커에 씁니다 — 예산을 두 번 쓰지 않게.
    # 결과(사실 수백 개)는 그런 묶음만 들고 있습니다. 회사 200곳 것을 다 들면 실행 끝까지 수십 MB가 남습니다.
    shared = Counter(tuple(ciks) for ciks in targets.values())
    results: dict[tuple[str, ...], fundamentals_service.CompanyResult] = {}
    requests = 0
    rows_sent = 0
    not_tried: dict[str, int] = {"budget": 0, "time": 0, "failures": 0}
    consecutive_failures = 0
    probed = False
    done: set[str] = set()

    def fetch(ticker: str) -> fundamentals_service.CompanyResult | None:
        """그 티커의 결과(같은 CIK 묶음이면 받아 둔 것). 시간·예산 상한으로 시도하지 않으면 None."""
        nonlocal requests, consecutive_failures
        ciks = tuple(targets[ticker])
        result = results.get(ciks)
        if result is not None:
            return result
        if _clock() - started > TIME_LIMIT_SECONDS:
            not_tried["time"] += 1
            return None
        if requests + len(ciks) > budget:
            # 별칭 회사(CIK 둘)는 남은 예산이 모자라면 건너뛰고 CIK 하나인 회사로 예산을 채웁니다 — 일부 CIK만 받아 쓰지 않음.
            not_tried["budget"] += 1
            return None
        try:
            result = fundamentals_service.collect_company(list(ciks))
        except Exception as exc:  # noqa: BLE001 — 한 회사의 예기치 못한 오류(문서 모양 등)로 나머지를 멈추지 않습니다
            logger.exception("SEC 재무 수집 오류 (%s)", ticker)
            result = fundamentals_service.CompanyResult(
                ciks=list(ciks), error=f"예기치 못한 오류: {type(exc).__name__}", requests=len(ciks))
        requests += result.requests
        if shared[ciks] > 1:
            results[ciks] = result
        if not (result.error and not result.no_companyfacts):
            consecutive_failures = 0
        elif not _failed_before(previous_companies.get(ticker)):
            consecutive_failures += 1
        return result

    def record(ticker: str, result: fundamentals_service.CompanyResult) -> None:
        nonlocal rows_sent
        done.add(ticker)
        before = previous_companies.get(ticker)
        if result.error and not (result.no_companyfacts and not _has_data(before)):
            # 일시 실패 — 또는 전에 자료가 있던 회사의 404(같은 CIK가 갑자기 404면 SEC 쪽 일시 장애로 봄).
            failed[ticker] = result.error
            return
        now = datetime.now(timezone.utc).isoformat()
        # 이번에 받은 CIK 목록에 없는 CIK만으로 쌓인 옛 행은 지웁니다(티커가 다른 회사로 바뀜·잘못 넣은 별칭을 뺌) — 남기면
        # 백엔드가 그 행의 cik를 보지 않고 합쳐, 새 회사에 없는 기간을 다른 회사 숫자로 채웁니다. 같은 실체의 CIK가 바뀐 곳(지주회사
        # 재편)은 별칭표(FUNDAMENTALS_CIK_ALIASES)에 옛 CIK를 넣어야 이력이 남습니다.
        rows_sent += store.put_entity_observations(catalog.OBS_US_FUNDAMENTALS, ticker, result.rows.items(),
                                                   keep_ciks=list(targets[ticker]))
        fresh[ticker] = {
            "ciks": list(targets[ticker]),
            "fetchedAt": now,
            "latestFiled": result.summary.get("latestFiled"),
            "latestEnd": result.summary.get("latestEnd"),
            "latestAnnualEnd": result.summary.get("latestAnnualEnd"),
            "taxonomy": result.summary.get("taxonomy"),
            "currency": result.summary.get("currency"),
            # 404면 저장한 사실이 없다는 뜻의 0입니다(지어낸 값이 아니라 센 값).
            "facts": result.summary.get("facts", 0),
            "error": result.error,
        }

    for ticker in order:
        if ticker in done:
            continue
        ciks = tuple(targets[ticker])
        if ciks not in results and consecutive_failures >= CONSECUTIVE_FAILURE_LIMIT:
            probe = None if fresh or probed else _probe_target(order, done, previous_companies)
            probed = probed or probe is not None
            if probe is not None:
                probe_result = fetch(probe)
                done.add(probe)  # 예산·시간으로 못 했으면 fetch가 이미 그 사유로 셌습니다(두 번 세지 않게)
                if probe_result is not None:
                    record(probe, probe_result)
            if consecutive_failures >= CONSECUTIVE_FAILURE_LIMIT:
                not_tried["failures"] += 1
                continue
        result = fetch(ticker)
        if result is not None:
            record(ticker, result)

    if not fresh:
        logger.info("SEC 재무 빈 결과(저장본 유지): 실패 %d곳", len(failed))
        raise EmptyResult(
            f"0/{len(targets)} 종목 — 기존 저장본 유지" + _reason_suffix([*reasons, *list(failed.values())[:2]])
        )

    companies: dict[str, dict] = {}
    for ticker in sorted(targets):
        if ticker in fresh:
            companies[ticker] = fresh[ticker]
        elif ticker not in failed and isinstance(previous_companies.get(ticker), dict):
            companies[ticker] = previous_companies[ticker]
    carried: list[str] = []
    for ticker, error in failed.items():
        carried += carry_over_missing(companies, previous_companies, [ticker], error, _has_data)
        if ticker not in companies:
            # 이어받을 값이 없음 — 오류만 적습니다. fetchedAt이 없으니 다음 실행이 맨 앞(①)에서 다시 시도합니다.
            companies[ticker] = {"ciks": list(targets[ticker]), "fetchedAt": None, "latestFiled": None, "latestEnd": None,
                                 "latestAnnualEnd": None, "taxonomy": None, "currency": None, "facts": None,
                                 "error": error}

    store.put_snapshot(catalog.SNAP_US_FUNDAMENTALS_STATUS, {
        "source": "SEC XBRL Company Facts (data.sec.gov companyfacts)",
        "companies": companies,
        "universe": len(universe),
        "mapped": len(targets),
        "fetchedThisRun": len(fresh),
        "failedThisRun": len(failed),
        "budget": budget,
        "requestsThisRun": requests,
        "unmapped": unmapped[:_UNMAPPED_SHOWN],
    })

    no_facts = sum(1 for entry in fresh.values() if entry["error"])
    detail = f"{len(fresh)}/{len(targets)} 종목 갱신(요청 {requests}/{budget}건, 행 {rows_sent}개)"
    if no_facts:
        detail += f", companyfacts 없음 {no_facts}"
    if not_tried["budget"]:
        detail += f", 예산 소진 — {not_tried['budget']}곳은 다음 실행"
    if not_tried["time"]:
        detail += f", 시간 상한({TIME_LIMIT_SECONDS:.0f}초) — {not_tried['time']}곳은 다음 실행"
    if not_tried["failures"]:
        detail += f", 연속 실패 {CONSECUTIVE_FAILURE_LIMIT}회로 멈춤 — {not_tried['failures']}곳은 다음 실행"
    if failed:
        detail += f", 실패 {len(failed)}곳" + carried_suffix(carried) + _reason_suffix(list(failed.values())[:2])
    if reasons:
        detail += _reason_suffix(reasons)
    return detail


def _today() -> date:
    """오늘(UTC 날짜). 테스트가 날짜를 바꿔 끼우는 자리입니다 — 순서 ②는 하루 단위라 KST·동부와의 몇 시간 차이는 상관없습니다."""
    return datetime.now(timezone.utc).date()


def _clock() -> float:
    """경과 시간 시계(초). 테스트가 시간 상한을 시험할 때 바꿔 끼웁니다."""
    return time.monotonic()


def _has_data(entry) -> bool:
    """이어받을 값이 있는 상태 줄 — 한 번이라도 받아 사실이 있는 회사."""
    return isinstance(entry, dict) and bool(entry.get("fetchedAt")) and bool(entry.get("facts"))


def _failed_before(entry) -> bool:
    """직전 상태가 실패로 적은 회사 — 이전 값을 이어받은 줄(isStale)이거나, 이어받을 값이 없어 오류만 남은 줄."""
    if not isinstance(entry, dict):
        return False
    return bool(entry.get("isStale")) or (not entry.get("fetchedAt") and bool(entry.get("error")))


def _probe_target(order: list[str], done: set[str], previous: dict[str, dict]) -> str | None:
    """
    SEC가 살아 있는지 확인할 회사 — 이번에 아직 시도하지 않은 회사 가운데 순서 끝에서부터 처음 만나는 정상 회사(직전에 자료를
    받았고 실패 표시가 없음). 순서 ③은 받은 시각 순이라 끝이 가장 최근에 받은 회사, 곧 지금도 받힐 가능성이 가장 큰 회사입니다.
    """
    for ticker in reversed(order):
        entry = previous.get(ticker)
        if ticker not in done and _has_data(entry) and not _failed_before(entry):
            return ticker
    return None


# ==============================================================================
# 순서 (네트워크 없음)
# ==============================================================================
def priority_order(targets: dict[str, list[str]], previous: dict[str, dict], today: date) -> list[str]:
    """
    이번 실행에서 받을 순서(명세 D28): ① 한 번도 받지 않음(직전 상태가 없거나 fetchedAt이 없음, CIK 목록이 바뀜)
    ② 새 공시가 나왔을 법한데(``latest_due``) 그 날짜 뒤로 아직 받지 않음 ③ 나머지는 가장 오래전에 받은 순. 같은 순위는 티커 순.
    """
    def rank(ticker: str) -> tuple:
        entry = previous.get(ticker)
        if not isinstance(entry, dict) or not entry.get("fetchedAt") or list(entry.get("ciks") or []) != list(targets[ticker]):
            return 0, "", ticker
        fetched_at = str(entry["fetchedAt"])
        due = latest_due(entry.get("latestEnd"), entry.get("latestAnnualEnd"), today)
        if due is not None and fetched_at[:10] < due.isoformat():
            return 1, "", ticker
        return 2, fetched_at, ticker

    return sorted(targets, key=rank)


def latest_due(latest_end: str | None, latest_annual_end: str | None, today: date) -> date | None:
    """
    오늘까지 지난 '다음 공시 마감' 중 가장 늦은 날(없으면 None).

    다음 기간 end = 마지막 기간 end + 91일 × k(k = 1, 2, …). 그 기간이 회계연도 말(마지막 연간 사실 end에서 약 1년의 배수)이면
    마감 = + 75일(10-K), 아니면 + 40일(10-Q). 첫 마감이 지났는데 받은 뒤에도 새 기간이 없으면(늦게 내는 회사) 다음 분기 마감에
    다시 ②가 됩니다. 연간만 내는 20-F 발행사는 분기마다 한 번씩 ②가 되어 한 건을 더 받을 뿐입니다 — 어차피 ③으로 약 36시간마다 돕니다.
    """
    end = _parse_date(latest_end)
    if end is None:
        return None
    annual = _parse_date(latest_annual_end)
    due = None
    for k in range(1, _LOOKAHEAD_QUARTERS + 1):
        next_end = end + timedelta(days=_QUARTER_DAYS * k)
        lag = _ANNUAL_LAG_DAYS if annual is not None and _is_fiscal_year_end(next_end, annual) else _QUARTERLY_LAG_DAYS
        candidate = next_end + timedelta(days=lag)
        if candidate > today:
            break
        due = candidate
    return due


def _is_fiscal_year_end(day: date, annual_end: date) -> bool:
    """day가 마지막 연간 사실 end에서 약 1년의 배수(±20일 — 52/53주 회계연도와 91일 근사의 오차) 뒤인가."""
    days = (day - annual_end).days
    years = round(days / 365.25)
    return years >= 1 and abs(days - years * 365.25) <= 20


def _parse_date(text) -> date | None:
    if not isinstance(text, str):
        return None
    try:
        return date.fromisoformat(text[:10])
    except ValueError:
        return None
