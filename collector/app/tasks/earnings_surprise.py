"""
app/tasks/earnings_surprise.py
weekly 그룹 — 모의 매매(로컬 전용 기능) Claude 계좌 패킷 섹션 ``8b_fundamentals``의 EPS 서프라이즈(2단계 명세 C1·C2·§10·D36).

태스크는 "받아서 저장"만 합니다. 기준일 이전 발표분(PIT)·최근 4분기·보유·후보 고르기는 백엔드가 이 저장본을 읽어 합니다
(ADR 0008 — 계좌 보유는 백엔드 테이블이라 수집기는 모릅니다). 규칙 계좌·검증기는 읽지 않습니다(D35).

[저장]
  observations ``us.earnings_surprise``      : entity = 티커, obs_date = 실제 발표 시각(reportedDate)의 미국 동부 날짜(PIT 키),
                                               payload {periodEnd, actual, estimate, surprisePct, currency, smallBase, reportedAt, source}.
                                               (날짜, 티커)마다 upsert — 나중에 받은 값이 비어 있던 추정치를 채우거나 값을 고치면 새 값이
                                               남습니다(같으면 다시 쓰지 않음). 지우지 않습니다: 출처가 최근 4분기만 줘, 쌓아야 8분기가 됩니다.
  스냅샷 ``us.earnings_surprise.status``     : {generatedAt, source, universe, covered(저장 행이 있는 종목), withEstimate4(추정치 있는
                                               분기가 4개 이상 저장된 종목), budget, fetchedThisRun, failedThisRun, stoppedReason?,
                                               items:{TICKER:{fetchedAt, rows, latestReported, error?, isStale?, staleReason?, staleSince?}}}.
                                               rows·latestReported·covered·withEstimate4는 이번 응답이 아니라 저장된 행으로 셉니다
                                               (withEstimate4는 분기(periodEnd)마다 한 번 — 발표일이 바뀌어 두 행이 된 분기를 두 번
                                               세지 않음. rows는 행 수 그대로).

[예산·순서] 실행당 ``EARNINGS_SURPRISE_BUDGET``(기본 100)종목. 유니버스 약 576종목(ETF 제외)을 12시간마다 100종목씩 약 3일에 한 바퀴.
순서는 ① 한 번도 받지 않은 종목(처음 보는 종목 → 받은 적 없이 실패만 한 종목, 각각 티커 순) ② 발표가 나왔을 법한데 그 뒤로 아직
받지 않은 종목 — us_calendar 저장본(calendar.us_events, 같은 수집기가 쓴 것)의 실적일(date) 또는 지난 실적일(lastDate)이 오늘(미국
동부) 이하이고, 마지막으로 받은 시각이 그 날짜 + 1일(동부 자정) 전 — 장 마감 뒤 발표는 그날 받은 응답에 없으므로 다음 날까지 기다리지
않고 다시 묻습니다 ③ 가장 오래전에 받은 순. 같은 순위는 티커 순(결정적).

[실패 규칙 — 수집기 공통]
  - 일부 실패 → 받은 종목만 행을 쓰고, 실패한 종목의 상태 줄은 직전 값을 이어받아 ``isStale``(carry_over_missing — 3일 기한).
    관측 행은 실패와 상관없이 그대로입니다(지우지 않음).
  - 한 종목도 받지 못하면(전부 실패·첫 요청이 429) EmptyResult — 행·상태 모두 그대로.
  - yfinance 내부 호출이 없어졌거나 인자 모양이 바뀌었거나, 응답 5건 이상에 earnings가 하나도 없거나, 응답 5건 이상이 모두 저장할
    분기(발표 시각이 있는 지난 분기)를 하나도 주지 않으면 EmptyResult('yfinance 내부 API 변경') — 이번 응답은 모두 버리고 행·상태를
    그대로 둡니다(모양이 바뀐 응답의 '자료 없음'을 믿지 않음). 다른 태스크는 영향 없음.
  - 한 종목의 응답을 읽다 난 값 오류(ShapeError 밖의 ValueError·OverflowError·OSError)도 그 종목만 실패로 셉니다.
  - 429면 그 실행을 멈추고(같은 Yahoo 한도를 us_calendar·paper_quotes와 나눠 씀) 받은 만큼 저장, ``stoppedReason``에 적습니다.
    시간 상한·연속 실패로 멈춘 것도 같습니다. 예산 소진은 정상 끝이라 적지 않습니다.
"""
from __future__ import annotations

import logging
import time
from datetime import date, datetime, timezone

from .. import catalog, logredact, settings, store
from ..http import brief_error
from ..services import earnings_surprise as surprise_service

from .base import EmptyResult, _parse_time, _reason_suffix, carried_suffix, carry_over_missing, previous_payload
from .us_calendar import stock_universe

logger = logging.getLogger(__name__)

# 새 요청을 더 시작하지 않는 경과 시간(초). weekly 상한(runner.TASK_DEADLINE_SECONDS["weekly"] = 1800초)을 넘기면 '시간 초과'로
# 끝나 상태를 못 남기므로 상한 전에 멈추고 받은 만큼 씁니다. 남는 600초는 진행 중인 요청(yfinance 응답 대기 30초)과 저장의 여유이고,
# 정상이면 100종목에 70~120초, 예산 상한 600종목이어도 약 7~12분입니다.
TIME_LIMIT_SECONDS = 1200.0

# Yahoo 요청 사이 간격(초) — us_calendar와 같은 값. 단계 0은 0.6초 간격으로 60종목에 429가 없었습니다.
SPACING_SECONDS = 0.5

# 이번 실행에서 한 종목이라도 받은 뒤, 직전에 제대로 받은 종목이 이만큼 연속으로 실패하면(응답 없음·오류) 나머지를 다음 실행으로
# 미룹니다 — 실행 도중 Yahoo가 막히면 남은 종목 모두를 '이전 값(isStale)'으로 적지 않게. 두 가지는 세지 않습니다:
#   ① 받은 적 없는 종목·직전에도 실패한 종목(이어받은 줄)의 실패 — 늘 실패하는 종목은 순서의 앞(① 또는 ③의 맨 앞 — 받은 시각이
#      그대로라)에 계속 섭니다.
#   ② 아직 한 종목도 받지 못한 동안의 실패 — 거기서 멈추면 받은 것이 없어 EmptyResult → 상태가 그대로 → 다음 실행도 같은 자리에서
#      멈춰 뒤의 종목을 영영 묻지 못합니다(sec_fundamentals에서 겪은 일). 처음부터 막힌 실행은 예산·시간 상한까지 빠르게 실패하고
#      EmptyResult로 끝납니다(아무것도 덮지 않음).
CONSECUTIVE_FAILURE_LIMIT = 5

# 응답이 이만큼 왔는데 earnings가 하나도 없거나, 저장할 분기를 준 응답이 하나도 없으면 Yahoo·yfinance의 응답 모양이 바뀐 것으로
# 봅니다(조용한 '자료 없음' 방지). 두 번째 조건은 earnings는 있는데 안쪽(earningsChart·quarterly·reportedDate)이 비거나 이름이 바뀐
# 경우입니다 — 단계 0에서 60종목 모두 발표 시각이 있는 분기를 줬으므로, 5건이 모두 비는 것은 정상 응답으로 볼 근거가 없습니다.
GUARD_MIN_RESPONSES = 5
API_CHANGED = "yfinance 내부 API 변경"

# 상태에 사유를 몇 개까지 적는가(실행 기록 detail은 _reason_suffix가 둘까지).
_REASONS_SHOWN = 2


def task_earnings_surprise() -> str:
    """
    유니버스 종목(ETF 제외 — us_calendar와 같은 범위)의 최근 4분기 EPS 실제치·컨센서스(yfinance quoteSummary earnings)
    → observations ``us.earnings_surprise`` + 상태 ``us.earnings_surprise.status``.
    """
    universe = stock_universe()
    if not universe:
        raise EmptyResult("유니버스(ETF 제외)가 비어 있음 — 물을 종목 없음")
    previous = previous_payload(catalog.SNAP_EARNINGS_SURPRISE_STATUS)
    prev_items = _as_dict(previous.get("items"))
    now = _now()
    today = now.astimezone(surprise_service.NEW_YORK).date()
    reported = calendar_report_dates(previous_payload(catalog.SNAP_US_CALENDAR), today)
    budget = settings.earnings_surprise_budget()
    order = priority_order(universe, prev_items, reported, today)
    started = _clock()

    fresh: dict[str, surprise_service.Parsed] = {}
    failed: dict[str, str] = {}
    not_tried = {"budget": 0, "budgetDue": 0, "time": 0, "rate": 0, "failures": 0}
    attempted = responses = with_module = with_rows = consecutive = 0
    broken: str | None = None

    for index, ticker in enumerate(order):
        remaining = len(order) - index
        if attempted >= budget:
            not_tried["budget"] = remaining
            # 묻지 않은 종목 가운데 정말 다음 실행이 받아야 하는 것(① 받은 적 없음 ② 실적일이 지남)만 따로 셉니다 — 나머지(③)는
            # 이미 받았고 오래된 순으로 다시 받을 뿐이라, 다 셈하면 다 받은 뒤에도 "n종목은 다음 실행"으로 보입니다.
            not_tried["budgetDue"] = sum(1 for t in order[index:] if _rank(t, prev_items, reported, today)[0] < 2)
            break
        if consecutive >= CONSECUTIVE_FAILURE_LIMIT:
            not_tried["failures"] = remaining
            break
        if attempted:
            _sleep(SPACING_SECONDS)
        # 간격을 쉰 뒤에 잽니다 — 쉬는 동안 상한을 넘었으면 새 요청을 보내지 않습니다.
        if _clock() - started > TIME_LIMIT_SECONDS:
            not_tried["time"] = remaining
            break
        attempted += 1
        counts = _counts_toward_limit(prev_items.get(ticker))
        try:
            raw = surprise_service.fetch_earnings(ticker)
        except surprise_service.InternalApiChanged as exc:
            broken = f"{API_CHANGED} — {exc}"
            break
        except surprise_service.RateLimited:
            # 그 종목의 실패가 아니라 Yahoo 한도라 상태 줄은 건드리지 않고(다음 실행이 같은 순서로 다시 물음) 이번 실행을 멈춥니다.
            not_tried["rate"] = remaining
            break
        except Exception as exc:  # noqa: BLE001 — 한 종목의 예기치 못한 오류로 나머지를 멈추지 않습니다
            failed[ticker] = logredact.redact(brief_error(exc))
            consecutive += int(counts and bool(fresh))
            continue
        if raw is None:
            failed[ticker] = "응답 없음(HTTP 오류 — 없는 종목·ETF면 404)"
            consecutive += int(counts and bool(fresh))
            continue
        responses += 1
        try:
            parsed = surprise_service.parse_earnings(raw, now)
        except surprise_service.ShapeError as exc:
            failed[ticker] = f"응답 모양이 다름: {exc}"
            consecutive += int(counts and bool(fresh))
            continue
        except (ValueError, OverflowError, OSError) as exc:
            # 날짜 변환 등에서 난 그 밖의 값 오류 — 실행 전체를 죽이면 상태가 그대로라 다음 실행도 같은 종목에서 다시 죽습니다.
            failed[ticker] = f"응답을 읽지 못함: {brief_error(exc)}"
            consecutive += int(counts and bool(fresh))
            continue
        consecutive = 0
        with_module += int(parsed.has_module)
        with_rows += int(bool(parsed.rows))
        fresh[ticker] = parsed

    if broken is None and responses >= GUARD_MIN_RESPONSES and not with_module:
        broken = f"{API_CHANGED} — 응답 {responses}건 모두 earnings가 없거나 모양이 다름"
    if broken is None and responses >= GUARD_MIN_RESPONSES and not with_rows:
        broken = f"{API_CHANGED} — 응답 {responses}건 모두 발표 시각이 있는 지난 분기가 없음"
    if broken:
        # 모양이 바뀐 응답의 '자료 없음'·종목별 실패는 믿을 수 없는 결과라 하나도 쓰지 않습니다(행·상태 그대로).
        raise EmptyResult(f"{broken}(yfinance {surprise_service.yfinance_version()}). 어닝 서프라이즈 수집을 멈추고 "
                          "저장본(관측 행·상태)을 그대로 둠")

    calendar_due = sum(1 for ticker in order[:attempted] if _rank(ticker, prev_items, reported, today)[0] == 1)
    reasons = [f"{ticker}: {reason}" for ticker, reason in list(failed.items())[:_REASONS_SHOWN]]
    if not_tried["rate"]:
        reasons.insert(0, "Yahoo 429(요청 제한)")
    if not fresh:
        logger.info("어닝 서프라이즈 빈 결과(저장본 유지): 시도 %d, 실패 %d", attempted, len(failed))
        raise EmptyResult(f"0/{attempted}종목 — 기존 저장본 유지" + _reason_suffix(reasons))

    stamp = now.isoformat()
    rows_sent = 0
    for ticker, parsed in fresh.items():
        if parsed.rows:
            rows_sent += store.put_entity_observations(catalog.OBS_US_EARNINGS_SURPRISE, ticker, parsed.rows)
    stored = store.observation_entity_summary(catalog.OBS_US_EARNINGS_SURPRISE, "estimate", "periodEnd")

    items: dict[str, dict] = {}
    carried: list[str] = []
    for ticker in universe:
        before = prev_items.get(ticker)
        if ticker in fresh:
            items[ticker] = {"fetchedAt": stamp}
        elif ticker in failed:
            carried += carry_over_missing(items, prev_items, [ticker], failed[ticker], _has_data)
            if ticker not in items:
                # 이어받을 값이 없음(받은 적 없음·이어받은 지 3일이 지남) — 오류만 적습니다. fetchedAt이 없어 다음 실행의 ①에 섭니다.
                items[ticker] = {"fetchedAt": None, "error": failed[ticker]}
        elif isinstance(before, dict):
            items[ticker] = dict(before)
        else:
            continue
        # 행 수·최신 발표일은 저장된 행에서 다시 셉니다(실패·이어받은 줄도 — 행은 실패와 상관없이 그대로이기 때문).
        summary = stored.get(ticker)
        items[ticker]["rows"] = summary["rows"] if summary else 0
        items[ticker]["latestReported"] = summary["latest"] if summary else None

    in_universe = [stored[ticker] for ticker in universe if ticker in stored]
    payload = {
        "generatedAt": stamp,
        "source": f"Yahoo Finance quoteSummary earnings.earningsChart (yfinance {surprise_service.yfinance_version()} 내부 호출 "
                  "Ticker._quote._fetch) — 최근 4분기, 날짜는 실제 발표 시각의 미국 동부 날짜",
        "universe": len(universe),
        "covered": sum(1 for summary in in_universe if summary["rows"] > 0),
        "withEstimate4": sum(1 for summary in in_universe if summary["periodsWithNumber"] >= 4),
        "budget": budget,
        "fetchedThisRun": len(fresh),
        "failedThisRun": len(failed),
        "items": items,
    }
    stopped = _stopped_reason(not_tried)
    if stopped:
        payload["stoppedReason"] = stopped
    store.put_snapshot(catalog.SNAP_EARNINGS_SURPRISE_STATUS, payload)
    return _detail(payload, fresh, attempted, rows_sent, calendar_due, not_tried, carried, reasons)


# ==============================================================================
# 순서 (네트워크·DB 없음)
# ==============================================================================
def calendar_report_dates(calendar: dict, today: date) -> dict[str, date]:
    """
    us_calendar 저장본(calendar.us_events)에서 종목마다 '이미 지난 실적일' — 다음 실적일(date)과 지난 실적일(lastDate) 가운데 오늘
    (미국 동부) 이하인 것의 가장 늦은 날. us_calendar는 종목마다 24시간 캐시라, 지난 실적일이 아직 date 칸에 남아 있을 수 있습니다.
    저장본이 없거나 모양이 다르면 빈 사전(순서 ②가 없을 뿐 ①·③은 그대로).
    """
    items = _as_dict(_as_dict(calendar.get("earnings")).get("items"))
    out: dict[str, date] = {}
    for ticker, entry in items.items():
        if not isinstance(entry, dict):
            continue
        days = [day for day in (_parse_day(entry.get("date")), _parse_day(entry.get("lastDate")))
                if day is not None and day <= today]
        if days:
            out[str(ticker)] = max(days)
    return out


def priority_order(universe: list[str], previous: dict, reported: dict[str, date], today: date) -> list[str]:
    """
    이번 실행에서 물을 순서(명세 C2 '증분'): ① 한 번도 받지 않음(처음 보는 종목 → 받은 적 없이 실패만 한 종목) ② 실적일이 오늘 이하이고
    마지막으로 받은 시각이 그 날짜 + 1일(동부 자정) 전 ③ 가장 오래전에 받은 순. 같은 순위는 티커 순. 모든 종목이 들어갑니다
    (캐시로 빼지 않음 — 예산이 실행당 물을 수를 정함).
    """
    return sorted(universe, key=lambda ticker: _rank(ticker, previous, reported, today))


def _rank(ticker: str, previous: dict, reported: dict[str, date], today: date) -> tuple:
    entry = previous.get(ticker)
    fetched = _parse_time(entry.get("fetchedAt")) if isinstance(entry, dict) else None
    if fetched is None:
        # 상태 줄이 있는데 fetchedAt이 없으면 받은 적 없이 실패만 한 종목 — 처음 보는 종목 뒤에 둡니다(새 종목이 늘 실패하는 종목을
        # 기다리지 않게).
        return 0, int(isinstance(entry, dict)), 0.0, ticker
    day = reported.get(ticker)
    # '그 날짜 + 1일 전' = 받은 시각의 동부 날짜가 실적일 이하. 장 마감 뒤(16:00 ET~) 발표는 그날 낮에 받은 응답에 없습니다.
    if day is not None and day <= today and fetched.astimezone(surprise_service.NEW_YORK).date() <= day:
        return 1, 0, 0.0, ticker
    return 2, 0, fetched.timestamp(), ticker


# ==============================================================================
# 실행 결과
# ==============================================================================
def _stopped_reason(not_tried: dict) -> str | None:
    if not_tried["rate"]:
        return f"Yahoo 429(요청 제한)로 멈춤 — {not_tried['rate']}종목은 다음 실행"
    if not_tried["time"]:
        return f"시간 상한({TIME_LIMIT_SECONDS:.0f}초) — {not_tried['time']}종목은 다음 실행"
    if not_tried["failures"]:
        return f"연속 실패 {CONSECUTIVE_FAILURE_LIMIT}회로 멈춤 — {not_tried['failures']}종목은 다음 실행"
    return None


def _detail(payload: dict, fresh: dict, attempted: int, rows_sent: int, calendar_due: int, not_tried: dict,
            carried: list[str], reasons: list[str]) -> str:
    no_data = sum(1 for parsed in fresh.values() if not parsed.rows)
    skipped = sum(parsed.skipped for parsed in fresh.values())
    detail = (f"{len(fresh)}/{attempted}종목 갱신(예산 {payload['budget']}, 행 {rows_sent}개"
              + (f", 실적 발표 뒤 우선 {calendar_due}" if calendar_due else "")
              + (f", 분기 자료 없음 {no_data}" if no_data else "")
              + (f", 발표 시각 없는·미래 분기 {skipped}개 뺌" if skipped else "")
              + f") · 서프라이즈 있음 {payload['covered']}/{payload['universe']}, 추정치 4분기 이상 {payload['withEstimate4']}")
    if not_tried["budget"]:
        due = not_tried["budgetDue"]
        rest = not_tried["budget"] - due
        if due:
            detail += f", 예산 소진 — 아직 받지 않았거나 새 실적이 나왔을 {due}종목은 다음 실행"
            detail += f"(나머지 {rest}종목은 이미 받음)" if rest else ""
        else:
            detail += f", 예산 소진 — 남은 {rest}종목은 이미 받음(오래전에 받은 순으로 다시 받음)"
    if payload.get("stoppedReason"):
        detail += f", {payload['stoppedReason']}"
    if payload["failedThisRun"]:
        detail += f", 실패 {payload['failedThisRun']}종목" + carried_suffix(carried)
    return detail + _reason_suffix(reasons)


# ==============================================================================
# 작은 도구 — 테스트가 바꿔 끼우는 자리
# ==============================================================================
def _now() -> datetime:
    """지금 시각(UTC). 테스트가 시계를 바꿔 끼우는 자리입니다 — 날짜는 미국 동부로 바꿔 씁니다(서버 TZ는 UTC)."""
    return datetime.now(timezone.utc)


def _clock() -> float:
    """경과 시간 시계(초). 테스트가 시간 상한을 시험할 때 바꿔 끼웁니다."""
    return time.monotonic()


def _sleep(seconds: float) -> None:
    time.sleep(seconds)


def _counts_toward_limit(entry) -> bool:
    """연속 실패 상한에 세는 종목 — 직전 상태에서 제대로 받은 줄(받은 시각이 있고 이어받은 줄이 아님)."""
    return _has_data(entry) and not entry.get("isStale")


def _has_data(entry) -> bool:
    """이어받을 값이 있는 상태 줄 — 한 번이라도 응답을 읽은 종목(분기 자료가 없다는 답도 답입니다)."""
    return isinstance(entry, dict) and bool(entry.get("fetchedAt"))


def _as_dict(value) -> dict:
    return value if isinstance(value, dict) else {}


def _parse_day(text) -> date | None:
    if not isinstance(text, str):
        return None
    try:
        return date.fromisoformat(text[:10])
    except ValueError:
        return None
