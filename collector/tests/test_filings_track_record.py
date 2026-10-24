"""
tests/test_filings_track_record.py
모의 매매 트랙레코드 후보 기관의 13F 30분기 수집(task_sec_13f_track_record, 설계 §4-0 ②).

고정하는 규칙
  1. 실행당 새로 내려받는 공시 수는 예산(TRACK_RECORD_FETCH_BUDGET) 안 — 다 쓰면 남은 기관은 다음 실행, 목록에 '예산 소진'.
  2. 저장본에 있는 공시는 다시 받지 않는다(reuse_stored) — 두 번째 실행은 목록만 보고 문서를 받지 않습니다.
  3. 목록은 count=100으로 묻고, 원본이 모자라고 쪽이 꽉 찼을 때만 start=로 다음 쪽.
  4. 선행 CIK는 마지막 보고 분기까지 쓰고 그 뒤만 지금 CIK — 겹치는 분기는 선행 CIK. 분기마다 filerCik.
  5. complete = 30분기가 찼거나 받을 수 있는 원본을 다 봄(예산 때문에 건너뛴 공시가 없을 때).
  6. 한 CIK라도 실패하면 그 기관의 저장본은 그대로, 목록에는 저장본의 값과 실패 사유.
  7. 후보 목록(sec.13f.track_record_index)은 이번에 못 돈 기관도 저장본의 값으로 담는다.
  8. q30에는 CUSIP 매핑표 티커(tickerSource "cusip")를 붙이고, 기존 q8 저장본 모양은 바뀌지 않는다.

외부 네트워크를 쓰지 않습니다 — EDGAR 목록·공시 색인·정보표 XML을 흉내 내는 가짜 세션(test_sec13f.py의 HTML 관례)이고,
공시 하나 받기(_collect_one_filing)는 진짜 코드가 그 가짜 문서를 파싱합니다. PostgreSQL이 필요합니다(store 픽스처).
"""
from __future__ import annotations

import calendar
from datetime import date, timedelta
from xml.sax.saxutils import escape

import pytest

from app import catalog, indicators, settings, tasks
from app.services import sec13f
from app.tasks import filings

A, B, B_OLD = "0000000101", "0000000202", "0000000303"
FILERS = (
    {"cik": A, "name": "가 기관 (Alpha)", "style": "가치·집중"},
    {"cik": B, "name": "나 기관 (Beta)", "style": "성장·테크", "predecessors": (B_OLD,)},
)


def _quarter_ends(latest: date, n: int) -> list[date]:
    ends = []
    year, month = latest.year, latest.month
    for _ in range(n):
        ends.append(date(year, month, calendar.monthrange(year, month)[1]))
        month -= 3
        if month <= 0:
            month += 12
            year -= 1
    return ends


def _info_table(rows: list[tuple[str, str, str, int]]) -> bytes:
    body = "".join(
        f"<infoTable><nameOfIssuer>{escape(name)}</nameOfIssuer><titleOfClass>{escape(cls)}</titleOfClass>"
        f"<cusip>{cusip}</cusip>"
        f"<value>{value}</value><shrsOrPrnAmt><sshPrnamt>100</sshPrnamt><sshPrnamtType>SH</sshPrnamtType>"
        "</shrsOrPrnAmt></infoTable>"
        for name, cls, cusip, value in rows
    )
    return ('<?xml version="1.0" encoding="UTF-8"?>'
            '<informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">'
            f"{body}</informationTable>").encode()


# 기본 보유: 이름 매핑표(AAPL) · CUSIP 매핑표(IVV — 'ISHARES TR'은 이름으로는 모름) · 어디에도 없음
_HOLDINGS = [("APPLE INC", "COM", "037833100", 600_000_000),
             ("ISHARES TR", "CORE S&P500 ETF", "464287200", 300_000_000),
             ("SOME OBSCURE HOLDINGS LLC", "COM", "999999109", 100_000_000)]


class _Response:
    def __init__(self, text: str = "", content: bytes = b"", status: int = 200):
        self.text = text
        self.content = content or text.encode()
        self.status = status

    def raise_for_status(self):
        if self.status >= 400:
            raise RuntimeError(f"{self.status} Server Error")


class FakeEdgar:
    """CIK별 공시 목록(최신순)·색인·정보표를 돌려주고 요청을 남기는 가짜 SEC 세션."""

    def __init__(self):
        self.rows: dict[str, list[dict]] = {}
        self.calls: list[tuple[str, dict]] = []
        self.broken: set[str] = set()

    def add_quarters(self, cik: str, latest: date, n: int, *, holdings=None, amendments_every: int = 0):
        """보고 분기 latest부터 n개 원본(제출은 분기말 45일 뒤). amendments_every면 그만큼마다 정정 공시 한 행."""
        for i, end in enumerate(_quarter_ends(latest, n)):
            self.add(cik, end + timedelta(days=45), end, holdings or _HOLDINGS)
            if amendments_every and (i + 1) % amendments_every == 0:
                self.add(cik, end + timedelta(days=46), end, holdings or _HOLDINGS, form="13F-HR/A")

    def add(self, cik: str, filed: date, report: date, holdings, form: str = "13F-HR"):
        rows = self.rows.setdefault(cik, [])
        accession = f"{cik}-{filed.isoformat()}-{len(rows)}"
        rows.append({"form": form, "filed": filed.isoformat(), "report": report.isoformat(),
                     "index": f"/Archives/edgar/data/{cik}/{accession}-index.htm",
                     "xml": f"/Archives/edgar/data/{cik}/{accession}/infotable.xml", "holdings": holdings})
        rows.sort(key=lambda r: r["filed"], reverse=True)

    def get(self, url, params=None, timeout=None):
        self.calls.append((url, dict(params or {})))
        if url == sec13f.EDGAR_BROWSE:
            rows = self.rows.get(params["CIK"], [])
            start, count = int(params.get("start", 0)), int(params["count"])
            body = "".join(
                f"<tr><td>{r['form']}</td><td><a href=\"{r['index']}\">Documents</a></td>"
                f"<td>desc</td><td>{r['filed']}</td><td>file</td></tr>"
                for r in rows[start:start + count]
            )
            return _Response("<html><body><table class=\"tableFile2\"><tr><th>Filings</th></tr>"
                             f"{body}</table></body></html>")
        if url in self.broken:
            return _Response(status=500)
        for rows in self.rows.values():
            for r in rows:
                if url == f"https://www.sec.gov{r['index']}":
                    return _Response(
                        f"<div class=\"infoHead\">Period of Report</div>\n<div class=\"info\">{r['report']}</div>"
                        "<table class=\"tableFile\"><tr><th>Seq</th></tr>"
                        f"<tr><td>2</td><td>INFORMATION TABLE</td><td><a href=\"{r['xml']}\">infotable.xml</a></td>"
                        "<td>INFORMATION TABLE</td></tr></table>")
                if url == f"https://www.sec.gov{r['xml']}":
                    return _Response(content=_info_table(r["holdings"]))
        return _Response(status=404)

    def listings(self, cik: str | None = None) -> list[dict]:
        return [p for url, p in self.calls if url == sec13f.EDGAR_BROWSE and (cik is None or p["CIK"] == cik)]

    def documents(self) -> list[str]:
        return [url for url, _p in self.calls if url != sec13f.EDGAR_BROWSE]

    def url_of(self, cik: str, report: str, kind: str = "xml") -> str:
        row = next(r for r in self.rows[cik] if r["report"] == report and r["form"] == "13F-HR")
        return f"https://www.sec.gov{row[kind]}"


@pytest.fixture()
def edgar(monkeypatch):
    monkeypatch.setenv("SEC_USER_AGENT", "test@example.com")
    monkeypatch.delenv("TRACK_RECORD_FETCH_BUDGET", raising=False)
    monkeypatch.setattr(indicators, "TRACK_RECORD_FILERS", FILERS)
    fake = FakeEdgar()
    monkeypatch.setattr(sec13f, "get_sec_session", lambda: fake)
    monkeypatch.setattr(sec13f, "sec_rate_limit", lambda: None)
    return fake


def _q30(store, cik: str) -> dict | None:
    snap = store.read_snapshot(catalog.snap_sec_13f(cik, catalog.TRACK_RECORD_QUARTERS))
    return snap.payload if snap else None


def _index(store) -> dict:
    return {entry["cik"]: entry for entry in store.read_snapshot(catalog.SNAP_TRACK_RECORD_INDEX).payload["filers"]}


def _standard(edgar: FakeEdgar):
    """가: 원본 32건. 나: 지금 CIK 원본 3건(2025-12-31~2026-06-30) + 옛 CIK 원본 10건(~2026-03-31, 겹침 2분기)."""
    edgar.add_quarters(A, date(2026, 6, 30), 32)
    edgar.add_quarters(B, date(2026, 6, 30), 3, holdings=[("HOWARD HUGHES HOLDINGS INC", "COM", "44267T102", 500_000_000)])
    edgar.add_quarters(B_OLD, date(2026, 3, 31), 10)


def test_후보_기관마다_30분기를_q30에_저장하고_목록을_남긴다(store, edgar):
    _standard(edgar)

    detail = filings.task_sec_13f_track_record()

    a = _q30(store, A)
    assert len(a["quarters"]) == 30 and a["complete"] is True
    assert a["quarters"][0]["reportDate"] == "2026-06-30"
    assert {a[k] for k in ("cik", "name", "style")} == {A, "가 기관 (Alpha)", "가치·집중"}
    assert a["predecessors"] == [] and a["fetchedThisRun"] == 30
    assert all(q["filerCik"] == A for q in a["quarters"])
    # q30에만 CUSIP 매핑표 티커 — 'ISHARES TR'(이름으로는 모름)이 IVV로 덮입니다.
    tickers = {h["name"]: (h["ticker"], h.get("tickerSource")) for h in a["quarters"][0]["holdings"]}
    assert tickers == {"APPLE INC": ("AAPL", None), "ISHARES TR": ("IVV", "cusip"),
                       "SOME OBSCURE HOLDINGS LLC": (None, None)}

    index = _index(store)
    assert list(index) == [A, B], "목록은 indicators의 순서입니다(처리 순서가 아님)"
    assert index[A] == {
        "cik": A, "name": "가 기관 (Alpha)", "style": "가치·집중", "predecessors": [], "storedQuarters": 30,
        "complete": True, "lastFilingDate": "2026-08-14", "lastReportDate": "2026-06-30",
        "updatedAt": index[A]["updatedAt"], "note": None,
    }
    assert index[A]["updatedAt"]
    payload = store.read_snapshot(catalog.SNAP_TRACK_RECORD_INDEX).payload
    assert payload["targetQuarters"] == 30 and payload["budget"] == settings.TRACK_RECORD_FETCH_BUDGET
    assert detail.startswith("2/2 기관 갱신, 새 공시 ")
    assert "완료 2곳" in detail


def test_선행_CIK는_마지막_보고_분기까지_쓰고_그_뒤만_지금_CIK다(store, edgar):
    _standard(edgar)

    filings.task_sec_13f_track_record()

    b = _q30(store, B)
    assert b["predecessors"] == [B_OLD]
    assert [(q["reportDate"], q["filerCik"]) for q in b["quarters"][:4]] == [
        ("2026-06-30", B), ("2026-03-31", B_OLD), ("2025-12-31", B_OLD), ("2025-09-30", B_OLD)]
    assert len(b["quarters"]) == 11
    # 겹치는 분기의 지금 CIK 공시(한 종목만)는 버렸습니다.
    assert b["quarters"][1]["holdings"][0]["name"] == "APPLE INC"
    # 두 CIK 모두 EDGAR에 더 오래된 원본이 없어 11분기로 완료입니다.
    assert b["complete"] is True
    assert _index(store)[B]["predecessors"] == [B_OLD]


def test_두_번째_실행은_저장본을_다시_받지_않는다(store, edgar):
    _standard(edgar)
    filings.task_sec_13f_track_record()
    first_documents = len(edgar.documents())
    assert first_documents > 0

    edgar.calls.clear()
    detail = filings.task_sec_13f_track_record()

    assert edgar.documents() == [], "목록만 보고 공시 문서는 받지 않아야 합니다"
    assert {p["CIK"] for p in edgar.listings()} == {A, B, B_OLD}
    assert _q30(store, A)["fetchedThisRun"] == 0 and len(_q30(store, A)["quarters"]) == 30
    assert "새 공시 0건" in detail


def test_새_분기가_나오면_그_공시만_받는다(store, edgar):
    _standard(edgar)
    filings.task_sec_13f_track_record()
    edgar.calls.clear()
    edgar.add(A, date(2026, 11, 14), date(2026, 9, 30), _HOLDINGS)

    filings.task_sec_13f_track_record()

    assert edgar.documents() == [edgar.url_of(A, "2026-09-30", "index"), edgar.url_of(A, "2026-09-30")]
    quarters = _q30(store, A)["quarters"]
    assert quarters[0]["reportDate"] == "2026-09-30" and len(quarters) == 30


def test_목록은_count_100으로_묻고_원본이_모자랄_때만_다음_쪽을_본다(store, edgar):
    # 가: 원본마다 정정 공시가 하나씩(64행) — 100행 한 쪽에서 원본 30건을 찾고 끝납니다.
    edgar.add_quarters(A, date(2026, 6, 30), 32, amendments_every=1)
    # 나: 원본 1건마다 정정 4건(125행) — 첫 쪽(100행)에 원본 20건뿐이라 start=100으로 나머지 5건을 봅니다.
    for end in _quarter_ends(date(2026, 6, 30), 25):
        edgar.add(B, end + timedelta(days=45), end, _HOLDINGS)
        for extra in range(1, 5):
            edgar.add(B, end + timedelta(days=45 + extra), end, _HOLDINGS, form="13F-HR/A")
    edgar.add_quarters(B_OLD, date(2019, 12, 31), 2)

    filings.task_sec_13f_track_record()

    assert [p.get("start") for p in edgar.listings(A)] == [None]
    assert [p.get("start") for p in edgar.listings(B)] == [None, 100]
    assert all(p["count"] == 100 for p in edgar.listings())
    assert len([q for q in _q30(store, B)["quarters"] if q["filerCik"] == B]) == 25


def test_예산을_다_쓰면_멈추고_남은_기관은_다음_실행이_이어_받는다(store, edgar, monkeypatch):
    _standard(edgar)
    monkeypatch.setenv("TRACK_RECORD_FETCH_BUDGET", "5")

    detail = filings.task_sec_13f_track_record()

    # 가(저장본 0, 목록 앞)부터 — 최신 5건만 받고, 나는 이번에 돌지 않았습니다.
    a = _q30(store, A)
    assert [q["reportDate"] for q in a["quarters"]] == [d.isoformat() for d in _quarter_ends(date(2026, 6, 30), 5)]
    assert a["complete"] is False and a["fetchedThisRun"] == 5
    assert _q30(store, B) is None
    assert {p["CIK"] for p in edgar.listings()} == {A}
    index = _index(store)
    assert index[A]["note"].startswith("예산 소진") and index[A]["complete"] is False
    assert index[B] == {"cik": B, "name": "나 기관 (Beta)", "style": "성장·테크", "predecessors": [B_OLD],
                        "storedQuarters": 0, "complete": False, "lastFilingDate": None, "lastReportDate": None,
                        "updatedAt": None, "note": "예산 소진 — 다음 실행에서 이어 받음"}
    assert store.read_snapshot(catalog.SNAP_TRACK_RECORD_INDEX).payload["budget"] == 5
    assert detail.startswith("1/2 기관 갱신, 새 공시 5건, 완료 0곳")
    assert "예산 소진" in detail

    # 다음 실행: 저장 분기가 적은 나(0)부터 — 나에서 예산을 다 써 가는 이번에 돌지 않고 저장본 그대로입니다.
    edgar.calls.clear()
    filings.task_sec_13f_track_record()
    assert edgar.listings()[0]["CIK"] == B
    assert A not in {p["CIK"] for p in edgar.listings()}
    b = _q30(store, B)
    assert b["complete"] is False and b["fetchedThisRun"] == 5
    assert len(_q30(store, A)["quarters"]) == 5
    assert _index(store)[A]["note"].startswith("예산 소진") and _index(store)[A]["storedQuarters"] == 5


def test_예산이_넉넉하면_여러_번에_나눠_받아도_결국_완료된다(store, edgar, monkeypatch):
    _standard(edgar)
    monkeypatch.setenv("TRACK_RECORD_FETCH_BUDGET", "12")

    for _ in range(6):
        filings.task_sec_13f_track_record()

    a, b = _q30(store, A), _q30(store, B)
    assert a["complete"] is True and len(a["quarters"]) == 30
    assert b["complete"] is True and len(b["quarters"]) == 11
    reports = [q["reportDate"] for q in a["quarters"]]
    assert reports == sorted(reports, reverse=True) and len(set(reports)) == 30, "구멍·중복 없이 이어 붙여야 합니다"


def test_한_CIK라도_실패하면_그_기관은_저장본을_유지한다(store, edgar):
    _standard(edgar)
    filings.task_sec_13f_track_record()
    before = _q30(store, B)
    # 옛 CIK의 공시 하나가 받아지지 않는 상태 + 나의 저장본에서 그 분기를 지워 다시 받게 합니다.
    edgar.broken.add(edgar.url_of(B_OLD, "2025-09-30"))
    trimmed = {**before, "quarters": [q for q in before["quarters"] if q["reportDate"] != "2025-09-30"]}
    store.put_snapshot(catalog.snap_sec_13f(B, 30), trimmed)
    edgar.add(A, date(2026, 11, 14), date(2026, 9, 30), _HOLDINGS)

    detail = filings.task_sec_13f_track_record()

    assert _q30(store, B) == trimmed, "실패한 기관은 덮지 않습니다"
    assert _q30(store, A)["quarters"][0]["reportDate"] == "2026-09-30", "다른 기관은 그대로 갱신됩니다"
    entry = _index(store)[B]
    assert entry["note"].startswith("수집 실패(저장본 유지)") and B_OLD in entry["note"]
    assert entry["storedQuarters"] == 10
    assert "1/2 기관 갱신" in detail and "실패 1곳" in detail


def test_전부_실패하면_EmptyResult이고_아무것도_덮지_않는다(store, edgar):
    # 목록은 오지만 모든 공시 문서가 404입니다.
    edgar.add_quarters(A, date(2026, 6, 30), 2)
    edgar.add_quarters(B, date(2026, 6, 30), 2)
    edgar.add_quarters(B_OLD, date(2025, 12, 31), 2)
    edgar.broken.update(url for rows in edgar.rows.values() for r in rows for url in [f"https://www.sec.gov{r['index']}"])

    with pytest.raises(tasks.EmptyResult):
        filings.task_sec_13f_track_record()

    assert _q30(store, A) is None and _q30(store, B) is None
    assert store.read_snapshot(catalog.SNAP_TRACK_RECORD_INDEX) is None


def test_SEC_USER_AGENT가_없으면_한_번만_말하고_EmptyResult(store, edgar, monkeypatch):
    monkeypatch.delenv("SEC_USER_AGENT")

    with pytest.raises(tasks.EmptyResult, match="SEC_USER_AGENT"):
        filings.task_sec_13f_track_record()

    assert edgar.calls == []


def test_쓸_수_없는_공시를_건너뛰어도_원본을_다_봤으면_완료다(store, edgar):
    """보유가 옵션뿐인 공시(UNUSABLE_FILING)는 건너뜁니다. 원본 30건을 다 봤으면 29분기여도 '수집 중'에 묶이지 않습니다."""
    edgar.add_quarters(A, date(2026, 6, 30), 32)
    edgar.add_quarters(B, date(2026, 6, 30), 1)
    edgar.add_quarters(B_OLD, date(2026, 3, 31), 1)
    options_only = next(r for r in edgar.rows[A] if r["report"] == "2025-06-30")
    options_only["holdings"] = []

    filings.task_sec_13f_track_record()

    a = _q30(store, A)
    assert len(a["quarters"]) == 29 and a["complete"] is True


def test_예산_설정은_1에서_2000으로_자르고_잘못된_값은_기본값(monkeypatch):
    for raw, expected in (("", 400), ("abc", 400), ("0", 1), ("-5", 1), ("5000", 2000), (" 250 ", 250)):
        monkeypatch.setenv("TRACK_RECORD_FETCH_BUDGET", raw)
        assert settings.track_record_fetch_budget() == expected, raw
    monkeypatch.delenv("TRACK_RECORD_FETCH_BUDGET")
    assert settings.track_record_fetch_budget() == 400


def test_weekly에_등록되고_목록이_없으면_누락_데이터셋에_보인다(store):
    task = tasks.TASKS_BY_NAME["sec_13f_track_record"]
    assert task.speed == "weekly" and task.run is filings.task_sec_13f_track_record
    assert catalog.SNAP_TRACK_RECORD_INDEX in {entry["name"] for entry in store.missing_datasets()}


def test_후보_목록은_명세_D21의_27곳이고_선행_CIK가_둘이다():
    real = indicators.TRACK_RECORD_FILERS
    assert len(real) == len({f["cik"] for f in real}) == 27
    assert all(len(f["cik"]) == 10 and f["cik"].isdigit() for f in real)
    assert {f["style"] for f in real} == {"가치·집중", "성장·테크", "매크로"}
    assert {f["cik"]: f["predecessors"] for f in real if f.get("predecessors")} == {
        "0002026053": ("0001336528",), "0001489933": ("0001079114",)}


def test_기존_sec_13f_q8_저장본_모양은_그대로다(store, monkeypatch):
    """collect_13f가 진행 정보(fetchedFilings 등)를 더 돌려줘도 기존 13F 화면의 저장본에는 싣지 않습니다."""
    monkeypatch.setenv("SEC_USER_AGENT", "test@example.com")
    monkeypatch.setattr(tasks.indicators, "INSTITUTIONS",
                        [{"key": "demo", "name": "데모 기관", "cik": "0001067983", "desc": ""}], raising=False)

    def fake_collect(cik, quarters, previous_quarters=None):
        return {"cik": cik, "error": None, "fetchedFilings": 1, "budgetExhausted": False, "linksFound": 1,
                "noMoreFilings": True,
                "quarters": [{"filingDate": "2026-08-14", "reportDate": "2026-06-30", "totalValue": 1.0,
                              "holdings": [{"name": "ISHARES TR", "class": "CORE S&P500 ETF", "cusip": "464287200",
                                            "value": 1.0, "shares": 1.0, "weight": 100.0}]}]}

    monkeypatch.setattr(tasks.sec_service, "collect_13f", fake_collect)

    tasks.task_sec_13f()

    q8 = store.read_snapshot(catalog.snap_sec_13f("0001067983", 8)).payload
    assert set(q8) == {"cik", "error", "quarters"}
    holding = q8["quarters"][0]["holdings"][0]
    assert holding["ticker"] is None and "tickerSource" not in holding, "q8에는 CUSIP 매핑표를 쓰지 않습니다"
