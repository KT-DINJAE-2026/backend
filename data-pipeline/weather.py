# -*- coding: utf-8 -*-
"""승차 정류장 위치·시각 기준 과거 날씨 조회.

- 소스: Open-Meteo Historical Weather API (무료, 인증키 불필요)
- 정류장 위경도(STTN 지역 08=수도권)를 0.1도(약 11km) 격자로 묶어
  격자별 시간 단위 날씨를 받아 (승차 정류장, 승차 시각)에 매핑한다.
- 응답은 cache/ 아래에 저장되어 재실행 시 API를 호출하지 않는다.
"""
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

API_URL = "https://archive-api.open-meteo.com/v1/archive"
CACHE_DIR = Path(__file__).parent / "cache"
# 격자 크기는 0.1도(약 11km) — round(x, 1)과 캐시 파일명 포맷(.1f)에 하드코딩돼
# 있으므로 바꾸려면 두 곳을 함께 수정해야 한다

# WMO weather code → 한글 범주 (스펙 예시의 맑음/비 계열에 맞춤)
_WMO_LABELS = [
    ((0, 1), "맑음"),
    ((2, 2), "구름많음"),
    ((3, 3), "흐림"),
    ((45, 48), "안개"),
    ((51, 67), "비"),
    ((71, 77), "눈"),
    ((80, 82), "비"),
    ((85, 86), "눈"),
    ((95, 99), "뇌우"),
]


def wmo_to_label(code):
    for (lo, hi), label in _WMO_LABELS:
        if lo <= code <= hi:
            return label
    return None


def load_stop_coords(sttn_path, stop_ids=None):
    """STTN(지역 08)에서 국토부 정류장ID → (위도, 경도). stop_ids=None이면 전체."""
    coords = {}
    stop_ids = set(stop_ids) if stop_ids is not None else None
    with open(sttn_path, encoding="utf-8") as f:
        for line in f:
            p = line.split("|")
            if len(p) > 19 and p[1] == "08" \
                    and (stop_ids is None or p[4] in stop_ids):
                try:
                    lat, lon = float(p[17]), float(p[18])
                except ValueError:
                    continue
                if lat > 30:  # 0.0 = 좌표 미정 방어
                    coords[p[4]] = (lat, lon)
    return coords


def _fetch_cell(lat, lon, date):
    """격자 중심점의 해당 일자 시간별 WMO weather code 목록(24개)."""
    CACHE_DIR.mkdir(exist_ok=True)
    cache_file = CACHE_DIR / f"weather_{lat:.1f}_{lon:.1f}_{date}.json"
    if cache_file.exists():
        return json.loads(cache_file.read_text(encoding="utf-8"))

    params = urllib.parse.urlencode({
        "latitude": f"{lat:.1f}", "longitude": f"{lon:.1f}",
        "start_date": date, "end_date": date,
        "hourly": "weather_code", "timezone": "Asia/Seoul",
    })
    last_err = None
    for attempt in range(5):
        try:
            with urllib.request.urlopen(f"{API_URL}?{params}", timeout=30) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            break
        except (urllib.error.URLError, OSError) as e:
            last_err = e
            time.sleep(2 ** attempt)  # 1,2,4,8,16초 백오프
    else:
        raise RuntimeError(f"날씨 API {attempt + 1}회 실패: {lat},{lon},{date}") from last_err
    codes = data["hourly"]["weather_code"]
    cache_file.write_text(json.dumps(codes), encoding="utf-8")
    time.sleep(0.2)  # 무료 API 예의상 호출 간격
    return codes


def attach_weather(df, sttn_path=None, coords=None):
    """명부 df에 weather 컬럼을 채워 반환. (board_stop_id, board_dt 기준)

    coords를 주면 STTN을 다시 읽지 않는다(배치용).
    """
    if coords is None:
        coords = load_stop_coords(sttn_path, df["board_stop_id"].dropna().unique())

    # 전체 마스터 좌표를 받았더라도 이 명부에 실제 등장하는 정류장만 사용
    used = set(df["board_stop_id"].dropna().unique())
    coords = {s: c for s, c in coords.items() if s in used}

    dates = sorted(df["board_dt"].dt.strftime("%Y-%m-%d").unique())
    cells = sorted({
        (round(lat, 1), round(lon, 1)) for lat, lon in coords.values()
    })
    print(f"날씨 조회: 격자 {len(cells)}개 × {len(dates)}일")

    cell_weather = {}  # (cell_lat, cell_lon, date, hour) → 라벨
    for date in dates:
        for lat, lon in cells:
            codes = _fetch_cell(lat, lon, date)
            for hour, code in enumerate(codes):
                if code is not None:
                    cell_weather[(lat, lon, date, hour)] = wmo_to_label(int(code))

    def lookup(stop_id, dt):
        if stop_id not in coords:
            return None
        lat, lon = coords[stop_id]
        return cell_weather.get(
            (round(lat, 1), round(lon, 1), dt.strftime("%Y-%m-%d"), dt.hour)
        )

    df["weather"] = [
        lookup(s, t) for s, t in zip(df["board_stop_id"], df["board_dt"])
    ]
    n_null = df["weather"].isna().sum()
    print(f"weather 매핑 완료: NULL {n_null:,}건 ({n_null / len(df) * 100:.2f}%)")
    return df
