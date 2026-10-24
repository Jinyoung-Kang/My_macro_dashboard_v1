package com.macrodash.feature.paper;

import com.macrodash.analytics.Fundamentals;
import com.macrodash.store.Datasets;
import com.macrodash.support.Json;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🧪 Claude 패킷의 재무 섹션 {@code 8b_fundamentals}(명세 D28, 설계 §4-5 ⑦) — 저장본 행({@code observations us.fundamentals})을
 * {@link Fundamentals} 입력으로 바꾸고, 계산 결과를 {@code 8_prices}와 같은 열 배열로 펼칩니다.
 *
 * <p>읽기(JDBC)는 {@link PacketBuilder}가 {@code StoreRepository}로 하고 여기서는 순수 변환만 합니다 — 대상 고르기(보유·후보·
 * watchlist)는 장부·신호를 아는 PacketBuilder 몫이고, 이 클래스는 같은 행이면 같은 섹션(결정적)만 보장합니다.
 *
 * <p><b>왜 수집 상태 저장본({@code us.fundamentals.status})을 싣지 않나</b>: 그 저장본은 '지금' 상태(받은 시각 등)라 초기 결정
 * 패킷(기준일 2026-10-23)에 넣으면 기준일 뒤 정보가 섞입니다. 패킷은 공시일 ≤ 기준일인 사실에서 나온 값만 씁니다.
 *
 * <p>단위: 금액은 <b>백만 달러</b>(소수 1자리, 보고 통화가 USD가 아니면 null — {@link Fundamentals}가 비움), 비율·성장률은
 * %(소수 1자리), 듀퐁의 회전율·레버리지는 배수({@link #multiple} — 소수 2자리, 1배 미만이면 유효숫자 3자리).
 */
final class FundamentalsSection {

    private FundamentalsSection() {
    }

    /** 섹션에 싣는 종목 상한(설계 §4-5 ⑦). 자르면 {@code omitted}에 뺀 수. */
    static final int MAX_ROWS = 25;
    static final double MILLION = 1_000_000.0;
    /**
     * 종목 한 줄의 계산 메모 글자 상한. 메모는 한글이라 글자마다 약 1토큰 — 25종목이 모두 160자면 메모만 약 3,000토큰이라 상한을
     * 꽉 채운 시험 패킷이 목표(30,000)에 바짝 붙었습니다. 계산이 뜻이 큰 메모(평균에 최신 값만·일부러 비운 값)를 앞에 두고, 개념
     * 이름은 범례 코드로 줄여 80자 안에 그런 메모가 먼저 들어갑니다. 잘린 뒤는 "…"입니다.
     */
    static final int MAX_NOTE_CHARS = 80;

    /**
     * 유니버스에 있지만 회사가 아닌 ETF·ETP — 수집기 {@code equities_cusip.TRACK_RECORD_ETPS}(후보 기관 13F CUSIP 매핑표에서 OpenFIGI
     * 종류가 ETP인 티커)와 같은 집합입니다({@code FundamentalsEtpParityTest}). 설정의 ETF({@code RuleSettings.etfs()} = 늘 넣는
     * ETF + SPY·QQQ)만 빼면 IVV·IWM·GDX 같은 ETF가 13F 교집합 후보·watchlist로 들어와 '재무 사실 없음' 빈 줄로 상한 25의 자리를
     * 차지합니다. 재무 섹션에서만 씁니다 — 규칙 책·검증기의 ETF 판정(종류별 상한)은 동결 대조군이라 그대로입니다(D15).
     */
    static final Set<String> TRACK_RECORD_ETPS = Set.of(
            "DUSA", "EEM", "EWY", "EWZ", "FXI", "GDX", "GLD", "IEF", "IEMG", "IGSB", "IVV", "IWM", "KWEB", "LQD",
            "MBB", "MCHI", "MUB", "QQQ", "SLV", "SPSB", "SPY", "TLT", "VCIT", "VEA", "VWO", "XLE", "XLF");

    /** 메모 안의 개념 이름({@code 분류체계:개념}) — 범례 코드로 바꿉니다. */
    private static final Pattern CONCEPT = Pattern.compile("\\b(?:us-gaap|ifrs-full|dei):[A-Za-z]+");

    /** 대상 종목의 역할 — 보유·섹션 7 교집합 후보·직전 결정의 watchlist. */
    static final String HOLDING = "HOLDING";
    static final String CANDIDATE = "CANDIDATE";
    static final String WATCHLIST = "WATCHLIST";

    /** 한 줄의 열(8b_fundamentals.columns). */
    static final List<String> COLUMNS = List.of("ticker", "role", "latestPeriodEnd", "filed", "form", "currency",
            "taxonomy", "revenueTtmUsdM", "revenueYoYPct", "netIncomeTtmUsdM", "netMarginPct", "ocfTtmUsdM",
            "capexTtmUsdM", "fcfTtmUsdM", "fcfConversionPct", "assetTurnoverX", "leverageX", "roePct",
            "receivablesYoYPct", "inventoryYoYPct", "sharesYoYPct", "buybacksTtmUsdM", "dividendsTtmUsdM",
            "longTermDebtUsdM", "debtToEquityPct", "isStale", "isProxy", "conceptsUsed", "notes");

    /** 설계 §4-5 ⑦의 문구 그대로. */
    static final String NOTE = "SEC XBRL 공시 원자료로 기계 계산(공시일 ≤ 기준일). 빈 값은 자료 없음 — [확인 필요]. 회사마다 태그가 "
            + "달라 개념 이름을 함께 적음. 실적 보도자료(8-K)는 들어오지 않아 10-Q 전까지 직전 분기 값";

    /**
     * 개념 코드의 필드 약어 — {@code conceptsUsed}를 "rev2 ni1 …"처럼 줄이고 뜻은 {@code conceptLegend}에 한 번만 적습니다.
     * 개념 이름을 줄마다 쓰면 25종목에 약 2,000토큰이라(대부분 같은 이름의 반복).
     */
    static final Map<String, String> FIELD_CODES;

    static {
        Map<String, String> codes = new LinkedHashMap<>();
        codes.put(Fundamentals.REVENUE, "rev");
        codes.put(Fundamentals.NET_INCOME, "ni");
        codes.put(Fundamentals.OCF, "ocf");
        codes.put(Fundamentals.CAPEX, "capex");
        codes.put(Fundamentals.ASSETS, "assets");
        codes.put(Fundamentals.EQUITY, "eq");
        codes.put(Fundamentals.RECEIVABLES, "ar");
        codes.put(Fundamentals.INVENTORY, "inv");
        codes.put(Fundamentals.LONG_TERM_DEBT, "ltd");
        codes.put(Fundamentals.BUYBACKS, "bb");
        codes.put(Fundamentals.DIVIDENDS, "div");
        codes.put(Fundamentals.SHARES, "sh");
        FIELD_CODES = Collections.unmodifiableMap(codes);
    }

    /** 섹션의 대상 한 종목. */
    record Target(String ticker, String role) {
    }

    /**
     * 저장본 한 행 → 계산 입력. 공시일은 {@code obsDate}(저장소가 붙임)이고, 칸이 빠지거나 형이 틀린 사실은 계산이 버리도록
     * null로 둡니다(0으로 채우지 않음). {@code fy}·{@code fp}는 공시의 회계연도·분기라 기간 판단에 쓰지 않아 옮기지 않습니다.
     */
    static Fundamentals.Row row(JsonNode payload) {
        LocalDate filed = Json.parseDate(Json.asText(payload, "obsDate"));
        List<Fundamentals.Fact> facts = new ArrayList<>();
        for (JsonNode f : Json.array(payload, "facts")) {
            if (f == null || !f.isObject()) {
                continue;
            }
            facts.add(new Fundamentals.Fact(Json.asText(f, "t"), Json.asText(f, "c"), Json.asText(f, "u"),
                    Json.parseDate(Json.asText(f, "s")), Json.parseDate(Json.asText(f, "e")), Json.asDouble(f, "v"),
                    Json.asText(f, "f"), Json.asText(f, "a")));
        }
        return new Fundamentals.Row(filed, facts);
    }

    static List<Fundamentals.Row> rows(List<JsonNode> payloads) {
        List<Fundamentals.Row> out = new ArrayList<>();
        if (payloads != null) {
            payloads.forEach(p -> out.add(row(p)));
        }
        return out;
    }

    /**
     * 섹션 본문.
     *
     * @param asOf     기준 거래일 — 공시일 ≤ 이 날인 사실만 계산에 씁니다({@link Fundamentals#compute})
     * @param shown    실을 종목(상한 안, 보유 → 후보 → watchlist 순)
     * @param omitted  상한으로 뺀 종목 수
     * @param excluded 대상이었지만 ETF·현금성이라 뺀 종목(재무 없음)
     * @param stored   종목 → 저장본 행들(기준일 뒤 공시가 섞여 있어도 계산이 거름)
     */
    static Map<String, Object> section(LocalDate asOf, List<Target> shown, int omitted, List<String> excluded,
                                       Map<String, List<JsonNode>> stored) {
        Map<String, String> legend = new LinkedHashMap<>();
        List<String> usedCodes = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        for (Target target : shown) {
            Fundamentals.Result r = Fundamentals.compute(rows(stored.get(target.ticker())), asOf);
            List<String> codes = new ArrayList<>();
            r.conceptsUsed().forEach((field, concept) -> {
                String code = code(field, concept);
                codes.add(code);
                if (!usedCodes.contains(code)) {
                    usedCodes.add(code);
                }
            });
            List<String> noteCodes = new ArrayList<>();
            String notes = notes(r, noteCodes);
            noteCodes.forEach(code -> {
                if (!usedCodes.contains(code)) {
                    usedCodes.add(code);
                }
            });
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", target.ticker());
            row.put("role", target.role());
            row.put("latestPeriodEnd", text(r.latestPeriodEnd()));
            row.put("filed", text(r.filed()));
            row.put("form", r.form());
            row.put("currency", r.currency());
            row.put("taxonomy", r.taxonomy());
            row.put("revenueTtmUsdM", millions(r.revenueTtm()));
            row.put("revenueYoYPct", PacketBuilder.decimal(r.revenueYoY(), 1));
            row.put("netIncomeTtmUsdM", millions(r.netIncomeTtm()));
            row.put("netMarginPct", PacketBuilder.decimal(r.netMargin(), 1));
            row.put("ocfTtmUsdM", millions(r.ocfTtm()));
            row.put("capexTtmUsdM", millions(r.capexTtm()));
            row.put("fcfTtmUsdM", millions(r.fcfTtm()));
            row.put("fcfConversionPct", PacketBuilder.decimal(r.fcfConversion(), 1));
            row.put("assetTurnoverX", multiple(r.assetTurnover()));
            row.put("leverageX", multiple(r.leverage()));
            row.put("roePct", PacketBuilder.decimal(r.roe(), 1));
            row.put("receivablesYoYPct", PacketBuilder.decimal(r.receivablesYoY(), 1));
            row.put("inventoryYoYPct", PacketBuilder.decimal(r.inventoryYoY(), 1));
            row.put("sharesYoYPct", PacketBuilder.decimal(r.sharesYoY(), 1));
            row.put("buybacksTtmUsdM", millions(r.buybacksTtm()));
            row.put("dividendsTtmUsdM", millions(r.dividendsTtm()));
            row.put("longTermDebtUsdM", millions(r.longTermDebt()));
            row.put("debtToEquityPct", PacketBuilder.decimal(r.debtToEquity(), 1));
            row.put("isStale", r.isStale());
            row.put("isProxy", r.isProxy());
            row.put("conceptsUsed", codes.isEmpty() ? null : String.join(" ", codes));
            row.put("notes", notes);
            List<Object> values = new ArrayList<>(COLUMNS.size());
            COLUMNS.forEach(column -> values.add(row.get(column)));
            rows.add(values);
        }
        // 범례는 필드 순서 → 후보 순서(같은 입력이면 같은 순서 — 결정적).
        Fundamentals.CANDIDATES.forEach((field, candidates) -> {
            for (String concept : candidates) {
                String code = code(field, concept);
                if (usedCodes.contains(code)) {
                    legend.put(code, concept);
                }
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "observations " + Datasets.OBS_US_FUNDAMENTALS + "(SEC XBRL Company Facts) — 공시일 ≤ 기준일(" + asOf
                + ")");
        out.put("note", NOTE);
        // 설명은 짧게 — 패킷 목표(30,000토큰)에서 이 섹션 몫은 약 4,000토큰이고, 대부분은 25줄의 값입니다.
        out.put("units", "…UsdM 백만 달러(USD가 아니면 null — 비율만), …Pct %, …X 배. TTM = 최근 4개 분기(연간 + 당해 누계 − 전년 "
                + "누계). roePct = netMarginPct × assetTurnoverX × leverageX(평균 = 최신·1년 전). 값은 latestPeriodEnd 기준, "
                + "filed·form은 그 기간의 마지막 공시. isStale = latestPeriodEnd가 기준일보다 15개월 넘게 앞섬, isProxy = 주식수가 "
                + "가중평균 희석 주식수(대용치)");
        out.put("targets", "보유(HOLDING) → 섹션 7 교집합 후보(CANDIDATE, 유니버스 안) → 직전 결정 watchlist(WATCHLIST), " + MAX_ROWS
                + "종목까지(omitted). ETF·현금성은 재무 없음(excluded)");
        out.put("conceptsNote", "conceptsUsed = 필드 코드(rev 매출·ni 순이익·ocf 영업CF·capex 설비투자·assets 자산·eq 자본·ar 매출채권·"
                + "inv 재고·ltd 장기부채·bb 자사주·div 배당·sh 주식수) + 후보 번호, 뜻은 conceptLegend(notes 안의 코드도). 코드가 없는 필드는 그 "
                + "기간 값 없음");
        out.put("columns", COLUMNS);
        out.put("rows", rows);
        out.put("conceptLegend", legend);
        out.put("omitted", omitted);
        out.put("excluded", excluded);
        return out;
    }

    /** "rev2" — 필드 약어 + 그 필드 후보 목록에서의 순번(1부터). 목록에 없는 개념이면(있을 수 없음) 이름 그대로. */
    static String code(String field, String concept) {
        List<String> candidates = Fundamentals.CANDIDATES.getOrDefault(field, List.of());
        int index = candidates.indexOf(concept);
        String prefix = FIELD_CODES.getOrDefault(field, field);
        return index < 0 ? prefix + ":" + concept : prefix + (index + 1);
    }

    /**
     * 계산 메모 한 줄 — 통화 메모는 {@code currency} 칸·섹션 단위 설명과 같은 말이라 뺍니다. 없으면 null. 메모 안의 개념 이름
     * ("us-gaap:PaymentsToAcquirePropertyPlantAndEquipment" — 그것만으로 50자)은 범례 코드("capex1")로 바꾸고 그 코드를
     * {@code codesOut}에 더합니다(범례에 실리게). 긴 개념 이름 하나 때문에 뒤의 '평균에 최신 값만' 같은 메모가 잘리지 않게.
     * 메모 순서는 계산이 정합니다(뜻이 큰 것부터).
     */
    static String notes(Fundamentals.Result r, List<String> codesOut) {
        List<String> kept = new ArrayList<>();
        for (String note : r.notes()) {
            if (!note.startsWith("보고 통화 ")) {
                kept.add(compact(note, codesOut));
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        String joined = String.join("; ", kept);
        return joined.length() <= MAX_NOTE_CHARS ? joined : joined.substring(0, MAX_NOTE_CHARS - 1) + "…";
    }

    /** 메모의 개념 이름 → 범례 코드(후보에 없는 이름은 그대로). */
    private static String compact(String note, List<String> codesOut) {
        Matcher m = CONCEPT.matcher(note);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String code = codeOf(m.group());
            if (code == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            if (!codesOut.contains(code)) {
                codesOut.add(code);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(code));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 개념({@code 분류체계:개념})의 범례 코드 — 어느 필드의 후보에도 없으면 null. */
    private static String codeOf(String concept) {
        for (Map.Entry<String, List<String>> e : Fundamentals.CANDIDATES.entrySet()) {
            if (e.getValue().contains(concept)) {
                return code(e.getKey(), concept);
            }
        }
        return null;
    }

    /**
     * 배수(회전율·레버리지) — 소수 2자리, 1배 미만이면 유효숫자 3자리. 은행의 자산 회전율 0.038배를 2자리로 내면 0.04배(+5%)가 되어
     * 패킷에 보이는 netMarginPct × assetTurnoverX × leverageX가 roePct와 1%p 가까이 어긋납니다(units 문구가 이 항등식을 적음).
     */
    static BigDecimal multiple(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        double abs = Math.abs(value);
        int scale = abs > 0 && abs < 1 ? Math.min(6, 2 - (int) Math.floor(Math.log10(abs))) : 2;
        return PacketBuilder.decimal(value, scale);
    }

    private static BigDecimal millions(Double value) {
        return value == null ? null : PacketBuilder.decimal(value / MILLION, 1);
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
