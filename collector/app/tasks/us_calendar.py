"""
app/tasks/us_calendar.py
slow 그룹 — 모의 매매(운영자 전용 기능) Claude 계좌 패킷 섹션 ``1b_calendar``의 발표·실적 일정(2단계 명세 A1~A3·§10).

태스크는 "받아서 저장"만 합니다. 기준일 다음 세션부터 10거래일 창, 보유·후보 종목 고르기, '일정 미공개' 판단은 백엔드가 이
저장본을 읽어 합니다(ADR 0008 — 계좌 보유는 백엔드 테이블이라 수집기는 모릅니다). 규칙 계좌·검증기는 읽지 않습니다(D35).

[저장] 스냅샷 ``calendar.us_events`` (한 건)
  generatedAt
  macro.events   : 앞으로 45일(미국 동부 오늘부터)의 [{name, date, kind "FOMC"|"RELEASE", releaseId, source, …}].
                   FOMC는 sep·meetingStart, 릴리스는 key·agency, 이어받은 것은 isStale·staleReason·staleSince.
  macro.horizon  : {from, to} — 위 45일. 저장본의 거시 일정은 이 범위 안뿐이라, 패킷 창이 이 밖으로 나가면 백엔드가 '수집 범위 밖'으로
                   적습니다(저장본이 오래 묵은 경우 — 그 밖의 날짜는 '일정 없음'이 아님).
  macro.fred     : {status "ok"|"키 미설정"|"실패", source, lastKnownDate {CPI, PCE, EMPLOYMENT, GDP}, isStale?, failed?}.
                   lastKnownDate = 릴리스마다 FRED가 알려 준 마지막 발표일(수집 창 끝이 속한 해의 12-31까지 묻고, 45일 창 밖 날짜도
                   셉니다. 이번에 없으면 직전 값). 패킷 창이 그 뒤로 넘어가면 백엔드가 '일정 미공개'로 적습니다 — FRED는 그해
                   12-31까지만 주고, 12월에는 다음 해 날짜가 비기 때문입니다. None = 모름.
  macro.fomc     : {source, checkedAt, pageUpdated, through, status "ok"|"갱신 필요"} — 상수(services.us_calendar.FOMC_MEETINGS).
  earnings.items : {TICKER: {date, session, isEstimated, epoch, source, fetchedAt, checkedAt, note?, lastDate?, isStale?,
                   staleReason?, staleSince?, error?}} — 유니버스 종목(ETF 제외) 전부. date None = 모름(0·지어낸 날짜 없음).
  earnings.*     : source·status("ok"|"yfinance 내부 API 변경")·stoppedReason?·covered(받은 적 있는 종목)·upcoming(오늘 이후
                   날짜가 있는 종목)·universe·budget·fetchedThisRun·failedThisRun.

[실적 — 예산과 캐시] 종목마다 24시간 캐시(checkedAt). 실행당 ``US_CALENDAR_EARNINGS_BUDGET``(기본 120)종목까지 묻고, 순서는
① 한 번도 묻지 않은 종목(티커 순) ② 가장 오래전에 물은 종목. 실패한 종목은 6시간 뒤 다시 묻습니다(없는 종목의 404가 매시간
예산과 로그를 쓰지 않게). 명세의 '보유·후보 최대 60개' 대신 유니버스 전체인 이유는 위 ADR 0008 — 백엔드가 패킷에서 보유 ∪ 후보를 고릅니다.

[실패 규칙 — 수집기 공통]
  - 한 릴리스·한 종목이 실패하면 직전 저장본의 같은 항목을 잇고 ``isStale``을 붙입니다(종목은 carry_over_missing — 3일 기한).
    릴리스 일정은 날짜가 지나면 창에서 저절로 빠지므로 기한을 두지 않습니다.
  - FRED 키가 없으면 FRED 일정은 비고 status '키 미설정'(설정 상태라 이어받지 않음). FOMC 상수는 키가 필요 없어 그대로 들어갑니다.
  - 무언가를 시도했는데 새로 받은 것이 하나도 없으면(FRED 전부 실패·키 없음 + 실적 전부 실패·물을 종목 없음) EmptyResult —
    저장본을 덮지 않습니다. 시도할 것이 없었던 경우(키 없음 + 캐시가 모두 유효)는 실패가 아니라 저장본만 새로 씁니다.
    '실적 전부 실패'는 이번에 물은 종목이 모두 실패했고 **캐시가 유효한 종목(24시간 안에 받은 것)도 없을 때**입니다. 키가 없을 때
    늘 404인 종목 하나만 물을 차례인 실행을 전부 실패로 보면, 저장하지 않아 그 종목의 checkedAt이 그대로 남아 매시간 다시 묻고
    매시간 실패로 보였습니다(유니버스 576 중 1개 실패는 일부 실패입니다).
  - yfinance 내부 호출이 없어졌거나 응답 모양이 바뀌면 EmptyResult('yfinance 내부 API 변경')로 실패로 보입니다. 실적은 이번 응답을
    버리고 직전 값을 isStale로 잇되(3일 기한), 거시 일정은 새로 저장합니다 — 코드를 고칠 때까지 이어지는 고장이라 거시까지 버리면
    저장본의 45일 창이 굳어, 몇 주 뒤 패킷 창 안의 FOMC·발표일이 말없이 빠졌습니다. FRED도 전부 실패했으면 새로 쓸 것이 없어
    저장본을 덮지 않습니다.
"""
from __future__ import annotations

import logging
import time
from datetime import date, datetime, timedelta, timezone

from .. import catalog, equities_cusip, indicators, logredact, paper_universe, settings, store
from ..http import brief_error
from ..services import us_calendar as calendar_service

from .base import EmptyResult, _parse_time, _reason_suffix, carried_suffix, carry_over_missing, previous_payload

logger = logging.getLogger(__name__)

# 앞으로 며칠의 거시 일정을 받는가(명세 A1). 패킷 창(10거래일 ≈ 14일)보다 넉넉히 받아, 초기 결정처럼 기준일이 며칠 앞이어도 창이 덮입니다.
HORIZON_DAYS = 45

# 새 요청을 더 시작하지 않는 경과 시간(초). slow 상한(runner.TASK_DEADLINE_SECONDS["slow"] = 600초)을 넘기면 '시간 초과'로 끝나
# 저장본을 못 남기므로 상한 전에 멈추고 받은 만큼 씁니다. 남는 180초는 진행 중인 요청 하나(yfinance 응답 대기 30초·FRED 30초 ×
# 세션 재시도)와 저장의 여유입니다. 정상이면 FRED 4건 1~2초 + 실적 120종목 80~140초.
TIME_LIMIT_SECONDS = 420.0
# FRED는 이 시간 안에서만 다음 릴리스를 묻습니다 — 응답 없는 FRED(요청당 30초 × 재시도)가 실적 쪽 시간까지 쓰지 않게.
FRED_TIME_LIMIT_SECONDS = 150.0

# 종목 캐시(명세 A2 '종목당 24시간')와 실패한 종목을 다시 묻는 간격.
EARNINGS_CACHE = timedelta(hours=24)
FAILED_RETRY = timedelta(hours=6)

# Yahoo 요청 사이 간격(초). 단계 0은 0.6초 간격으로 60종목에 429가 없었습니다. 같은 Yahoo 한도를 paper_quotes·카드 수집과
# 나눠 쓰므로 몰아 보내지 않습니다.
EARNINGS_SPACING_SECONDS = 0.5

# 이만큼 연속으로 실패하면(응답 없음·오류) 이번 실행의 나머지 종목을 묻지 않습니다 — Yahoo 장애 때 종목마다 같은 실패를 쌓지 않게.
# 실패한 종목은 checkedAt이 새로 적혀 다음 실행 순서의 뒤로 가므로, 늘 실패하는 종목 몇 개가 태스크를 멈춰 두지 않습니다.
CONSECUTIVE_FAILURE_LIMIT = 5

# 응답이 이만큼 왔는데 calendarEvents가 하나도 없으면 Yahoo·yfinance의 응답 모양이 바뀐 것으로 봅니다(조용한 '실적일 없음' 방지).
GUARD_MIN_RESPONSES = 5
# 내부 호출이 없어졌거나 응답 모양이 바뀐 상태 — 실패 사유의 머리말이자 저장본 earnings.status.
API_CHANGED = "yfinance 내부 API 변경"


def task_us_calendar() -> str:
    """
    거시 발표일(FRED 릴리스 4개·FOMC 상수)과 유니버스 종목의 다음 실적 발표일(yfinance) → 스냅샷 ``calendar.us_events``.
    """
    previous = previous_payload(catalog.SNAP_US_CALENDAR)
    now = _now()
    today = now.astimezone(calendar_service.NEW_YORK).date()
    started = _clock()

    macro, fred = _collect_macro(_as_dict(previous.get("macro")), today, now, started)
    earnings, run = _collect_earnings(_as_dict(previous.get("earnings")), now, today, started)
    payload = {"generatedAt": now.isoformat(), "macro": macro, "earnings": earnings}

    fred_failed_all = fred["hasKey"] and not fred["ok"]
    if run["broken"]:
        # 실적 쪽 내부 API 변경은 코드를 고칠 때까지 이어집니다. 실적과 무관한 거시 일정까지 버리면 저장본의 45일 창이 그동안 굳어
        # 패킷 창 안의 FOMC가 말없이 빠지므로, 새로 받은 거시가 있으면(FRED 성공 또는 키 없음 — FOMC 상수) 저장하고 실패로 알립니다.
        if fred_failed_all:
            kept = "FRED도 전부 실패 — 기존 저장본 유지"
        else:
            store.put_snapshot(catalog.SNAP_US_CALENDAR, payload)
            kept = f"거시 일정은 새로 저장({len(macro['events'])}건)"
        raise EmptyResult(f"{run['broken']}(yfinance {calendar_service.yfinance_version()}). 실적 일정을 멈추고 직전 값을 "
                          f"이어받음, {kept}" + _reason_suffix(fred["reasons"]))

    # 실적 '전부 실패'는 이번에 물은 종목만 보지 않습니다 — 캐시가 유효한 종목이 있으면 일부 실패라 저장해야, 실패한 종목의
    # checkedAt이 새로 적혀 6시간 뒤에만 다시 묻습니다(모듈 설명 '실패 규칙').
    earnings_failed_all = run["attempted"] > 0 and not run["fresh"] and not run["cachedValid"]
    if not fred["ok"] and not run["fresh"] and (fred_failed_all or earnings_failed_all):
        raise EmptyResult(
            "새로 받은 일정 없음 — 기존 저장본 유지"
            + _reason_suffix([*fred["reasons"], *run["reasons"]])
        )

    store.put_snapshot(catalog.SNAP_US_CALENDAR, payload)
    return _detail(macro, fred, earnings, run)


# ==============================================================================
# 거시 — FRED 릴리스 + FOMC 상수
# ==============================================================================
def _collect_macro(previous: dict, today: date, now: datetime, started: float) -> tuple[dict, dict]:
    end = today + timedelta(days=HORIZON_DAYS)
    # FRED에는 수집 창 끝이 속한 해의 12-31까지 묻습니다(호출 수는 그대로 4회). 45일 창 끝까지만 물으면 lastKnownDate가 'FRED가 아는
    # 마지막 날짜'가 아니라 '창 안 마지막 날짜'가 되어, 발표 간격이 한 달을 넘는 릴리스(고용·CPI의 5주 간격)는 다음 날짜를 FRED가
    # 이미 아는데도 백엔드가 '일정 미공개'로 적었습니다. 일정(events)은 아래에서 45일로 자릅니다.
    fred_through = date(end.year, 12, 31)
    prev_fred = _as_dict(previous.get("fred"))
    prev_known = _as_dict(prev_fred.get("lastKnownDate"))
    prev_events = [event for event in previous.get("events") or []
                   if isinstance(event, dict) and event.get("kind") == "RELEASE"]

    has_key = bool(settings.fred_key())
    events: list[dict] = []
    last_known: dict[str, str | None] = {}
    failed: dict[str, str] = {}
    carried: list[str] = []
    ok = 0

    for release in calendar_service.RELEASES:
        if not has_key:
            last_known[release.key] = None
            continue
        if _clock() - started > FRED_TIME_LIMIT_SECONDS:
            failed[release.key] = f"시간 상한({FRED_TIME_LIMIT_SECONDS:.0f}초) — 다음 실행"
        else:
            try:
                dates = calendar_service.fetch_release_dates(release.release_id, today, fred_through)
            except calendar_service.CalendarFetchError as exc:
                failed[release.key] = str(exc)
                logger.warning("FRED 발표 일정 실패 (%s): %s", release.key, exc)
            else:
                ok += 1
                events += [_release_event(release, day) for day in dates if _within(day, today, end)]
                # 받은 날짜가 없으면(12월 — 다음 해 일정이 아직 없음) 직전에 받은 마지막 날짜를 둡니다.
                last_known[release.key] = dates[-1] if dates else _text(prev_known.get(release.key))
                continue
        # 실패 — 직전 저장본의 같은 릴리스 일정을 잇습니다. 지난 날짜는 창에서 빠집니다.
        before = [event for event in prev_events
                  if event.get("releaseId") == release.release_id and _within(event.get("date"), today, end)]
        for event in before:
            since = event.get("staleSince") if event.get("isStale") else None
            events.append({**event, "isStale": True, "staleReason": failed[release.key],
                           "staleSince": since or now.isoformat()})
        if before:
            carried.append(release.key)
        last_known[release.key] = _text(prev_known.get(release.key))

    fomc = calendar_service.fomc_events(today, end)
    events += fomc
    events.sort(key=lambda event: (event["date"], event["kind"], event["name"]))

    status = "키 미설정" if not has_key else ("ok" if ok else "실패")
    fred_section: dict = {"status": status, "source": calendar_service.FRED_SOURCE, "lastKnownDate": last_known}
    if failed:
        fred_section["isStale"] = True
        fred_section["failed"] = failed
    macro = {
        "events": events,
        "horizon": {"from": today.isoformat(), "to": end.isoformat()},
        "fred": fred_section,
        "fomc": {
            "source": calendar_service.FOMC_SOURCE,
            "checkedAt": calendar_service.FOMC_CHECKED_AT,
            "pageUpdated": calendar_service.FOMC_PAGE_UPDATED,
            "through": calendar_service.FOMC_THROUGH,
            "status": calendar_service.fomc_status(end),
        },
    }
    reasons = ["FRED: " + reason for reason in failed.values()]
    return macro, {"hasKey": has_key, "ok": ok, "carried": carried, "fomc": len(fomc), "reasons": reasons}


def _release_event(release: calendar_service.Release, day: str) -> dict:
    return {
        "name": release.name,
        "key": release.key,
        "date": day,
        "kind": "RELEASE",
        "releaseId": release.release_id,
        "agency": release.agency,
        "source": f"{calendar_service.FRED_SOURCE} — {release.agency} 일정",
    }


# ==============================================================================
# 실적 — yfinance quoteSummary calendarEvents
# ==============================================================================
def stock_universe() -> list[str]:
    """
    실적 일정을 묻는 종목 — 모의 매매 유니버스에서 ETF(설정 ETF와 13F 매핑표의 ETP)를 뺀 것. ETF는 실적이 없고 Yahoo가 404를
    줍니다(단계 0의 SPY). sec_fundamentals와 같은 범위입니다.
    """
    etfs = set(indicators.PAPER_ETFS) | equities_cusip.TRACK_RECORD_ETPS
    return [ticker for ticker in paper_universe.tradable() if ticker not in etfs]


def due_order(universe: list[str], previous: dict, now: datetime) -> list[str]:
    """
    이번 실행에서 물을 종목과 순서: ① 한 번도 묻지 않음(직전 줄이 없거나 checkedAt을 읽을 수 없음, 티커 순) ② 물은 지
    24시간(실패한 줄은 6시간)이 지난 종목, 가장 오래전에 물은 순. 캐시가 유효한 종목은 빠집니다.
    """
    ranked: list[tuple] = []
    for ticker in universe:
        entry = previous.get(ticker)
        checked = _parse_time(entry.get("checkedAt")) if isinstance(entry, dict) else None
        if checked is None:
            ranked.append((0, datetime.min.replace(tzinfo=timezone.utc), ticker))
            continue
        wait = FAILED_RETRY if _failed(entry) else EARNINGS_CACHE
        if now - checked >= wait:
            ranked.append((1, checked, ticker))
    return [ticker for *_, ticker in sorted(ranked)]


def _collect_earnings(previous: dict, now: datetime, today: date, started: float) -> tuple[dict, dict]:
    universe = stock_universe()
    prev_items = _as_dict(previous.get("items"))
    budget = settings.us_calendar_earnings_budget()
    order = due_order(universe, prev_items, now)
    stamp = now.isoformat()

    fresh: dict[str, dict] = {}
    failed: dict[str, str] = {}
    not_tried = {"budget": 0, "time": 0, "rate": 0, "failures": 0}
    attempted = responses = with_module = consecutive = 0
    broken: str | None = None

    for index, ticker in enumerate(order):
        remaining = len(order) - index
        if attempted >= budget:
            not_tried["budget"] = remaining
            break
        if consecutive >= CONSECUTIVE_FAILURE_LIMIT:
            not_tried["failures"] = remaining
            break
        if attempted:
            _sleep(EARNINGS_SPACING_SECONDS)
        # 간격을 쉰 뒤에 잽니다 — 쉬는 동안 상한을 넘었으면 새 요청을 보내지 않습니다.
        if _clock() - started > TIME_LIMIT_SECONDS:
            not_tried["time"] = remaining
            break
        attempted += 1
        try:
            raw = calendar_service.fetch_calendar_events(ticker)
        except calendar_service.InternalApiChanged as exc:
            broken = f"{API_CHANGED} — {exc}"
            break
        except calendar_service.RateLimited as exc:
            failed[ticker] = str(exc)
            not_tried["rate"] = remaining - 1
            break
        except Exception as exc:  # noqa: BLE001 — 한 종목의 예기치 못한 오류로 나머지를 멈추지 않습니다
            failed[ticker] = logredact.redact(brief_error(exc))
            consecutive += 1
            continue
        if raw is None:
            failed[ticker] = "응답 없음(HTTP 오류 — 없는 종목이면 404)"
            consecutive += 1
            continue
        responses += 1
        try:
            parsed = calendar_service.parse_earnings(raw, now)
        except calendar_service.ShapeError as exc:
            failed[ticker] = f"응답 모양이 다름: {exc}"
            consecutive += 1
            continue
        consecutive = 0
        with_module += int(parsed.has_module)
        fresh[ticker] = _entry(parsed, stamp)

    if broken is None and responses >= GUARD_MIN_RESPONSES and not with_module:
        broken = f"{API_CHANGED} — 응답 {responses}건 모두 calendarEvents가 없거나 모양이 다름"
    if broken:
        # 모양이 바뀐 응답의 '실적일 없음'·종목별 실패는 믿을 수 없는 결과라 버리고, 물을 차례였던 종목은 아래에서 직전 값을 잇습니다.
        fresh, failed = {}, {}

    due = set(order)
    items: dict[str, dict] = {}
    carried: list[str] = []
    for ticker in universe:
        before = prev_items.get(ticker)
        if ticker in fresh:
            items[ticker] = fresh[ticker]
        elif ticker in failed:
            carried += carry_over_missing(items, prev_items, [ticker], failed[ticker], _has_data)
            if ticker in items:
                items[ticker]["checkedAt"] = stamp
            else:
                # 이어받을 값이 없음 — 오류만 적습니다. checkedAt이 있으니 6시간 뒤에 다시 묻습니다.
                items[ticker] = {"date": None, "session": None, "isEstimated": None, "epoch": None,
                                 "source": calendar_service.EARNINGS_SOURCE, "fetchedAt": None, "checkedAt": stamp,
                                 "error": failed[ticker]}
        elif broken and ticker in due:
            # 내부 API 변경으로 받지 못한 종목 — 직전 값을 isStale로 잇습니다(3일 기한이 지나면 빠져 패킷에 '자료 없음').
            # checkedAt은 그대로 둬, 코드를 고친 뒤 첫 실행이 바로 다시 묻습니다. 이어받을 값이 없는 오류 줄은 그대로 둡니다.
            carried += carry_over_missing(items, prev_items, [ticker], broken, _has_data)
            if ticker not in items and isinstance(before, dict) and not _has_data(before):
                items[ticker] = before
        elif isinstance(before, dict):
            items[ticker] = before

    earnings = {
        "source": f"Yahoo Finance quoteSummary calendarEvents (yfinance {calendar_service.yfinance_version()} 내부 호출 "
                  "Ticker._quote._fetch) — 날짜는 미국 동부, 장 구분은 UTC 12:30 BMO·20:00 AMC",
        "status": API_CHANGED if broken else "ok",
        "items": items,
        "covered": sum(1 for entry in items.values() if _has_data(entry)),
        "upcoming": sum(1 for entry in items.values() if _within(entry.get("date"), today, date.max)),
        "universe": len(universe),
        "budget": budget,
        "fetchedThisRun": len(fresh),
        "failedThisRun": len(failed),
    }
    if broken:
        earnings["stoppedReason"] = broken
    # 캐시가 유효한 종목 — 물을 차례가 아니고(24시간 안에 물음) 마지막에 제대로 받은 줄. 실적 '전부 실패' 판단에 씁니다.
    cached_valid = sum(1 for ticker in universe if ticker not in due
                       and isinstance(prev_items.get(ticker), dict) and not _failed(prev_items[ticker]))
    reasons = [f"{ticker}: {reason}" for ticker, reason in list(failed.items())[:2]]
    return earnings, {"attempted": attempted, "fresh": len(fresh), "failed": len(failed), "due": len(order),
                      "cachedValid": cached_valid, "notTried": not_tried, "carried": carried, "reasons": reasons,
                      "broken": broken}


def _entry(parsed: calendar_service.Earnings, stamp: str) -> dict:
    entry = {
        "date": parsed.date,
        "session": parsed.session,
        "isEstimated": parsed.is_estimated,
        "epoch": parsed.epoch,
        "source": calendar_service.EARNINGS_SOURCE,
        "fetchedAt": stamp,
        "checkedAt": stamp,
    }
    if parsed.note:
        entry["note"] = parsed.note
    if parsed.last_date:
        entry["lastDate"] = parsed.last_date
    return entry


def _has_data(entry) -> bool:
    """이어받을 값이 있는 줄 — 한 번이라도 응답을 읽은 종목(날짜가 None인 '실적일 없음'도 답입니다)."""
    return isinstance(entry, dict) and bool(entry.get("fetchedAt"))


def _failed(entry: dict) -> bool:
    """직전 줄이 실패로 적혔나 — 이어받은 줄(isStale)이거나 이어받을 값이 없어 오류만 남은 줄."""
    return bool(entry.get("isStale")) or not entry.get("fetchedAt")


# ==============================================================================
# 실행 결과 한 줄
# ==============================================================================
def _detail(macro: dict, fred: dict, earnings: dict, run: dict) -> str:
    fred_status = macro["fred"]["status"]
    fred_part = f"FRED {fred['ok']}/{len(calendar_service.RELEASES)}" if fred["hasKey"] else "FRED 키 미설정"
    detail = f"거시 {len(macro['events'])}건({fred_part}, FOMC {fred['fomc']}"
    if macro["fomc"]["status"] != "ok":
        detail += f", FOMC 상수 {macro['fomc']['status']}(~{macro['fomc']['through']})"
    detail += ")"
    if fred["carried"]:
        detail += f" · FRED 이전 값 {', '.join(fred['carried'])}"
    elif fred_status == "실패":
        detail += " · FRED 실패"
    cached = earnings["universe"] - run["due"]
    detail += (f" · 실적 {run['fresh']}/{run['attempted']}종목 갱신(예산 {earnings['budget']}, 캐시 {cached}), "
               f"일정 있음 {earnings['upcoming']}/{earnings['universe']}")
    not_tried = run["notTried"]
    if not_tried["budget"]:
        detail += f", 예산 소진 — {not_tried['budget']}종목은 다음 실행"
    if not_tried["time"]:
        detail += f", 시간 상한({TIME_LIMIT_SECONDS:.0f}초) — {not_tried['time']}종목은 다음 실행"
    if not_tried["rate"]:
        detail += f", Yahoo 429로 멈춤 — {not_tried['rate']}종목은 다음 실행"
    if not_tried["failures"]:
        detail += f", 연속 실패 {CONSECUTIVE_FAILURE_LIMIT}회로 멈춤 — {not_tried['failures']}종목은 다음 실행"
    if run["failed"]:
        detail += f", 실패 {run['failed']}종목" + carried_suffix(run["carried"])
    return detail + _reason_suffix([*fred["reasons"], *run["reasons"]])


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


def _as_dict(value) -> dict:
    return value if isinstance(value, dict) else {}


def _text(value) -> str | None:
    return value if isinstance(value, str) and value else None


def _within(text, start: date, end: date) -> bool:
    if not isinstance(text, str):
        return False
    try:
        day = date.fromisoformat(text[:10])
    except ValueError:
        return False
    return start <= day <= end
