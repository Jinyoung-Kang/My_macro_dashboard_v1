# 미국 발표·실적 일정 테스트 입력 (`us_calendar`, 2단계 명세 A·§10)

키·연락처·쿠키(crumb)는 들어 있지 않습니다. 날짜는 2026-10-25 수집기 컨테이너에서 받은 값입니다.

- `fred_release_dates_cpi.json` — **실제 응답 그대로**(단계 0). `fred/release/dates?release_id=10`(CPI),
  `include_release_dates_with_no_data=true`, `realtime_start=2026-10-25`, `realtime_end=2026-12-08`, `sort_order=asc`.
- `fred_release_dates_step0.json` — 위 응답과 **같은 모양**에 단계 0에서 받은 날짜를 넣어 만든 릴리스 54(PCE)·50(고용)·53(GDP)
  응답(키 = release_id). 12-23은 45일 창(10-25 기준 12-09까지) 밖이라 일정에서는 빠지고 `lastKnownDate`(FRED가 아는 마지막 날짜)로만 남아야 합니다. 실제 응답 원문이 아닙니다.
- `quote_summary_calendar_events.json` — **실제 응답 그대로**. yfinance 1.7.0 `yf.Ticker(t)._quote._fetch(['calendarEvents'])`의
  반환값(키 = 티커). AAPL(2026-11-02 20:00 UTC → AMC, 추정 아님), PANW(2026-11-19 20:00 UTC, `isEarningsDateEstimate` true →
  장 구분 없음), JPM(2027-01-14 12:30 UTC → BMO). ETF(SPY)·없는 종목은 Yahoo가 404를 주고 yfinance가 `None`으로 바꿔 돌려주므로
  원문이 없습니다(시험에서 `None`으로 둠).
