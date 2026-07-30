# -*- coding: utf-8 -*-
"""TCD 원본 → 학습용 승객 명부(parquet) 생성 파이프라인.

스펙: schema/TCD_학습용명부_예시스펙.parquet (18컬럼, 1행 = 승객 1명의 1회 탑승)
레이블 규칙(기획 1차 수정안, 이슈 #1~#3 합의):
  - 재차인원 복원 후 FIFO 좌석배정: 먼저 입석한 승객이 먼저 착석,
    동시각 태그는 시분초 우선(동일하면 랜덤 — seed 고정)
  - is_standing: 승차 시점 입석 여부
  - standing_seconds: 입석 시작~착석(또는 하차)까지 초, 미입석 0
  - 하차 미태그 승객은 운행회차 종료까지 재차한 것으로 간주(점유 반영),
    본인 레이블 중 확정 불가능한 값(standing_seconds)만 NULL

운행회차 분리:
  TCD 필드21(운행출발일시)이 일부 차량에서 하루 종일 한 값으로 고정되는
  결함이 있어(서초구 4/1 기준 62대), 필드21 대신 ROUTESTTN의 정류장 순번을
  승차 태그에 붙여 순번이 역행하는 지점을 회차 경계로 삼는다.
  순번 조인이 불가능한 노선은 승차 공백(60분 초과)으로 분리한다.
  trip_round_id = 차량ID + "_" + 회차 첫 승차태그 시각 (출발시각의 근사).

사용법:
  python build_roster.py <TCD파일> <ROUTESTTN파일> <출력.parquet> [STTN파일]
  (STTN파일을 주면 정류장 좌표 기반으로 weather 컬럼을 채운다 — Open-Meteo 과거 관측)
"""
import random
import sys
from collections import deque
from datetime import datetime

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

RANDOM_SEED = 20260401

# TCD 필드번호(1-based) → 컬럼명
FIELDS = {
    7: "card_id",
    9: "board_dt",
    10: "alight_dt",
    18: "vehicle_id",
    24: "route_id",
    25: "route_settle_id",
    28: "board_stop_id",
    29: "board_stop_settle_id",
    30: "alight_stop_id",
    34: "usertype_code",
    45: "bus_type_code",
}

# 회차 분리 파라미터
SEQ_BACKWARD_TOLERANCE = 3   # 순번이 (현재 회차 최대 순번 - 3) 미만으로 떨어지면 새 회차
GAP_SPLIT_MINUTES = 60       # 순번 조인 불가 시: 승차 공백이 이 값을 넘으면 새 회차

BUS_TYPES = {
    "105": ("마을버스", 20),
    "151": ("마을버스", 20),
    "115": ("간선버스", 25),
    "120": ("지선버스", 25),
    "121": ("지선버스", 25),
    "122": ("지선버스", 25),
}

# 사용자구분코드 전체 목록 (AI 파트 확인 코드집 기준)
USERTYPE_NAMES = {
    "01": "일반인", "02": "어린이", "03": "청소년", "04": "경로",
    "05": "장애인", "06": "국가유공자", "07": "외국인", "08": "기타",
}

WEEKDAYS = ["월", "화", "수", "목", "금", "토", "일"]

# holidays 라이브러리가 누락한 임시공휴일이 생기면 "YYYY-MM-DD"로 추가
EXTRA_HOLIDAYS = set()


def build_holiday_set(dates):
    """주어진 날짜("YYYY-MM-DD") 목록이 걸치는 연도의 한국 공휴일 집합.

    설·추석(음력), 대체공휴일, 임시공휴일(예: 2025-06-03 대선일)까지
    holidays 라이브러리가 계산한다.
    """
    import holidays as holidays_lib
    years = sorted({int(d[:4]) for d in dates})
    kr = holidays_lib.KR(years=years)
    return {d.strftime("%Y-%m-%d") for d in kr} | EXTRA_HOLIDAYS


def load_tcd(path):
    # pandas는 usecols 순서와 무관하게 파일 등장 순서로 컬럼을 돌려주므로
    # 필드번호 오름차순으로 정렬해 이름을 짝지어야 안전하다
    field_nums = sorted(FIELDS)
    usecols = [n - 1 for n in field_nums]
    names = [FIELDS[n] for n in field_nums]
    df = pd.read_csv(
        path, sep="|", header=None, usecols=usecols, dtype=str,
        keep_default_na=False, encoding="utf-8", engine="c",
    )
    df.columns = names
    df = df[df["bus_type_code"].isin(BUS_TYPES)].copy()

    for col in ("board_dt", "alight_dt"):
        df[col] = pd.to_datetime(df[col], format="%Y%m%d%H%M%S", errors="coerce")

    # 승차일시가 파싱 불가한 행은 회차 분리·시뮬레이션이 불가능하므로 제외
    n_bad_board = int(df["board_dt"].isna().sum())
    if n_bad_board:
        print(f"경고: 승차일시 파싱 불가 {n_bad_board}건 제외")
        df = df[df["board_dt"].notna()].copy()

    # 하차일시가 승차일시보다 빠른 이상치는 하차 미태그로 취급
    bad = df["alight_dt"].notna() & (df["alight_dt"] <= df["board_dt"])
    df.loc[bad, ["alight_dt"]] = pd.NaT
    df.loc[bad, ["alight_stop_id"]] = ""
    return df, int(bad.sum())


def load_stop_sequences(routesttn_path):
    """ROUTESTTN(지역 08=수도권)에서 (정산사 노선ID, 정산사 정류장ID) → 정류장 순번."""
    seq_map = {}
    with open(routesttn_path, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("|")
            if len(p) < 6 or p[1] != "08":
                continue
            key = (p[3], p[5])
            seq = int(p[4])
            # 같은 (노선, 정류장)이 다른 순번으로 재등장하면(순환·재방문 노선) 첫 순번 유지.
            # 이런 노선은 두 번째 방문 태그가 낮은 순번으로 붙어 회차가 과분리될 수 있다.
            # 수도권 마스터에 1,781개 노선이 해당하나 서초구 대상 노선과는 겹치지 않음(4월 기준 0%).
            # 다른 지역으로 확장할 때는 이 가정을 재검증할 것.
            if key not in seq_map:
                seq_map[key] = seq
    return seq_map


def assign_trip_rounds(df, seq_map):
    """차량별 승차태그를 시간순으로 훑으며 정류장 순번 역행 지점에서 회차를 나눈다."""
    df = df.sort_values(["vehicle_id", "board_dt"], kind="stable")
    seqs = [
        seq_map.get((r, s))
        for r, s in zip(df["route_settle_id"], df["board_stop_settle_id"])
    ]
    df["stop_seq"] = pd.array(seqs, dtype="Int64")

    # 주의: 그룹 순회 순서는 df 행 순서와 다를 수 있으므로(한 차량이 여러 노선을
    # 운행하는 경우) 반드시 원본 인덱스에 매핑해서 되돌려 붙인다
    idx_list, id_list = [], []
    gap = pd.Timedelta(minutes=GAP_SPLIT_MINUTES)
    for (veh, _route), g in df.groupby(["vehicle_id", "route_settle_id"], sort=False):
        cur_id = None
        max_seq = None
        prev_t = None
        for idx, t, seq in zip(g.index, g["board_dt"], g["stop_seq"]):
            new_round = cur_id is None
            if not new_round:
                if pd.notna(seq) and max_seq is not None \
                        and seq < max_seq - SEQ_BACKWARD_TOLERANCE:
                    new_round = True          # 순번 역행 → 기점 회차
                elif prev_t is not None and t - prev_t > gap:
                    new_round = True          # 장시간 공백(순번 조인 불가 노선 대비)
            if new_round:
                cur_id = f"{veh}_{t:%Y%m%d%H%M%S}"
                max_seq = None
            if pd.notna(seq):
                max_seq = seq if max_seq is None else max(max_seq, int(seq))
            prev_t = t
            idx_list.append(idx)
            id_list.append(cur_id)
    df["trip_round_id"] = pd.Series(id_list, index=idx_list)

    n_unmapped = int(df["stop_seq"].isna().sum())
    return df, n_unmapped


def simulate_fifo(df):
    """운행회차별 재차인원 복원 + FIFO 좌석배정.

    반환: index → (is_standing 'Y'/'N', standing_seconds 또는 None)
    """
    rng = random.Random(RANDOM_SEED)
    results = {}

    for trip_id, g in df.groupby("trip_round_id", sort=False):
        capacity = BUS_TYPES[g["bus_type_code"].iat[0]][1]

        # 이벤트: (시각, 우선순위, 랜덤타이브레이크, 종류, 행index)
        # 같은 시각이면 하차(0)를 승차(1)보다 먼저 처리해 좌석을 비운다
        events = []
        for idx, row in g.iterrows():
            events.append((row["board_dt"], 1, rng.random(), "board", idx))
            if pd.notna(row["alight_dt"]):
                events.append((row["alight_dt"], 0, rng.random(), "alight", idx))
        events.sort(key=lambda e: (e[0], e[1], e[2]))

        seated = set()
        standing = deque()           # FIFO: 입석 시작 순서
        stand_start = {}

        for t, _, _, kind, idx in events:
            if kind == "board":
                if len(seated) < capacity:
                    seated.add(idx)
                    results[idx] = ("N", 0)
                else:
                    standing.append(idx)
                    stand_start[idx] = t
                    results[idx] = ("Y", None)  # 착석/하차 시 확정
            else:  # alight
                if idx in seated:
                    seated.remove(idx)
                    if standing:
                        nxt = standing.popleft()
                        seated.add(nxt)
                        secs = int((t - stand_start.pop(nxt)).total_seconds())
                        results[nxt] = ("Y", secs)
                else:
                    # 입석 상태로 하차
                    standing.remove(idx)
                    secs = int((t - stand_start.pop(idx)).total_seconds())
                    results[idx] = ("Y", secs)
        # 운행회차 종료까지 남은 입석 승객(하차 미태그): standing_seconds 확정 불가 → None 유지
    return results


def compute_headway(df):
    """같은 (노선, 정류장)의 직전 차량과의 도착 간격(초). 첫차는 NULL.

    차량의 정류장 도착시각 = 해당 운행회차가 그 정류장에서 받은 첫 승차 태그.
    """
    arrivals = (
        df.groupby(["route_id", "board_stop_id", "trip_round_id"], sort=False)["board_dt"]
        .min()
        .reset_index(name="arrival")
        .sort_values(["route_id", "board_stop_id", "arrival"])
    )
    arrivals["headway_sec"] = (
        arrivals.groupby(["route_id", "board_stop_id"])["arrival"].diff().dt.total_seconds()
    )
    return arrivals.set_index(["route_id", "board_stop_id", "trip_round_id"])["headway_sec"]


def build(tcd_path, routesttn_path, out_path, sttn_path=None,
          seq_map=None, stop_coords=None):
    """단일 TCD 파일 → 명부 parquet. seq_map/stop_coords를 주면 재로드 생략(배치용)."""
    t0 = datetime.now()
    df, n_bad_alight = load_tcd(tcd_path)
    print(f"화이트리스트 행: {len(df):,} (하차<승차 이상치 {n_bad_alight}건은 미태그 처리)")

    if seq_map is None:
        seq_map = load_stop_sequences(routesttn_path)
    df, n_unmapped = assign_trip_rounds(df, seq_map)
    n_rounds = df["trip_round_id"].nunique()
    print(f"정류장 순번 매핑: {len(seq_map):,}개 (노선,정류장) / 순번 미매핑 태그 {n_unmapped:,}건")
    print(f"운행회차: {n_rounds:,}개 (차량 {df['vehicle_id'].nunique():,}대)")

    labels = simulate_fifo(df)
    df["is_standing"] = df.index.map(lambda i: labels[i][0])
    df["standing_seconds"] = df.index.map(lambda i: labels[i][1])

    headway = compute_headway(df)
    df = df.join(
        headway.rename("headway_sec"),
        on=["route_id", "board_stop_id", "trip_round_id"],
    )

    df["board_date"] = df["board_dt"].dt.strftime("%Y-%m-%d")
    df["weekday"] = df["board_dt"].dt.dayofweek.map(lambda d: WEEKDAYS[d])
    df["hour"] = df["board_dt"].dt.hour
    df["is_holiday"] = df["board_date"].isin(build_holiday_set(df["board_date"].unique()))
    if sttn_path or stop_coords is not None:
        from weather import attach_weather
        df = attach_weather(df, sttn_path, coords=stop_coords)
    else:
        df["weather"] = None
    df["bus_type_name"] = df["bus_type_code"].map(lambda c: BUS_TYPES[c][0])
    df["seat_capacity"] = df["bus_type_code"].map(lambda c: BUS_TYPES[c][1])
    df["usertype_name"] = df["usertype_code"].map(USERTYPE_NAMES)

    # (노선, 구간=승하차 OD쌍, 시간대) 표본 수
    df["sample_count_route_segment_hour"] = df.groupby(
        ["route_id", "board_stop_id", "alight_stop_id", "hour"]
    )["card_id"].transform("size")

    out = pd.DataFrame({
        "trip_round_id": df["trip_round_id"],
        "route_id": df["route_id"],
        "board_stop_id": df["board_stop_id"].replace("", None),
        "alight_stop_id": df["alight_stop_id"].replace("", None),
        "board_datetime": df["board_dt"],
        "weekday": df["weekday"],
        "hour": df["hour"].astype("int64"),
        "is_holiday": df["is_holiday"].astype("bool"),
        "weather": df["weather"],
        "headway_sec": df["headway_sec"].astype("Int64"),
        "bus_type_code": df["bus_type_code"],
        "bus_type_name": df["bus_type_name"],
        "seat_capacity": df["seat_capacity"].astype("int64"),
        "usertype_code": df["usertype_code"].replace("", None),
        "usertype_name": df["usertype_name"],
        "is_standing": df["is_standing"],
        "standing_seconds": df["standing_seconds"].astype("Int64"),
        "sample_count_route_segment_hour":
            df["sample_count_route_segment_hour"].astype("int64"),
    })

    schema = pa.schema([
        ("trip_round_id", pa.string()),
        ("route_id", pa.string()),
        ("board_stop_id", pa.string()),
        ("alight_stop_id", pa.string()),
        ("board_datetime", pa.timestamp("s")),
        ("weekday", pa.string()),
        ("hour", pa.int64()),
        ("is_holiday", pa.bool_()),
        ("weather", pa.string()),
        ("headway_sec", pa.int64()),
        ("bus_type_code", pa.string()),
        ("bus_type_name", pa.string()),
        ("seat_capacity", pa.int64()),
        ("usertype_code", pa.string()),
        ("usertype_name", pa.string()),
        ("is_standing", pa.string()),
        ("standing_seconds", pa.int64()),
        ("sample_count_route_segment_hour", pa.int64()),
    ])
    table = pa.Table.from_pandas(out, schema=schema, preserve_index=False)
    pq.write_table(table, out_path, compression="zstd")

    n_stand = (out["is_standing"] == "Y").sum()
    n_null_secs = out["standing_seconds"].isna().sum()
    print(f"출력: {len(out):,}행 → {out_path}")
    print(f"입석 비율: {n_stand / len(out) * 100:.1f}% ({n_stand:,}명)")
    print(f"standing_seconds NULL(입석+하차미태그): {n_null_secs:,}")
    print(f"headway NULL(첫차): {out['headway_sec'].isna().sum():,}")
    print(f"소요: {(datetime.now() - t0).total_seconds():.0f}초")


if __name__ == "__main__":
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    build(*sys.argv[1:])
