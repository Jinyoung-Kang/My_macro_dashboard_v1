# 어닝 서프라이즈 테스트 입력 (`earnings_surprise`, 2단계 명세 C·§10)

키·연락처·쿠키(crumb)는 들어 있지 않습니다. 2026-10-25 수집기 컨테이너에서 받은 값입니다(읽기 요청 4건, DB 쓰기 없음).

- `quote_summary_earnings.json` — **실제 응답 그대로**. yfinance 1.7.0 `yf.Ticker(t)._quote._fetch(['earnings'])`의 반환값
  (키 = 티커). `earnings.earningsChart.quarterly`가 최근 4분기(SPCX는 3분기)를 주고, 분기마다 `actual`·`estimate`·
  `surprisePct`(문자열)·`periodEndDate`(UTC 자정 epoch)·`reportedDate`(실제 발표 시각 epoch). 통화는 `earnings.financialCurrency`.
  - AAPL — 보통의 경우(USD, 4분기 모두 추정치 있음, 장 마감 뒤 16:30 ET 발표).
  - BIDU — 통화 CNY(ADR이라 EPS가 본국 통화, 04:00·05:00 ET 발표).
  - SPCX — 첫 분기(1Q2026)에 `estimate`·`surprisePct`가 **없음**(상장 직후), 추정치가 음수인 분기(-0.28931).
  - INTC — 추정치가 0에 가까운 분기(0.00705 → 3,162%, 0.01313 → 2,109%) — `smallBase`.
  - 네 종목 모두 응답의 `surprisePct` 문자열이 (actual − estimate) / |estimate| × 100을 소수 둘째 자리로 반올림한 값과 같습니다.
- ETF·없는 종목은 Yahoo가 404를 주고 yfinance가 `None`으로 바꿔 돌려주므로 원문이 없습니다(시험에서 `None`으로 둠).
