/**
 * 차트 축의 순수 계산 — 값 범위, 라벨 폭. React·Recharts를 모릅니다.
 */
/**
 * Y축 표시 범위.
 *
 * <b>왜 필요한가</b> — Recharts는 면적 차트의 Y축을 0부터 그립니다. 순유동성처럼
 * 값이 5.85~6.0조 달러 사이에서 움직이는 계열에 0~8조 축을 쓰면, 정작 읽어야 할
 * 변동이 축 꼭대기 얇은 띠에 눌려 직선처럼 보입니다.
 *
 * 0이 의미를 갖는 계열(스프레드처럼 부호가 중요한 값)만 0을 포함시키고,
 * 나머지는 데이터 범위에 맞춰 여백만 둡니다.
 */
export function valueDomain(
  data: { value: number | null }[],
  includeZero: boolean,
): [number | "auto", number | "auto"] {
  const values = data
    .map((point) => point.value)
    .filter((value): value is number => value !== null && Number.isFinite(value));

  if (values.length === 0) {
    return ["auto", "auto"];
  }

  let min = Math.min(...values);
  let max = Math.max(...values);
  if (includeZero) {
    min = Math.min(min, 0);
    max = Math.max(max, 0);
  }

  // 위아래로 8%씩 숨 쉴 공간. 선이 축에 붙어 잘린 것처럼 보이지 않게 합니다.
  const span = max - min;
  const pad = span === 0 ? Math.abs(max) * 0.05 || 1 : span * 0.08;

  // 값이 모두 0 이상인 계열은 축을 0 아래로 내리지 않습니다. 역레포처럼
  // 음수가 될 수 없는 양에 "-0.11T" 눈금이 찍히면 있을 수 없는 값을
  // 있을 수 있는 것처럼 보여 주게 됩니다.
  const lower = min >= 0 ? Math.max(0, min - pad) : min - pad;
  return [lower, max + pad];
}

/**
 * 축 라벨 길이를 눈대중으로 잽니다.
 *
 * 한글·한자는 폭이 라틴 문자의 약 두 배입니다. 글자 수만 세면
 * "KODEX 레버리지"와 "TIGER 미국필라델피아반도체나스닥"을 같게 보게 됩니다.
 */
export function visualWidth(text: string): number {
  let width = 0;
  for (const char of text) {
    width += /[가-힣ㄱ-ㅎㅏ-ㅣ一-鿿ぁ-ヿ]/.test(char) ? 2 : 1;
  }
  return width;
}

export function truncateToWidth(text: string, maxWidth: number): string {
  if (visualWidth(text) <= maxWidth) {
    return text;
  }
  let out = "";
  let width = 0;
  for (const char of text) {
    const next = width + (/[가-힣ㄱ-ㅎㅏ-ㅣ一-鿿ぁ-ヿ]/.test(char) ? 2 : 1);
    if (next > maxWidth - 1) {
      break;
    }
    out += char;
    width = next;
  }
  return out + "…";
}

/** 이름과 구분자 사이의 표시 — 백엔드 13F 비중 추이 줄 이름(`이름 · 클래스`, `이름 · CUSIP …`)과 같습니다. */
const TAG_SEPARATOR = " · ";

/**
 * 이름표를 폭에 맞춰 줄이되, 첫 ' · ' 뒤의 구분자는 그대로 둡니다 — 줄이는 쪽은 앞의 이름입니다.
 *
 * 같은 이름의 두 항목을 가르려고 붙인 구분자(' · COM'과 ' · PERP PFD CNV A', ' · CUSIP 464287200'과 '…655')가
 * 앞에서부터 자르면 통째로 잘려 다시 같은 이름표가 됩니다(QA-120). 이름은 적어도 `minNameWidth`만큼 남기고,
 * 그래도 폭을 넘으면 구분자를 자르지 않고 이름표를 그만큼 길게 둡니다. ' · '가 없으면 {@link truncateToWidth}와 같습니다.
 */
export function truncateKeepingTag(label: string, maxWidth: number, minNameWidth = 12): string {
  const at = label.indexOf(TAG_SEPARATOR);
  if (at < 0) {
    return truncateToWidth(label, maxWidth);
  }
  const tag = label.slice(at);
  const nameWidth = Math.max(minNameWidth, maxWidth - visualWidth(tag));
  return `${truncateToWidth(label.slice(0, at), nameWidth).replace(/\s+…$/, "…")}${tag}`;
}

/**
 * 여러 키의 이름표 — 각자 {@link truncateKeepingTag}로 줄이고, 그래도 다른 키와 같아진 이름표는 줄이지 않은 키로 둡니다.
 * 키가 서로 다르면 이름표도 서로 다릅니다(같은 이름표 아래 다른 숫자가 보이지 않게).
 */
export function distinctLabels(keys: readonly string[], maxWidth: number): string[] {
  const labels = keys.map((key) => truncateKeepingTag(key, maxWidth));
  for (;;) {
    const counts = new Map<string, number>();
    labels.forEach((label) => counts.set(label, (counts.get(label) ?? 0) + 1));
    let changed = false;
    labels.forEach((label, index) => {
      if ((counts.get(label) ?? 0) > 1 && label !== keys[index]) {
        labels[index] = keys[index];
        changed = true;
      }
    });
    if (!changed) {
      return labels;
    }
  }
}


/** 도넛 고리와 직접 라벨의 자리(픽셀·비율) — 차트 컴포넌트가 Recharts에 넘기는 값과 같아야 합니다. */
export interface DonutLabelGeometry {
  /** 차트 안쪽 여백(사방 같음). */
  margin: number;
  /** 바깥 반지름 = 최대 반지름(여백 안 너비·높이 중 작은 쪽의 절반) × 이 백분율 ÷ 100 — Recharts outerRadius "70%"면 70. */
  outerPct: number;
  /** 바깥 반지름에서 라벨 자리까지(Recharts Pie 라벨의 기본 offsetRadius 20). */
  offset: number;
  fontSize: number;
}

/** 라틴 한 칸(visualWidth 1)의 어림 폭 ÷ 글자 크기 — 한글(2칸)은 실제보다 조금 넓게 잡혀 잘리는 쪽으로 틀리지 않습니다. */
const LABEL_UNIT_EM = 0.6;

/**
 * 도넛의 직접 라벨(고리 밖)이 차트 너비 안에 다 들어가는가 — 들어가지 않으면 부르는 쪽이 라벨을 끄고 범례·툴팁·표에 맡깁니다.
 *
 * Recharts는 라벨을 바깥 반지름 + offset 자리에 두고, 중심 오른쪽 라벨은 그 점에서 오른쪽으로, 왼쪽 라벨은 왼쪽으로 적습니다. 그래서
 * 3시·9시 방향 라벨이 가장 멀리 나가고, 반폭 − (반지름 + offset)보다 긴 글자는 SVG 끝에서 잘립니다(인라인 SVG는 넘친 글자를 잘라 냄 —
 * 좁은 화면 360px에서 "NVDA 35.0%"·"AAPL 25.0%"가 좌우로 잘렸음). 라벨이 어느 각도에 올지는 따지지 않고 가장 긴 라벨로 잽니다 —
 * 일부만 잘리는 것보다 모두 끄는 편이 낫습니다. 너비를 아직 모르면(측정 전) false.
 */
export function donutLabelsFit(
  width: number | null | undefined,
  height: number,
  labels: readonly (string | null | undefined)[],
  geometry: DonutLabelGeometry,
): boolean {
  const texts = labels.filter((label): label is string => typeof label === "string" && label.length > 0);
  if (texts.length === 0) return false;
  if (typeof width !== "number" || !Number.isFinite(width) || width <= 0) return false;
  const maxRadius = Math.min(width - geometry.margin * 2, height - geometry.margin * 2) / 2;
  if (maxRadius <= 0) return false;
  const reach = (maxRadius * geometry.outerPct) / 100 + geometry.offset;
  const longest = Math.max(...texts.map((text) => visualWidth(text))) * geometry.fontSize * LABEL_UNIT_EM;
  return width / 2 - reach >= longest;
}
