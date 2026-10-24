# SEC XBRL Company Facts 테스트 입력

**실제 응답을 잘라 낸 것이 아닙니다.** 단계 0-b 측정(2026-10-25, `docs/specs/2026-10-24-paper-trading-step0.md` §0-b)에서
본 companyfacts 문서의 **모양**(`facts → taxonomy → concept → units → unit → [사실]`, 사실의 `start`·`end`·`val`·`accn`·
`fy`·`fp`·`form`·`filed`·`frame`)과 그때 적어 둔 값으로 만든 작은 문서입니다. 연락처(User-Agent)나 키는 들어 있지 않습니다.

- `companyfacts_aapl.json` — US-GAAP·USD. 10-Q 현금흐름이 연초부터 누계(YTD)라 3개월치가 없음, 같은 기간이 다음 해
  공시의 비교 열에 같은 값으로 다시 나옴(버려야 함), dei 주식수의 end는 표지 기준일(2026-07-17), 창 밖 사실(2023-07-01 전
  분기·2020-01-01 전 연간), 8-K 사실·후보 밖 개념(버려야 함). 값은 측정 §5의 AAPL(2026-07-31 공시 10-Q)과 같습니다.
- `companyfacts_gpro.json` — 10-K/A(2026-10-19)가 9개월·3개월 순이익을 바꾼 재작성(남겨야 함)과 같은 값의 재게재(버려야 함).
- `companyfacts_sap.json` — IFRS(`ifrs-full`)·EUR 20-F 발행사. 6-K 중간 재무는 서식 밖.
- `companyfacts_xom_new.json`·`companyfacts_xom_old.json` — 지주회사 재편으로 CIK가 바뀐 XOM(별칭표). 새 CIK의 비교 열은
  옛 CIK와 같은 값이라 합칠 때 하나만 남습니다.
- `company_tickers.json` — `https://www.sec.gov/files/company_tickers.json`과 같은 모양의 9행(같은 CIK를 쓰는 GOOG·GOOGL,
  같은 티커가 두 번 나오는 행 포함).
