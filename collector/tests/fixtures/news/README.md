# 사건 뉴스 RSS 테스트 입력 (`news_headlines`, 2단계 명세 B·§10)

키·연락처·쿠키는 들어 있지 않습니다. 2026-10-25 06:39 UTC 무렵 수집기 컨테이너에서 받은 응답(단계 0)에서 **앞 3항목만 남기고**
lxml로 다시 쓴 것입니다(그래서 XML 선언의 따옴표가 작은따옴표). 항목 안의 글자·날짜 형식은 원문 그대로입니다.

| 파일 | 피드 키 | URL | 형식 |
|---|---|---|---|
| `investing_news_25.xml` | `investing_stock` | https://www.investing.com/rss/news_25.rss | RSS 2.0, `pubDate`가 `2026-10-25 02:48:34`처럼 **시간대 없음**(UTC로 읽음) |
| `investing_news_95.xml` | `investing_ind` | https://www.investing.com/rss/news_95.rss | 위와 같음 |
| `investing_news_14.xml` | `investing_economy` | https://www.investing.com/rss/news_14.rss | 위와 같음 |
| `fed_press_monetary.xml` | `fed_monetary` | https://www.federalreserve.gov/feeds/press_monetary.xml | RSS 2.0, RFC 822 GMT |
| `bls_cpi.xml` | `bls_cpi` | https://www.bls.gov/feed/cpi.rss | 주소는 .rss지만 **Atom 1.0**, `-04:00` |
| `bls_empsit.xml` | `bls_empsit` | https://www.bls.gov/feed/empsit.rss | Atom 1.0 |
| `sec_pressreleases.xml` | `sec_press` | https://www.sec.gov/news/pressreleases.rss | RSS 2.0, `-0400`, **제목 앞뒤에 공백·줄바꿈** |

시각이 없는 항목·HTML 응답·빈 피드는 원문에 없어 테스트가 이 파일들을 고쳐 만듭니다(`test_news_headlines.py`).
