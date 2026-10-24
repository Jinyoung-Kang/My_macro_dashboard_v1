"""
tests/test_sec13f.py
SEC EDGAR 13F 목록·문서 파싱.

네트워크를 쓰지 않습니다. 목록 HTML은 EDGAR 'browse-edgar?action=getcompany&type=13F-HR'
페이지의 tableFile2 구조를 그대로 흉내 냅니다.
"""
from __future__ import annotations

import pytest

from app.services import sec13f


def _row(filing_type: str, href: str, filed: str) -> str:
    return (
        f"<tr><td>{filing_type}</td>"
        f"<td><a href=\"{href}\">Documents</a></td>"
        f"<td>desc</td><td>{filed}</td><td>file</td></tr>"
    )


def _listing(*rows: str) -> str:
    return (
        "<html><body><table class=\"tableFile2\">"
        "<tr><th>Filings</th><th>Format</th><th>Description</th><th>Filing Date</th><th>File</th></tr>"
        + "".join(rows)
        + "</table></body></html>"
    )


def test_정정_공시는_분기로_세지_않는다():
    """
    EDGAR의 type=13F-HR 목록에는 정정(13F-HR/A)도 섞여 옵니다. 그걸 분기로 세면
    (1) 같은 reportDate가 두 번 들어가 q8이 실제로는 7개 분기가 되고, (2) 가장
    최근 공시가 '신규 보유분만' 담은 부분 정정이면 q1이 부분 포트폴리오가 되어
    비중이 그 부분 합계로 정규화됩니다. 원본 13F-HR만 분기로 씁니다.
    """
    html = _listing(
        _row("13F-HR/A", "/Archives/a1-index.htm", "2026-08-20"),
        _row("13F-HR", "/Archives/q2-index.htm", "2026-08-14"),
        _row("13F-HR/A", "/Archives/a2-index.htm", "2026-06-02"),
        _row("13F-HR", "/Archives/q1-index.htm", "2026-05-15"),
        _row("SC 13G", "/Archives/other.htm", "2026-04-01"),
    )

    links = sec13f._parse_filing_links(html, 8)

    assert links == [
        ("2026-08-14", "https://www.sec.gov/Archives/q2-index.htm"),
        ("2026-05-15", "https://www.sec.gov/Archives/q1-index.htm"),
    ]


def test_max_quarters만큼만_고른다():
    html = _listing(*[
        _row("13F-HR", f"/Archives/q{i}-index.htm", f"2026-0{i}-15") for i in range(1, 6)
    ])
    assert len(sec13f._parse_filing_links(html, 2)) == 2


def test_같은_종목의_여러_클래스는_따로_남는다():
    """CUSIP·종류가 다르면 같은 이름이라도 다른 보유분입니다(백엔드가 CUSIP으로 비교)."""
    xml = b"""<?xml version="1.0"?>
    <informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">
      <infoTable><nameOfIssuer>ALPHABET INC</nameOfIssuer><titleOfClass>CL A</titleOfClass>
        <cusip>02079K305</cusip><value>1000</value><shrsOrPrnAmt><sshPrnamt>10</sshPrnamt></shrsOrPrnAmt></infoTable>
      <infoTable><nameOfIssuer>ALPHABET INC</nameOfIssuer><titleOfClass>CL C</titleOfClass>
        <cusip>02079K107</cusip><value>2000</value><shrsOrPrnAmt><sshPrnamt>20</sshPrnamt></shrsOrPrnAmt></infoTable>
      <infoTable><nameOfIssuer>ALPHABET INC</nameOfIssuer><titleOfClass>CL A</titleOfClass>
        <cusip>02079K305</cusip><value>500</value><shrsOrPrnAmt><sshPrnamt>5</sshPrnamt></shrsOrPrnAmt></infoTable>
    </informationTable>"""

    holdings = sec13f._parse_information_table(xml, "2026-08-14")

    by_cusip = {h["cusip"]: h for h in holdings}
    assert set(by_cusip) == {"02079K305", "02079K107"}
    assert by_cusip["02079K305"]["value"] == 1500.0 and by_cusip["02079K305"]["shares"] == 15.0


# ---------------------------------------------------------------- QA-014
# 보고 분기를 제출 '월'로 추정해, 3·6·9·12월에 낸 공시가 아직 끝나지 않은 분기로 붙었습니다
# (예: 12월 2일 제출 → 같은 해 12-31). 늦게 낸 공시가 다음 분기 이름을 달면 백엔드의
# 분기별 중복 제거(dedupeByReportDate)가 진짜 다음 분기 공시와 겹쳐 한 분기를 버릴 수 있습니다.
# EDGAR 공시 색인 페이지에는 'Period of Report'가 있으므로 그 값을 씁니다.

_INDEX_HTML = (
    "<div class=\"formGrouping\">"
    "<div class=\"infoHead\">Filing Date</div>\n<div class=\"info\">2026-12-02</div>"
    "<div class=\"infoHead\">Accepted</div>\n<div class=\"info\">2026-12-02 16:05:04</div>"
    "</div><div class=\"formGrouping\">"
    "<div class=\"infoHead\">Period of Report</div>\n<div class=\"info\">2026-09-30</div>"
    "</div>"
)


def test_QA014_보고_분기는_공시_색인의_Period_of_Report를_쓴다():
    assert sec13f._parse_period_of_report(_INDEX_HTML) == "2026-09-30"


def test_QA014_추정은_제출일_전에_끝난_가장_최근_분기말이다():
    assert sec13f._estimate_report_date("2026-12-02") == "2026-09-30"
    assert sec13f._estimate_report_date("2026-03-20") == "2025-12-31"
    assert sec13f._estimate_report_date("2026-08-14") == "2026-06-30"


class _FakeResponse:
    text = "<html></html>"

    def raise_for_status(self):
        return None


class _FakeSession:
    def get(self, *_a, **_k):
        return _FakeResponse()


def _quarter(filing_date: str, report_date: str) -> dict:
    return {"filingDate": filing_date, "reportDate": report_date, "totalValue": 100.0,
            "holdings": [{"name": "APPLE INC", "cusip": "037833100", "class": "COM",
                          "value": 100.0, "shares": 1.0, "weight": 100.0}]}


@pytest.fixture()
def three_filings_middle_fails(monkeypatch):
    monkeypatch.setattr(sec13f, "get_sec_session", lambda: _FakeSession())
    monkeypatch.setattr(sec13f, "sec_rate_limit", lambda: None)
    monkeypatch.setattr(sec13f, "_parse_filing_links", lambda html, n: [
        ("2026-08-14", "u3"), ("2026-05-15", "u2"), ("2026-02-14", "u1")])
    done = {"2026-08-14": _quarter("2026-08-14", "2026-06-30"),
            "2026-05-15": None,                                   # 이 공시만 받지 못함
            "2026-02-14": _quarter("2026-02-14", "2025-12-31")}
    monkeypatch.setattr(sec13f, "_collect_one_filing", lambda session, filing_date, url: done[filing_date])


def test_QA031_공시_하나를_받지_못하면_분기가_밀린_결과를_내지_않는다(three_filings_middle_fails):
    """
    가운데 분기를 건너뛰면 0번(2026Q2)과 1번(2025Q4)이 '최신 대 직전'으로 비교됩니다. 직전 저장본에도 그 공시가 없으면
    실패로 보고(저장본 유지), 분기가 밀린 결과로 덮지 않습니다.
    """
    result = sec13f.collect_13f("0001067983", 3)

    assert result["quarters"] == []
    assert "받지 못" in result["error"]


def test_QA031_받지_못한_공시는_직전_저장본의_같은_공시를_이어받는다(three_filings_middle_fails):
    previous = [_quarter("2026-05-15", "2026-03-31")]

    result = sec13f.collect_13f("0001067983", 3, previous_quarters=previous)

    assert [q["reportDate"] for q in result["quarters"]] == ["2026-06-30", "2026-03-31", "2025-12-31"]
    assert result["quarters"][1]["isStale"] is True
    assert result["error"] is None



def test_QA059_정보표가_없는_공시는_건너뛰고_기관을_실패로_만들지_않는다(monkeypatch):
    """
    정보표 XML이 없거나 롱 보유가 0건(옵션 행뿐)인 공시는 다시 받아도 같습니다. QA-031 뒤로 이런 공시가 직전 저장본에
    없으면 그 기관 전체가 실패로 끝나 새 분기 공시가 나와도 영영 갱신되지 않았습니다. 예전처럼 그 공시만 건너뜁니다.
    """
    real = sec13f._collect_one_filing
    monkeypatch.setattr(sec13f, "get_sec_session", lambda: _FakeSession())
    monkeypatch.setattr(sec13f, "sec_rate_limit", lambda: None)
    monkeypatch.setattr(sec13f, "_parse_filing_links", lambda html, n: [("2026-08-14", "u2"), ("2026-05-15", "u1")])
    fresh = _quarter("2026-08-14", "2026-06-30")
    monkeypatch.setattr(sec13f, "_collect_one_filing", lambda session, filing_date, url:
                        fresh if filing_date == "2026-08-14" else real(session, filing_date, url))

    result = sec13f.collect_13f("0001067983", 2)

    assert result["error"] is None
    assert [q["reportDate"] for q in result["quarters"]] == ["2026-06-30"]

_INFO_TABLE = b"""<?xml version="1.0" encoding="UTF-8"?>
<informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">
  <infoTable><nameOfIssuer>APPLE INC</nameOfIssuer><titleOfClass>COM</titleOfClass><cusip>037833100</cusip>
    <value>1000000</value><shrsOrPrnAmt><sshPrnamt>5000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt></infoTable>
  <infoTable><nameOfIssuer>APPLE INC</nameOfIssuer><titleOfClass>COM</titleOfClass><cusip>037833100</cusip>
    <value>4000000</value><shrsOrPrnAmt><sshPrnamt>20000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt>
    <putCall>Put</putCall></infoTable>
  <infoTable><nameOfIssuer>TESLA INC</nameOfIssuer><titleOfClass>NOTE 2.000% 5/1</titleOfClass><cusip>88160RAG6</cusip>
    <value>2000000</value><shrsOrPrnAmt><sshPrnamt>1500000</sshPrnamt><sshPrnamtType>PRN</sshPrnamtType></shrsOrPrnAmt></infoTable>
</informationTable>"""


def test_QA033_옵션_행은_보유가_아니고_채권_원금은_주식_수가_아니다():
    """
    13F 정보표의 putCall 행은 옵션(기초자산 평가액)이라 롱 보유가 아닙니다. 합치면 풋이 보유로 보이고 비중 분모가
    부풉니다(추적 기관 중 사이언·브리지워터 등이 보고). PRN 행의 sshPrnamt는 채권 원금이라 주식 수로 쓰지 않습니다.
    """
    holdings = {h["name"]: h for h in sec13f._parse_information_table(_INFO_TABLE, "2026-08-14")}

    assert holdings["APPLE INC"]["value"] == 1_000_000
    assert holdings["APPLE INC"]["shares"] == 5000
    assert holdings["TESLA INC"]["value"] == 2_000_000
    assert holdings["TESLA INC"]["shares"] is None


# ---------------------------------------------------------------- 모의 매매 트랙레코드(30분기) 인자
# collect_13f(…, reuse_stored, list_count, fetch_budget)와 chain_predecessors. 기본값(기존 13F 화면 q8)의 동작은 위 테스트들이
# 고정합니다. 공시 목록은 _row/_listing으로 만들고, 공시 하나 받기(_collect_one_filing)는 바꿔 끼워 횟수를 셉니다.

class _ListingResponse:
    def __init__(self, text: str):
        self.text = text

    def raise_for_status(self):
        return None


class _PagedListing:
    """browse-edgar 목록을 count·start대로 잘라 돌려주는 가짜 세션. 받은 params를 남깁니다."""

    def __init__(self, rows: list[str], fail_pages: tuple[int, ...] = ()):
        self.rows = rows
        self.fail_pages = fail_pages
        self.params: list[dict] = []

    def get(self, url, params=None, timeout=None):
        assert url == sec13f.EDGAR_BROWSE
        self.params.append(dict(params))
        start = int(params.get("start", 0))
        if start // int(params["count"]) in self.fail_pages:
            raise RuntimeError("503 Server Error")
        return _ListingResponse(_listing(*self.rows[start:start + int(params["count"])]))


def _originals(n: int, *, amendments_every: int = 0) -> list[str]:
    """최신순 원본 n건(제출일 2026-08-14부터 하루씩 앞으로). amendments_every면 원본 그만큼마다 정정 공시 한 행을 끼웁니다."""
    from datetime import date, timedelta

    rows: list[str] = []
    for i in range(n):
        filed = (date(2026, 8, 14) - timedelta(days=i)).isoformat()
        rows.append(_row("13F-HR", f"/Archives/o{i}-index.htm", filed))
        if amendments_every and (i + 1) % amendments_every == 0:
            rows.append(_row("13F-HR/A", f"/Archives/a{i}-index.htm", filed))
    return rows


@pytest.fixture()
def fake_edgar(monkeypatch):
    """공시 목록 세션을 바꿔 끼우고, 공시 하나 받기는 제출일로 분기를 만들어 돌려주며 받은 제출일을 셉니다."""
    state = {"session": None, "downloaded": [], "fail": set()}

    def install(rows, **kwargs):
        state["session"] = _PagedListing(rows, **kwargs)
        return state["session"]

    def one_filing(session, filing_date, url):
        state["downloaded"].append(filing_date)
        if filing_date in state["fail"]:
            return None
        return _quarter(filing_date, sec13f._estimate_report_date(filing_date))

    monkeypatch.setattr(sec13f, "get_sec_session", lambda: state["session"])
    monkeypatch.setattr(sec13f, "sec_rate_limit", lambda: None)
    monkeypatch.setattr(sec13f, "_collect_one_filing", one_filing)
    state["install"] = install
    return state


def test_기본_호출도_진행_정보를_싣고_결과는_같다(fake_edgar):
    session = fake_edgar["install"](_originals(3))

    result = sec13f.collect_13f("0001067983", 8)

    assert session.params == [{"action": "getcompany", "CIK": "0001067983", "type": "13F-HR", "dateb": "",
                               "owner": "include", "count": 40}], "첫 쪽 요청은 예전과 같아야 합니다(start 없음)"
    assert [q["filingDate"] for q in result["quarters"]] == ["2026-08-14", "2026-08-13", "2026-08-12"]
    assert {k: result[k] for k in ("fetchedFilings", "budgetExhausted", "linksFound", "noMoreFilings")} == {
        "fetchedFilings": 3, "budgetExhausted": False, "linksFound": 3, "noMoreFilings": True}


def test_기본_호출은_쪽이_꽉_차도_다음_쪽을_보지_않는다(fake_edgar):
    """
    기존 13F 화면(q8, 인자 3개)의 목록 요청은 예전처럼 40행 한 번입니다(단계 1b 계약 A-2) — 정정 공시가 많아 40행에 원본이
    8건 없어도 다음 쪽(start=40)을 묻지 않습니다. 쪽 넘기기는 list_count를 준 호출(트랙레코드)만.
    """
    # 원본 5건 + 정정 40건(45행) — 첫 쪽(40행)이 꽉 찼고 원본은 5건뿐입니다.
    session = fake_edgar["install"](_originals(5) + [
        _row("13F-HR/A", f"/Archives/x{i}-index.htm", "2026-01-01") for i in range(40)])

    result = sec13f.collect_13f("1067983", 8, None)

    assert session.params == [{"action": "getcompany", "CIK": "0001067983", "type": "13F-HR", "dateb": "",
                               "owner": "include", "count": 40}], "기존 호출의 목록 요청은 한 번"
    assert result["linksFound"] == 5 and len(result["quarters"]) == 5
    assert result["noMoreFilings"] is False, "다음 쪽을 보지 않았으니 '더 없음'이라 적지 않습니다"
    assert all("filingUrl" not in q for q in result["quarters"]), "q8 분기 모양은 그대로"


def test_쪽이_꽉_찼고_원본이_모자라면_start로_다음_쪽을_본다(fake_edgar):
    """정정 공시가 섞여 한 쪽(count행)에 원본이 max_quarters만큼 없으면 다음 쪽을 봅니다. 찾으면 더 넘기지 않습니다."""
    session = fake_edgar["install"](_originals(10, amendments_every=1))   # 원본·정정 번갈아 20행

    result = sec13f.collect_13f("1067983", 6, list_count=4)

    assert [p.get("start") for p in session.params] == [None, 4, 8]
    assert all(p["count"] == 4 for p in session.params)
    assert result["linksFound"] == 6 and len(result["quarters"]) == 6
    assert result["noMoreFilings"] is False


def test_마지막_쪽이_덜_찼으면_더_오래된_원본이_없다(fake_edgar):
    session = fake_edgar["install"](_originals(5))

    result = sec13f.collect_13f("1067983", 30, list_count=4)

    assert [p.get("start") for p in session.params] == [None, 4]
    assert result["linksFound"] == 5
    assert result["noMoreFilings"] is True


def test_다음_쪽은_세_쪽까지만_보고_실패하면_찾은_공시로_간다(fake_edgar):
    session = fake_edgar["install"](_originals(20))

    result = sec13f.collect_13f("1067983", 30, list_count=4)
    assert [p.get("start") for p in session.params] == [None, 4, 8]
    assert result["linksFound"] == 12 and result["noMoreFilings"] is False

    fake_edgar["install"](_originals(20), fail_pages=(1,))
    result = sec13f.collect_13f("1067983", 30, list_count=4)
    assert result["error"] is None
    assert result["linksFound"] == 4 and result["noMoreFilings"] is False, "모르는 것을 '더 없음'으로 적지 않습니다"


def test_reuse_stored는_저장본의_같은_공시를_다시_받지_않는다(fake_edgar):
    fake_edgar["install"](_originals(3))
    stored = [{**_quarter("2026-08-13", "2026-06-30"), "ticker": "kept"},
              {**_quarter("2026-08-12", "2026-06-30"), "isStale": True}]   # 이어받은 값은 다시 받아 봅니다

    result = sec13f.collect_13f("1067983", 30, stored, reuse_stored=True)

    assert fake_edgar["downloaded"] == ["2026-08-14", "2026-08-12"]
    assert result["quarters"][1] is stored[0]
    assert "isStale" not in result["quarters"][2]
    assert result["fetchedFilings"] == 2


def test_reuse_stored가_없으면_예전처럼_전부_다시_받는다(fake_edgar):
    fake_edgar["install"](_originals(2))

    sec13f.collect_13f("1067983", 8, [_quarter("2026-08-13", "2026-06-30")])

    assert fake_edgar["downloaded"] == ["2026-08-14", "2026-08-13"]


def test_같은_날_낸_원본이_둘이면_filingUrl_없는_저장본은_쓰지_않고_다시_받는다(fake_edgar):
    """filingUrl을 적기 전의 저장본은 제출일로만 찾을 수 있어, 같은 날 두 건이면 어느 공시인지 몰라 다시 받습니다."""
    fake_edgar["install"]([_row("13F-HR", "/Archives/b-index.htm", "2026-08-14"),
                           _row("13F-HR", "/Archives/a-index.htm", "2026-08-14")])

    result = sec13f.collect_13f("1067983", 30, [_quarter("2026-08-14", "2026-06-30")], reuse_stored=True)

    assert fake_edgar["downloaded"] == ["2026-08-14", "2026-08-14"]
    assert [q["filingUrl"] for q in result["quarters"]] == [
        "https://www.sec.gov/Archives/b-index.htm", "https://www.sec.gov/Archives/a-index.htm"], "다음 실행부터는 문서로 찾음"


def _stored(filed: str, report: str, href: str, **extra) -> dict:
    return {**_quarter(filed, report), "filingUrl": f"https://www.sec.gov{href}", **extra}


def test_reuse_stored는_filingUrl로_같은_날_낸_두_공시도_다시_받지_않는다(fake_edgar):
    """밀린 분기를 같은 날 두 건 낸 기관(리뷰 1 결함 2) — 문서 URL로 저장본을 알아봐 실행마다 다시 받지 않습니다."""
    fake_edgar["install"]([_row("13F-HR", "/Archives/b-index.htm", "2026-05-15"),
                           _row("13F-HR", "/Archives/a-index.htm", "2026-05-15")])
    stored = [_stored("2026-05-15", "2026-03-31", "/Archives/b-index.htm"),
              _stored("2026-05-15", "2025-12-31", "/Archives/a-index.htm")]

    result = sec13f.collect_13f("1067983", 30, stored, reuse_stored=True, list_count=100)

    assert fake_edgar["downloaded"] == []
    assert result["quarters"][0] is stored[0] and result["quarters"][1] is stored[1]
    assert result["fetchedFilings"] == 0 and result["budgetExhausted"] is False


def test_예산이_바닥나도_같은_날_낸_공시의_저장본은_결과에_남는다(fake_edgar):
    """
    리뷰 1 결함 2의 재현: 저장본 4분기 중 두 분기가 2026-05-15 같은 날 공시이고 새 공시 1건이 생겼는데 예산이 1건이면,
    예전에는 같은 날 두 분기를 '다시 받아야 할 공시'로 쳐 예산이 바닥난 뒤 결과에서 빼, 이번 저장이 분기 중간에 구멍을 냈습니다.
    """
    fake_edgar["install"]([
        _row("13F-HR", "/Archives/new-index.htm", "2026-11-14"),
        _row("13F-HR", "/Archives/q3-index.htm", "2026-08-14"),
        _row("13F-HR", "/Archives/q2-index.htm", "2026-05-15"),
        _row("13F-HR", "/Archives/q1-index.htm", "2026-05-15"),
        _row("13F-HR", "/Archives/q0-index.htm", "2025-11-14"),
    ])
    stored = [_stored("2026-08-14", "2026-06-30", "/Archives/q3-index.htm"),
              _stored("2026-05-15", "2026-03-31", "/Archives/q2-index.htm"),
              _stored("2026-05-15", "2025-12-31", "/Archives/q1-index.htm"),
              _stored("2025-11-14", "2025-09-30", "/Archives/q0-index.htm")]

    result = sec13f.collect_13f("1067983", 30, stored, reuse_stored=True, list_count=100, fetch_budget=1)

    assert fake_edgar["downloaded"] == ["2026-11-14"]
    assert [q["reportDate"] for q in result["quarters"]] == [
        "2026-09-30", "2026-06-30", "2026-03-31", "2025-12-31", "2025-09-30"]
    assert result["budgetExhausted"] is False


def test_예산이_바닥나면_이어받아_둔_같은_날_공시도_결과에_남긴다(fake_edgar):
    """이어받아 둔(isStale) 같은 날 공시 둘 — filingUrl이 있든 없든(옛 저장본) 예산이 없으면 그대로 남깁니다."""
    rows = [_row("13F-HR", "/Archives/q2-index.htm", "2026-05-15"),
            _row("13F-HR", "/Archives/q1-index.htm", "2026-05-15")]

    fake_edgar["install"](rows)
    stored = [_stored("2026-05-15", "2026-03-31", "/Archives/q2-index.htm", isStale=True),
              _stored("2026-05-15", "2025-12-31", "/Archives/q1-index.htm", isStale=True)]
    result = sec13f.collect_13f("1067983", 30, stored, reuse_stored=True, list_count=100, fetch_budget=0)
    assert fake_edgar["downloaded"] == []
    assert result["quarters"] == stored and result["budgetExhausted"] is True

    fake_edgar["install"](rows)
    legacy = [_quarter("2026-05-15", "2026-03-31"), _quarter("2026-05-15", "2025-12-31")]
    result = sec13f.collect_13f("1067983", 30, legacy, reuse_stored=True, list_count=100, fetch_budget=0)
    assert result["quarters"] == legacy, "옛 저장본은 어느 공시인지 몰라도 두 분기를 한 번씩 남깁니다"

    # 같은 날의 한 건은 받았고 다른 한 건에서 예산이 바닥남 — 받은 분기와 겹치지 않는 옛 저장본만 남깁니다.
    fake_edgar["install"](rows)
    fake_edgar["downloaded"].clear()
    result = sec13f.collect_13f("1067983", 30, legacy, reuse_stored=True, list_count=100, fetch_budget=1)
    assert fake_edgar["downloaded"] == ["2026-05-15"]
    fetched_report = result["quarters"][0]["reportDate"]
    assert sorted(q["reportDate"] for q in result["quarters"]) == sorted(
        {fetched_report, *(q["reportDate"] for q in legacy)})


def test_예산을_다_쓰면_내려받을_공시만_건너뛰고_저장본은_이어_쓴다(fake_edgar):
    """
    예산은 최신 공시부터 씁니다. 멈춘 뒤에도 저장본에 있는 오래된 분기는 결과에 남겨야 합니다 — 빠지면 이번 저장이 그 분기를
    지우고 다음 실행이 다시 받습니다.
    """
    fake_edgar["install"](_originals(4))
    stored = [_quarter("2026-08-11", "2026-06-30")]

    result = sec13f.collect_13f("1067983", 30, stored, reuse_stored=True, fetch_budget=1)

    assert fake_edgar["downloaded"] == ["2026-08-14"]
    assert [q["filingDate"] for q in result["quarters"]] == ["2026-08-14", "2026-08-11"]
    assert result["budgetExhausted"] is True and result["fetchedFilings"] == 1
    assert result["error"] is None


def test_예산이_0이고_저장본도_없으면_실패가_아니라_빈_결과다(fake_edgar):
    fake_edgar["install"](_originals(2))

    result = sec13f.collect_13f("1067983", 30, None, reuse_stored=True, fetch_budget=0)

    assert fake_edgar["downloaded"] == []
    assert result["error"] is None and result["quarters"] == [] and result["budgetExhausted"] is True


def test_받지_못한_공시가_있으면_실패지만_쓴_예산은_싣는다(fake_edgar):
    fake_edgar["install"](_originals(3))
    fake_edgar["fail"].add("2026-08-13")

    result = sec13f.collect_13f("1067983", 30, None, reuse_stored=True, fetch_budget=10)

    assert result["quarters"] == [] and "받지 못" in result["error"]
    assert result["fetchedFilings"] == 3


def test_목록_행_수는_머리_행을_빼고_정정_공시도_센다():
    html = _listing(_row("13F-HR", "/a", "2026-08-14"), _row("13F-HR/A", "/b", "2026-08-13"))
    assert sec13f._listing_row_count(html) == 2
    assert sec13f._listing_row_count("<html></html>") == 0


def _q(report: str, filed: str, cik: str) -> dict:
    return {"filingDate": filed, "reportDate": report, "filerCik": cik, "holdings": []}


def test_선행_CIK는_마지막_보고_분기까지_쓰고_겹치는_분기는_선행_CIK다():
    """퍼싱 스퀘어: 새 CIK(PSI)가 2025-06-30~2026-03-31에 한 종목만 신고하는 동안 펀드 전체는 옛 CIK(PSCM)가 신고했습니다."""
    current = [_q("2026-06-30", "2026-08-14", "new"), _q("2026-03-31", "2026-05-15", "new"),
               _q("2025-12-31", "2026-02-14", "new")]
    old = [_q("2026-03-31", "2026-05-14", "old"), _q("2025-12-31", "2026-02-13", "old"),
           _q("2025-09-30", "2025-11-14", "old")]

    chained = sec13f.chain_predecessors(current, [old], 30)

    assert [(q["reportDate"], q["filerCik"]) for q in chained] == [
        ("2026-06-30", "new"), ("2026-03-31", "old"), ("2025-12-31", "old"), ("2025-09-30", "old")]


def test_같은_보고_분기는_먼저_낸_공시이고_최대_분기_수로_자른다():
    current = [_q("2026-06-30", "2026-09-01", "c"), _q("2026-06-30", "2026-08-14", "c"),
               _q("2026-03-31", "2026-05-15", "c"), _q("2025-12-31", "2026-02-14", "c")]

    chained = sec13f.chain_predecessors(current, [], 2)

    assert [(q["reportDate"], q["filingDate"]) for q in chained] == [("2026-06-30", "2026-08-14"), ("2026-03-31", "2026-05-15")]


def test_선행_CIK가_여럿이면_마지막_보고가_이른_쪽부터_잇는다():
    oldest = [_q("2019-12-31", "2020-02-14", "a"), _q("2019-09-30", "2019-11-14", "a")]
    middle = [_q("2020-06-30", "2020-08-14", "b"), _q("2020-03-31", "2020-05-15", "b"), _q("2019-12-31", "2020-02-13", "b")]
    current = [_q("2020-09-30", "2020-11-14", "c"), _q("2020-06-30", "2020-08-13", "c")]

    chained = sec13f.chain_predecessors(current, [middle, oldest, []], 30)

    assert [(q["reportDate"], q["filerCik"]) for q in chained] == [
        ("2020-09-30", "c"), ("2020-06-30", "b"), ("2020-03-31", "b"), ("2019-12-31", "a"), ("2019-09-30", "a")]
