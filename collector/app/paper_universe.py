"""
app/paper_universe.py
모의 알고리즘 매매(로컬 전용 기능)가 시세·이력을 받는 종목 목록.

장부가 체결·평가하려면 관측치(paper.quotes)에 그 종목이 있어야 하고, 트랙레코드·모멘텀·분할 감지에는 10년 이력
(timeseries equity_close)이 있어야 합니다. 두 목록을 태스크마다 따로 만들면 한쪽에만 종목이 더해져 "이력은 있는데
체결이 안 되는" 종목이 생기므로 여기 한 곳에서 만듭니다.

[단계별 범위 — docs/specs/2026-10-24-paper-trading-design-plan.md §0-6]
  단계 1 : 13F 매핑표(equities.EQUITY_MAP) 티커 + indicators.PAPER_ETFS
  단계 1b: 후보 기관 CUSIP 매핑(equities_cusip.TRACK_RECORD_CUSIPS) 티커를 더합니다.

equities.all_tickers()를 쓰지 않는 이유: 그 목록에는 위험 분석 벤치마크 ACWI와 국면 판정 성과용 069500.KS(원화)가
들어 있습니다. 달러 장부의 매매 대상이 아니고, 원화 종가가 달러 장부에 섞이면 평가액이 틀립니다.
"""
from __future__ import annotations

from . import equities, indicators


def tradable() -> tuple[str, ...]:
    """장부 관측치를 받는 유니버스: EQUITY_MAP 티커 ∪ PAPER_ETFS (정렬·중복 제거). 단계 1b에서 CUSIP 매핑 티커를 더함."""
    tickers = {ticker for ticker, _ in equities.EQUITY_MAP.values() if ticker}
    tickers.update(indicators.PAPER_ETFS)
    return tuple(sorted(tickers))


def history() -> tuple[str, ...]:
    """10년 이력을 받는 유니버스. 단계 1에서는 tradable()과 같다."""
    return tradable()
