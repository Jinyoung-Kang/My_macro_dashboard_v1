"""
tests/test_paper_tasks.py
모의 알고리즘 매매(운영자 전용 기능)의 가격 수집 태스크 (PostgreSQL 필요 — 유니버스 검사는 DB 없이).

고정하는 규칙
  1. paper.quotes 저장본 모양 {"period", "tickers": {T: {dates, close}}, "universe"} — 백엔드 PaperMarketData가 이 모양을 읽습니다.
  2. 일부 실패 → 빠진 티커는 직전 저장본으로 잇고 isStale(QA-032와 같은 규칙). 장부는 isStale 봉으로 체결하지 않습니다.
  3. 전부 실패 → EmptyResult, 저장본을 덮지 않습니다.
  4. 10년 이력은 묶음마다 받아 티커별 timeseries equity_close에 upsert — 값이 없는 점은 쓰지 않고, 기존 행은 지우지 않습니다.
  5. 유니버스 = 13F 이름 매핑 티커 ∪ 후보 기관 CUSIP 매핑 티커(장외 라인 포함) ∪ PAPER_ETFS, 중복 없음.
  6. 10년 이력은 동부 마감 + 15분(16:15) 전이면 오늘 세션의 봉을 쓰지 않습니다 — 백엔드가 이 값을 확정 종가로 믿고 사후 체결합니다.
     관측치(paper.quotes)는 장중 마지막 봉이 곧 관측치라 그대로 둡니다.
"""
from __future__ import annotations

from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import pytest

from app import catalog, equities, equities_cusip, indicators, paper_universe, tasks
from app.tasks import paper as paper_tasks

_FRAME = {"dates": ["2026-10-22", "2026-10-23"], "close": [100.0, 101.0]}
_OLD = {"dates": ["2026-10-21", "2026-10-22"], "close": [90.0, 91.0]}
_NY = ZoneInfo("America/New_York")


@pytest.fixture()
def two_tickers(monkeypatch):
    monkeypatch.setattr(paper_universe, "tradable", lambda: ("AAA", "BBB"))
    return "AAA", "BBB"


@pytest.fixture()
def ny_clock(monkeypatch):
    """태스크가 보는 시계를 동부 시각으로 고정합니다. 기본은 2026-10-23(금) 16:20 — 그날 봉까지 확정."""
    def pin(*args):
        pinned = datetime(*args, tzinfo=_NY)
        monkeypatch.setattr(paper_tasks, "_now", lambda: pinned)
        return pinned
    pin(2026, 10, 23, 16, 20)
    return pin


def test_시세_저장본_모양(store, monkeypatch, two_tickers):
    calls = []

    def fake(tickers, period="2y"):
        calls.append((tuple(tickers), period))
        return {"tickers": {"AAA": _FRAME, "BBB": _OLD}, "error": None}

    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes", fake)

    detail = paper_tasks.task_paper_quotes()

    assert calls == [(("AAA", "BBB"), catalog.PAPER_QUOTES_PERIOD)]
    payload = store.read_snapshot(catalog.SNAP_PAPER_QUOTES).payload
    assert payload["period"] == "3mo"
    assert payload["universe"] == ["AAA", "BBB"]
    assert payload["tickers"]["AAA"] == _FRAME
    assert "isStale" not in payload["tickers"]["AAA"]
    assert detail == "2/2 티커"


def test_시세_일부_실패는_직전_값을_잇고_isStale(store, monkeypatch, two_tickers):
    store.put_snapshot(catalog.SNAP_PAPER_QUOTES, {"period": "3mo", "tickers": {"AAA": _OLD, "BBB": _OLD},
                                                   "universe": ["AAA", "BBB"]})
    # 서비스는 일부 실패에 사유를 주지 않습니다(error=None) — 그래도 이어받고 이름을 남겨야 합니다.
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {"AAA": _FRAME}, "error": None})

    detail = paper_tasks.task_paper_quotes()

    saved = store.read_snapshot(catalog.SNAP_PAPER_QUOTES).payload["tickers"]
    assert saved["AAA"]["close"] == [100.0, 101.0] and "isStale" not in saved["AAA"]
    assert saved["BBB"]["close"] == _OLD["close"]
    assert saved["BBB"]["isStale"] is True
    assert saved["BBB"]["staleSince"]
    assert detail.startswith("1/2 티커") and "이전 값 1개(BBB)" in detail


def test_시세_이어받을_값도_없으면_빠지고_0으로_채우지_않는다(store, monkeypatch, two_tickers):
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {"AAA": _FRAME}, "error": None})

    detail = paper_tasks.task_paper_quotes()

    saved = store.read_snapshot(catalog.SNAP_PAPER_QUOTES).payload
    assert set(saved["tickers"]) == {"AAA"}
    assert saved["universe"] == ["AAA", "BBB"]
    assert "받지 못함 1개(BBB)" in detail


def test_시세_전부_실패는_EmptyResult이고_저장본을_덮지_않는다(store, monkeypatch, two_tickers):
    before = {"period": "3mo", "tickers": {"AAA": _OLD}, "universe": ["AAA", "BBB"]}
    store.put_snapshot(catalog.SNAP_PAPER_QUOTES, before)
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {}, "error": "AAA 429 Too Many Requests"})

    with pytest.raises(tasks.EmptyResult) as exc:
        paper_tasks.task_paper_quotes()

    assert "0/2 티커" in str(exc.value) and "429" in str(exc.value)
    assert store.read_snapshot(catalog.SNAP_PAPER_QUOTES).payload == before


def test_시세_전부_실패_첫_수집이면_저장본을_만들지_않는다(store, monkeypatch, two_tickers):
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {"AAA": {"dates": [], "close": []}}, "error": None})

    with pytest.raises(tasks.EmptyResult):
        paper_tasks.task_paper_quotes()

    assert store.read_snapshot(catalog.SNAP_PAPER_QUOTES) is None


def test_이력은_묶음마다_받아_티커별로_upsert하고_빈_값은_건너뛴다(store, monkeypatch, ny_clock):
    monkeypatch.setattr(paper_universe, "history", lambda: ("AAA", "BBB", "CCC"))
    monkeypatch.setattr(paper_tasks, "HISTORY_CHUNK", 2)
    # 이번에 받지 못하는 날짜의 기존 행은 남아야 합니다(지우지 않음).
    store.put_timeseries(catalog.TS_EQUITY_CLOSE, "AAA", [("2016-10-24", 30.0)])
    calls = []

    def fake(tickers, period="2y"):
        calls.append((tuple(tickers), period))
        if tuple(tickers) == ("AAA", "BBB"):
            return {"tickers": {
                "AAA": {"dates": ["2026-10-22", "2026-10-23"], "close": [100.0, 101.0]},
                "BBB": {"dates": ["2026-10-21", "2026-10-22", "2026-10-23"], "close": [50.0, None, 52.0]},
            }, "error": None}
        return {"tickers": {}, "error": "CCC possibly delisted"}

    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes", fake)

    detail = paper_tasks.task_paper_price_history()

    assert calls == [(("AAA", "BBB"), "10y"), (("CCC",), "10y")]
    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "AAA") == [
        {"date": "2016-10-24", "value": 30.0},
        {"date": "2026-10-22", "value": 100.0},
        {"date": "2026-10-23", "value": 101.0},
    ]
    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "BBB") == [
        {"date": "2026-10-21", "value": 50.0},
        {"date": "2026-10-23", "value": 52.0},
    ]
    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "CCC") == []
    assert detail.startswith("2/3 티커, 점 4개")
    assert "받지 못함 1개(CCC)" in detail and "delisted" in detail


def test_이력은_날짜와_값_길이가_다른_티커를_건너뛴다(store, monkeypatch, ny_clock):
    monkeypatch.setattr(paper_universe, "history", lambda: ("AAA", "BBB"))
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {
                            "AAA": {"dates": ["2026-10-23"], "close": [101.0]},
                            "BBB": {"dates": ["2026-10-22", "2026-10-23"], "close": [52.0]},
                        }, "error": None})

    detail = paper_tasks.task_paper_price_history()

    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "BBB") == []
    assert "1/2 티커" in detail and "길이가 다름" in detail


def test_이력을_하나도_못_받으면_EmptyResult(store, monkeypatch, ny_clock):
    monkeypatch.setattr(paper_universe, "history", lambda: ("AAA",))
    store.put_timeseries(catalog.TS_EQUITY_CLOSE, "AAA", [("2026-10-22", 100.0)])
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {}, "error": "Yahoo 차단"})

    with pytest.raises(tasks.EmptyResult) as exc:
        paper_tasks.task_paper_price_history()

    assert "0/1 티커" in str(exc.value) and "Yahoo 차단" in str(exc.value)
    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "AAA") == [{"date": "2026-10-22", "value": 100.0}]


@pytest.mark.parametrize(("now", "expected"), [
    # 금요일 장중·마감 직후는 아직 목요일까지만 확정입니다.
    (datetime(2026, 10, 23, 9, 31, tzinfo=_NY), "2026-10-22"),
    (datetime(2026, 10, 23, 16, 14, 59, tzinfo=_NY), "2026-10-22"),
    # 마감 + 15분부터 그날 봉이 확정 종가입니다(백엔드 PaperMarketData.isFinal과 같은 경계, 포함).
    (datetime(2026, 10, 23, 16, 15, tzinfo=_NY), "2026-10-23"),
    # 주말 — 금요일 봉까지 확정. 토요일 날짜의 봉은 없으니 '어제까지'여도 잃는 행이 없습니다.
    (datetime(2026, 10, 24, 10, 0, tzinfo=_NY), "2026-10-23"),
    # 서버 TZ(UTC)·화면 TZ(KST)로 들어와도 동부 날짜·시각으로 판정합니다. 10-23 16:15 EDT = 20:15 UTC = 10-24 05:15 KST.
    (datetime(2026, 10, 23, 20, 14, tzinfo=timezone.utc), "2026-10-22"),
    (datetime(2026, 10, 23, 20, 15, tzinfo=timezone.utc), "2026-10-23"),
    (datetime(2026, 10, 24, 5, 15, tzinfo=ZoneInfo("Asia/Seoul")), "2026-10-23"),
    # 서머타임이 끝난 뒤(EST, UTC-5)에는 같은 16:15가 21:15 UTC입니다.
    (datetime(2026, 12, 1, 21, 14, tzinfo=timezone.utc), "2026-11-30"),
    (datetime(2026, 12, 1, 21, 15, tzinfo=timezone.utc), "2026-12-01"),
])
def test_확정_종가_날짜는_동부_마감_15분_뒤부터_오늘(now, expected):
    assert paper_tasks.latest_final_date(now) == expected


def test_이력은_마감_15분_전이면_오늘_봉을_쓰지_않는다(store, monkeypatch, ny_clock):
    # 리뷰 정확성 #6: 장중(14:00 ET)에 돌면 10-23 봉은 그 시각의 지연 시세입니다. 이 값이 equity_close에 들어가면
    # 백엔드가 '그날 종가'로 믿고 사후 체결합니다(다음 실행까지 최대 12시간 남음).
    ny_clock(2026, 10, 23, 14, 0)
    monkeypatch.setattr(paper_universe, "history", lambda: ("AAA", "NEW"))
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {
                            "AAA": {"dates": ["2026-10-22", "2026-10-23"], "close": [100.0, 101.5]},
                            # 오늘 상장해 오늘 봉만 있는 티커 — 쓸 행이 없으니 받지 못한 것으로 셉니다.
                            "NEW": {"dates": ["2026-10-23"], "close": [20.0]},
                        }, "error": None})

    detail = paper_tasks.task_paper_price_history()

    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "AAA") == [{"date": "2026-10-22", "value": 100.0}]
    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "NEW") == []
    assert detail.startswith("1/2 티커, 점 1개")
    assert "2026-10-23 봉은 장 마감 + 15분 전이라 쓰지 않음" in detail

    # 같은 날 마감 + 15분 뒤 실행은 그 봉을 확정 종가로 씁니다.
    ny_clock(2026, 10, 23, 16, 20)
    detail = paper_tasks.task_paper_price_history()

    assert store.read_timeseries(catalog.TS_EQUITY_CLOSE, "AAA") == [
        {"date": "2026-10-22", "value": 100.0},
        {"date": "2026-10-23", "value": 101.5},
    ]
    assert detail.startswith("2/2 티커") and "쓰지 않음" not in detail


def test_시세는_장중에도_오늘_봉을_그대로_둔다(store, monkeypatch, two_tickers, ny_clock):
    # 관측치는 장중 마지막 봉이 곧 지연 시세 관측치입니다. 이력의 확정 규칙을 여기에 적용하면 장중 스탑 판정이 멈춥니다.
    ny_clock(2026, 10, 23, 14, 0)
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="2y": {"tickers": {"AAA": _FRAME, "BBB": _FRAME}, "error": None})

    paper_tasks.task_paper_quotes()

    saved = store.read_snapshot(catalog.SNAP_PAPER_QUOTES).payload["tickers"]
    assert saved["AAA"]["dates"][-1] == "2026-10-23" and saved["AAA"]["close"][-1] == 101.0


def test_유니버스는_매핑표_티커와_PAPER_ETFS를_중복_없이_담는다():
    universe = paper_universe.tradable()

    assert len(universe) == len(set(universe))
    assert list(universe) == sorted(universe)
    assert set(indicators.PAPER_ETFS) <= set(universe)
    assert {ticker for ticker, _ in equities.EQUITY_MAP.values()} <= set(universe)
    # 후보 기관 CUSIP 매핑표의 티커(장외 라인 CFRHF 등 포함) — 트랙레코드가 덮으려면 10년 이력이, 신호로 사려면 관측치가 필요합니다.
    assert set(equities_cusip.TRACK_RECORD_CUSIPS.values()) <= set(universe)
    assert "CFRHF" in universe
    # 원화 종가(069500.KS)는 달러 장부의 매매 대상이 아닙니다 — equities.all_tickers()를 그대로 쓰면 섞입니다.
    assert not [ticker for ticker in universe if ticker.endswith(".KS")]
    assert paper_universe.history() == universe


def test_PAPER_ETFS는_명세_D8의_19개():
    assert len(indicators.PAPER_ETFS) == len(set(indicators.PAPER_ETFS)) == 19
    assert set(indicators.SECTOR_ETFS) <= set(indicators.PAPER_ETFS)
    assert {"SHY", "UUP", "GLD", "DBC", "TLT", "IEF", "SPY", "QQQ"} <= set(indicators.PAPER_ETFS)


def test_두_태스크가_속도군에_등록된다():
    assert tasks.TASKS_BY_NAME["paper_quotes"].speed == "slow"
    assert tasks.TASKS_BY_NAME["paper_price_history"].speed == "weekly"
    assert tasks.TASKS_BY_NAME["paper_quotes"].run is paper_tasks.task_paper_quotes
    assert tasks.TASKS_BY_NAME["paper_price_history"].run is paper_tasks.task_paper_price_history


def test_시세_저장본이_없으면_누락_데이터셋에_보인다(store):
    names = {entry["name"] for entry in store.missing_datasets()}
    assert catalog.SNAP_PAPER_QUOTES in names
