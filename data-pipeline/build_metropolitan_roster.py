# -*- coding: utf-8 -*-
"""KDATA METROPOLITAN 일별 ZIP에서 자치구 경유 노선의 학습 명부를 생성한다.

원본 ZIP은 열기만 하며 압축을 풀거나 변경하지 않는다. 같은 ZIP의 STTN에서
법정동코드로 대상 정류장을 찾고, ROUTESTTN에서 그 정류장을 경유하는 버스 노선을
선정한 뒤, TCD에서는 해당 노선의 *전체 승객*을 읽는다. 대상 자치구 안에서 승하차한
행만 고르면 자치구 밖에서 이미 타고 있던 승객이 빠져 재차인원이 과소 계산되기 때문이다.

사용법:
  python build_metropolitan_roster.py DATA_YYYYMMDD.zip OUTPUT.parquet
  python build_metropolitan_roster.py DATA_YYYYMMDD.zip OUTPUT.parquet --district-code 11290

18컬럼 자료에는 국토부 표준 ID가 없으므로 같은 날짜의 27컬럼 TCD ZIP을 함께 지정하면
정산 ID를 표준 노선·정류장 ID로 변환한다. 지정하지 않으면 검사용 복합 정산 ID
(``정산사ID:정산지역코드:정산사로컬ID``)를 유지한다.
"""

import argparse
import csv
import json
import re
import sys
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from zipfile import ZipFile

import pandas as pd

from build_roster import BUS_TYPES, build_loaded


# 각 필수 컬럼 집합은 공급기관 파일 형식 변경을 조기에 발견하기 위한 입력 계약이다.
# 필요한 컬럼만 검사하므로 새 컬럼이 추가되는 변경은 허용하고, 기존 필수 컬럼 누락은 막는다.
STTN_REQUIRED_COLUMNS = {
    "정산사코드",
    "지역코드",
    "교통수단구분",
    "정류장ID",
    "법정동코드",
    "정류장GPSY좌표",
    "정류장GPSX좌표",
}
ROUTESTTN_REQUIRED_COLUMNS = {
    "정산사ID",
    "정산지역코드",
    "노선ID",
    "교통수단구분",
    "정류장순번",
    "정류장ID",
}
TCD_REQUIRED_COLUMNS = {
    "운행일자",
    "정산사ID",
    "가상카드번호",
    "정산지역코드",
    "정산사차량ID",
    "교통수단코드",
    "정산사노선ID",
    "승차일시",
    "정산사승차정류장ID",
    "하차일시",
    "정산사하차정류장ID",
    "이용자유형코드(시스템)",
    "이용자수",
}
MAX_PASSENGERS_PER_TRANSACTION = 100
RAW_TCD_COLUMN_COUNT = 27
# manifest의 재사용 가능 여부를 판단하는 버전이다. 출력 의미가 바뀌면 반드시 올린다.
PIPELINE_VERSION = "metropolitan-tcd-v2"


def source_key(operator_id, region_code, local_id):
    """서로 다른 정산사·지역에서 겹치는 로컬 ID를 안전한 복합 ID로 만든다."""
    return f"{operator_id.strip()}:{region_code.strip()}:{local_id.strip()}"


def entry_name(zip_file, prefix, date):
    """대상 일자의 유일한 CSV 항목을 찾고 중복·누락을 오류로 처리한다."""
    expected = f"{prefix}_{date}.csv"
    matches = [name for name in zip_file.namelist() if Path(name).name == expected]
    if len(matches) != 1:
        raise ValueError(f"ZIP 항목을 하나로 결정할 수 없음: {expected} (발견 {len(matches)}개)")
    return matches[0]


def archive_date(zip_path):
    match = re.search(r"DATA_(\d{8})\.zip$", Path(zip_path).name, re.IGNORECASE)
    if not match:
        raise ValueError(f"일자를 파일명에서 찾을 수 없음: {zip_path} (DATA_YYYYMMDD.zip 필요)")
    return match.group(1)


def raw_tcd_entry_names(zip_file, date):
    """27컬럼 원본 TCD 분할 파일을 분할 번호 순서로 찾는다.

    매핑 자료는 하루치가 여러 CSV로 나뉠 수 있다. 모든 분할을 읽어야 뒤쪽 파일에만
    등장하는 ID와 상충 매핑까지 확인할 수 있으므로 첫 파일만 사용하는 최적화는 금지한다.
    """
    pattern = re.compile(
        rf"TB_KTS_DWTCD_{date}_(\d+)\.csv$", re.IGNORECASE
    )
    matches = [
        name for name in zip_file.namelist()
        if pattern.fullmatch(Path(name).name)
    ]
    if not matches:
        raise ValueError(f"27컬럼 TCD 항목을 찾을 수 없음: {date}")
    return sorted(matches, key=lambda name: int(
        pattern.fullmatch(Path(name).name).group(1)
    ))


def _put_mapping(mapping, conflicts, source_id, standard_id):
    """빈 표준 ID는 무시하고 하나의 정산 ID가 여러 표준 ID를 가리키면 기록한다."""
    if not source_id or not standard_id:
        return
    previous = mapping.get(source_id)
    if previous is None:
        mapping[source_id] = standard_id
    elif previous != standard_id:
        conflicts.setdefault(source_id, {previous}).add(standard_id)


def load_standard_id_maps(mapping_zip_path, date, required_routes, required_stops,
                          allow_missing=False):
    """같은 날짜의 27컬럼 TCD에서 정산 ID → 국토부 표준 ID 사전을 만든다.

    27컬럼 자료에서 13/14번째는 표준/정산 노선 ID, 17/18번째와
    20/21번째는 표준/정산 승·하차 정류장 ID다. 필요한 ID만 보관하지만 파일 전체를
    검사하여 뒤쪽 파일에 상충하는 매핑이 있어도 놓치지 않는다.

    아래 코드의 배열 인덱스는 0부터 시작하므로 문서상의 13/14번째 컬럼을
    ``row[12]``/``row[13]``으로 읽는다. 공급기관의 공식 컬럼 정의가 바뀌면 이 위치와
    ``RAW_TCD_COLUMN_COUNT``를 함께 검토해야 한다.
    """
    import io

    mapping_zip_path = Path(mapping_zip_path).resolve()
    mapping_date = archive_date(mapping_zip_path)
    if mapping_date != date:
        raise ValueError(
            f"표준 ID 매핑 ZIP 일자 불일치: TCD={date}, 매핑={mapping_date}"
        )

    route_map = {}
    stop_map = {}
    route_conflicts = {}
    stop_conflicts = {}
    scanned_rows = 0
    invalid_column_rows = 0

    with ZipFile(mapping_zip_path, "r") as zip_file:
        names = raw_tcd_entry_names(zip_file, date)
        for name in names:
            with io.TextIOWrapper(
                zip_file.open(name, "r"), encoding="utf-8-sig", newline=""
            ) as text:
                for row_number, row in enumerate(csv.reader(text), start=1):
                    scanned_rows += 1
                    if len(row) != RAW_TCD_COLUMN_COUNT:
                        invalid_column_rows += 1
                        continue
                    operator_id = row[1].strip()
                    region_code = row[4].strip()

                    route_key = source_key(operator_id, region_code, row[13])
                    if route_key in required_routes:
                        _put_mapping(
                            route_map, route_conflicts, route_key, row[12].strip()
                        )

                    for standard_index, settlement_index in ((16, 17), (19, 20)):
                        local_id = row[settlement_index].strip()
                        if not local_id:
                            continue
                        stop_key = source_key(operator_id, region_code, local_id)
                        if stop_key in required_stops:
                            _put_mapping(
                                stop_map,
                                stop_conflicts,
                                stop_key,
                                row[standard_index].strip(),
                            )
        entry_meta = archive_entry_metadata(zip_file, names)

    if invalid_column_rows:
        raise ValueError(
            f"27컬럼 TCD 형식 오류: 전체 {scanned_rows:,}행 중 "
            f"컬럼 수가 {RAW_TCD_COLUMN_COUNT}가 아닌 행 {invalid_column_rows:,}개"
        )
    if route_conflicts or stop_conflicts:
        raise ValueError(
            "표준 ID 매핑 충돌: "
            f"노선 {len(route_conflicts):,}개, 정류장 {len(stop_conflicts):,}개"
        )

    missing_routes = sorted(required_routes - route_map.keys())
    missing_stops = sorted(required_stops - stop_map.keys())
    if (missing_routes or missing_stops) and not allow_missing:
        raise ValueError(
            "표준 ID 매핑 누락: "
            f"노선 {len(missing_routes):,}개, 정류장 {len(missing_stops):,}개; "
            f"노선 예시={missing_routes[:3]}, 정류장 예시={missing_stops[:3]}"
        )

    metadata = {
        "source_zip": str(mapping_zip_path),
        "source_zip_size_bytes": mapping_zip_path.stat().st_size,
        "source_zip_mtime_utc": datetime.fromtimestamp(
            mapping_zip_path.stat().st_mtime, timezone.utc
        ).isoformat(),
        "source_entries": entry_meta,
        "scanned_rows": scanned_rows,
        "route_mapping_count": len(route_map),
        "stop_mapping_count": len(stop_map),
        "route_conflict_count": 0,
        "stop_conflict_count": 0,
        "missing_route_count": len(missing_routes),
        "missing_stop_count": len(missing_stops),
        "missing_route_examples": missing_routes[:10],
        "missing_stop_examples": missing_stops[:10],
    }
    return route_map, stop_map, metadata


def apply_standard_ids(df, route_map, stop_map):
    """모델 입력용 ID를 변환하고 표준 ID가 모두 있는 학습 가능 행을 반환한다.

    표준 ID가 없는 행은 정산 ID를 임시로 유지해 회차·재차인원·FIFO 계산에는
    참여시킨다. 반환 마스크를 이용해 계산 이후 학습 출력에서만 제외한다.
    """
    route_standard = df["route_settle_id"].map(route_map)
    board_standard = df["board_stop_settle_id"].map(stop_map)
    has_alight = df["alight_stop_id"].ne("")
    alight_standard = df["alight_stop_id"].map(stop_map)
    valid_for_training = (
        route_standard.notna()
        & board_standard.notna()
        & (~has_alight | alight_standard.notna())
    )
    df["route_id"] = route_standard.fillna(df["route_settle_id"])
    df["board_stop_id"] = board_standard.fillna(df["board_stop_settle_id"])
    df.loc[has_alight, "alight_stop_id"] = (
        alight_standard[has_alight].fillna(df.loc[has_alight, "alight_stop_id"])
    )
    return valid_for_training


def standardize_stop_coords(source_coords, stop_map):
    """정산 정류장 좌표를 표준 정류장 ID 기준 좌표로 바꾼다.

    여러 정산 ID가 같은 표준 ID로 합쳐지는 별칭은 정상이다. 다만 두 좌표가 날씨 조회
    격자(0.1도)까지 다르면 어느 좌표를 대표값으로 써야 할지 자동 판단하지 않고 충돌로
    기록한다. 같은 격자라면 날씨 결과가 같으므로 첫 좌표를 재사용한다.
    """
    result = {}
    grid_conflicts = 0
    for source_id, standard_id in sorted(stop_map.items()):
        coords = source_coords.get(source_id)
        if coords is None:
            continue
        previous = result.get(standard_id)
        if previous is not None and (
            round(previous[0], 1), round(previous[1], 1)
        ) != (round(coords[0], 1), round(coords[1], 1)):
            grid_conflicts += 1
            continue
        result.setdefault(standard_id, coords)
    return result, grid_conflicts


@contextmanager
def dict_reader(zip_file, name, required_columns):
    """ZIP 항목을 UTF-8 CSV DictReader로 열고 헤더 계약을 검증한다."""
    binary = zip_file.open(name, "r")
    text = None
    try:
        import io

        text = io.TextIOWrapper(binary, encoding="utf-8-sig", newline="")
        reader = csv.DictReader(text)
        actual = set(reader.fieldnames or [])
        missing = required_columns - actual
        if missing:
            raise ValueError(f"{name} 필수 컬럼 누락: {sorted(missing)}")
        yield reader
    finally:
        if text is not None:
            text.close()
        else:
            binary.close()


def load_route_scope(zip_file, date, district_code):
    """대상 자치구 정류장, 경유 버스 노선, 전체 정류장 순번을 읽는다.

    먼저 STTN 법정동코드로 대상 정류장을 찾고, ROUTESTTN에서 그 정류장을 하나라도
    지나는 노선을 선정한다. 그다음 선정 노선의 자치구 밖 정류장 순번까지 모두 남긴다.
    자치구 내부 정류장만 남기면 외부 승하차와 회차 경계를 복원할 수 없다.
    """
    sttn_name = entry_name(zip_file, "TB_KTS_STTN", date)
    routesttn_name = entry_name(zip_file, "TB_KTS_ROUTESTTN", date)

    target_stops = set()
    stop_coords = {}
    with dict_reader(zip_file, sttn_name, STTN_REQUIRED_COLUMNS) as rows:
        for row in rows:
            if row["교통수단구분"].strip() != "B":
                continue
            stop_key = source_key(
                row["정산사코드"], row["지역코드"], row["정류장ID"]
            )
            try:
                lat = float(row["정류장GPSY좌표"])
                lon = float(row["정류장GPSX좌표"])
            except (TypeError, ValueError):
                pass
            else:
                if lat > 30 and lon > 100:
                    stop_coords[stop_key] = (lat, lon)
            if not row["법정동코드"].strip().startswith(district_code):
                continue
            target_stops.add(stop_key)

    selected_routes = set()
    # ROUTESTTN은 한 번만 순회한다. 아직 선정 여부를 모르는 시점이므로 행을 임시로
    # 모은 뒤, 대상 정류장을 경유한다고 확인된 노선의 순번만 두 번째 단계에서 남긴다.
    route_stop_rows = []
    with dict_reader(zip_file, routesttn_name, ROUTESTTN_REQUIRED_COLUMNS) as rows:
        for row in rows:
            if row["교통수단구분"].strip() != "B":
                continue
            route_key = source_key(
                row["정산사ID"], row["정산지역코드"], row["노선ID"]
            )
            stop_key = source_key(
                row["정산사ID"], row["정산지역코드"], row["정류장ID"]
            )
            try:
                stop_order = int(row["정류장순번"])
            except (TypeError, ValueError):
                continue
            route_stop_rows.append((route_key, stop_key, stop_order))
            if stop_key in target_stops:
                selected_routes.add(route_key)

    seq_map = {}
    duplicate_route_stops = 0
    for route_key, stop_key, stop_order in route_stop_rows:
        if route_key not in selected_routes:
            continue
        key = (route_key, stop_key)
        if key in seq_map:
            duplicate_route_stops += 1
            continue
        seq_map[key] = stop_order

    if not target_stops:
        raise ValueError(f"법정동코드 {district_code}에 해당하는 버스 정류장이 없음")
    if not selected_routes:
        raise ValueError(f"법정동코드 {district_code} 정류장을 경유하는 버스 노선이 없음")
    return {
        "sttn_entry": sttn_name,
        "routesttn_entry": routesttn_name,
        "target_stops": target_stops,
        "selected_routes": selected_routes,
        "seq_map": seq_map,
        "stop_coords": stop_coords,
        "duplicate_route_stops": duplicate_route_stops,
    }


def load_metropolitan_tcd(zip_file, date, selected_routes):
    """선정 노선의 전체 거래를 공용 처리기가 요구하는 DataFrame으로 정규화한다.

    승·하차 위치가 대상 자치구인지로 다시 거르지 않는다. 선정 노선에 탑승한 전체 승객이
    있어야 자치구 진입 전부터 타고 있던 승객을 포함해 재차인원과 좌석을 복원할 수 있다.
    ``이용자수``가 2 이상인 집계행은 같은 시각·OD의 승객 여러 명으로 확장한다.
    """
    tcd_name = entry_name(zip_file, "TB_KTS_DWTCD_METROPOLITAN", date)
    records = []
    counters = {
        "source_rows": 0,
        "selected_route_rows": 0,
        "selected_passenger_rows": 0,
        "invalid_user_count_rows": 0,
        "invalid_board_time_rows": 0,
        "invalid_alight_time_rows": 0,
        "missing_alight_rows": 0,
    }

    with dict_reader(zip_file, tcd_name, TCD_REQUIRED_COLUMNS) as rows:
        for source_row_number, row in enumerate(rows, start=2):
            counters["source_rows"] += 1
            route_key = source_key(
                row["정산사ID"], row["정산지역코드"], row["정산사노선ID"]
            )
            bus_type_code = row["교통수단코드"].strip()
            if route_key not in selected_routes or bus_type_code not in BUS_TYPES:
                continue
            counters["selected_route_rows"] += 1

            try:
                passenger_count = int(row["이용자수"])
            except (TypeError, ValueError):
                passenger_count = 0
            if not 1 <= passenger_count <= MAX_PASSENGERS_PER_TRANSACTION:
                counters["invalid_user_count_rows"] += 1
                continue

            board_dt = pd.to_datetime(
                row["승차일시"], format="%Y%m%d%H%M%S", errors="coerce"
            )
            if pd.isna(board_dt):
                counters["invalid_board_time_rows"] += 1
                continue
            alight_dt = pd.to_datetime(
                row["하차일시"], format="%Y%m%d%H%M%S", errors="coerce"
            )
            alight_stop_local = row["정산사하차정류장ID"].strip()
            if pd.isna(alight_dt):
                counters["missing_alight_rows"] += 1
            if pd.notna(alight_dt) and alight_dt <= board_dt:
                alight_dt = pd.NaT
                alight_stop_local = ""
                counters["invalid_alight_time_rows"] += 1

            operator_id = row["정산사ID"]
            region_code = row["정산지역코드"]
            vehicle_key = source_key(operator_id, region_code, row["정산사차량ID"])
            board_stop_key = source_key(
                operator_id, region_code, row["정산사승차정류장ID"]
            )
            alight_stop_key = (
                source_key(operator_id, region_code, alight_stop_local)
                if alight_stop_local else ""
            )

            for passenger_index in range(1, passenger_count + 1):
                records.append({
                    # 원본 가상카드번호는 개인정보 최소화를 위해 출력·중간 DataFrame에
                    # 보관하지 않는다. 이 대체 ID는 행 식별과 그룹 크기 계산에만 쓰인다.
                    "card_id": f"{date}:{source_row_number}:{passenger_index}",
                    "board_dt": board_dt,
                    "alight_dt": alight_dt,
                    "vehicle_id": vehicle_key,
                    "route_id": route_key,
                    "route_settle_id": route_key,
                    "board_stop_id": board_stop_key,
                    "board_stop_settle_id": board_stop_key,
                    "alight_stop_id": alight_stop_key,
                    "usertype_code": row["이용자유형코드(시스템)"].strip(),
                    "bus_type_code": bus_type_code,
                })
            counters["selected_passenger_rows"] += passenger_count

    if not records:
        raise ValueError("선정 노선·버스 유형에 해당하는 TCD 탑승 기록이 없음")
    return pd.DataFrame.from_records(records), tcd_name, counters


def archive_entry_metadata(zip_file, names):
    """재처리 여부와 원본 동일성을 확인할 ZIP 항목 서명을 반환한다.

    CRC32는 ZIP 중앙 디렉터리에 이미 저장된 값이므로 대용량 CSV를 다시 풀어 읽지 않고도
    manifest에 원본 항목의 정체성을 기록할 수 있다.
    """
    return {
        name: {
            "crc32": f"{zip_file.getinfo(name).CRC:08x}",
            "uncompressed_bytes": zip_file.getinfo(name).file_size,
            "compressed_bytes": zip_file.getinfo(name).compress_size,
        }
        for name in names
    }


def build_metropolitan(zip_path, out_path, district_code="11290",
                       standard_id_zip=None, with_weather=False,
                       exclude_unmapped_standard_ids=False):
    """하루치 수도권 ZIP을 승객 단위 학습 Parquet과 manifest로 변환한다.

    처리 순서는 범위 노선 선정 → 전체 승객 적재 → 표준 ID 매핑 → 날씨 좌표 준비 →
    회차/FIFO/레이블 생성이다. 원본 ZIP은 항상 읽기 모드로 열고 결과는 별도 경로에 쓴다.
    ``exclude_unmapped_standard_ids``를 사용해도 누락행은 시뮬레이션까지 참여한 뒤 최종
    학습 출력에서만 제외된다.
    """
    zip_path = Path(zip_path).resolve()
    out_path = Path(out_path).resolve()
    date = archive_date(zip_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"원본 ZIP(읽기 전용): {zip_path}")
    print(f"대상 일자: {date}, 법정동코드 접두사: {district_code}")
    with ZipFile(zip_path, "r") as zip_file:
        scope = load_route_scope(zip_file, date, district_code)
        print(
            f"대상 정류장 {len(scope['target_stops']):,}개 / "
            f"경유 버스 노선 {len(scope['selected_routes']):,}개 / "
            f"노선-정류장 순번 {len(scope['seq_map']):,}개"
        )
        df, tcd_name, load_stats = load_metropolitan_tcd(
            zip_file, date, scope["selected_routes"]
        )
        print(
            f"TCD 전체 {load_stats['source_rows']:,}행 중 "
            f"선정 거래 {load_stats['selected_route_rows']:,}행, "
            f"이용자수 확장 후 {load_stats['selected_passenger_rows']:,}행"
        )
        entry_meta = archive_entry_metadata(zip_file, [
            scope["sttn_entry"], scope["routesttn_entry"], tcd_name
        ])

    # 표준 매핑 자료를 주지 않은 실행도 구조 점검에는 사용할 수 있다. 다만 복합 정산 ID는
    # 백엔드/AI의 국토부 표준 ID 계약과 다르므로 최종 학습본으로 사용하면 안 된다.
    id_namespace = "settlement-composite(operator:region:local-id)"
    id_mapping_meta = None
    stop_coords = scope["stop_coords"]
    coordinate_grid_conflicts = 0
    output_row_mask = None
    if standard_id_zip is not None:
        # 실제 선정 행에 등장한 ID만 사전에 저장하되, 매핑 ZIP 자체는 끝까지 스캔하여
        # 한 정산 ID가 여러 표준 ID로 바뀌는 충돌을 검출한다.
        required_routes = set(df["route_settle_id"])
        required_stops = set(df["board_stop_settle_id"])
        required_stops.update(
            stop_id for stop_id in df["alight_stop_id"] if stop_id
        )
        print(
            f"표준 ID 매핑 대상: 노선 {len(required_routes):,}개 / "
            f"정류장 {len(required_stops):,}개"
        )
        route_map, stop_map, id_mapping_meta = load_standard_id_maps(
            standard_id_zip,
            date,
            required_routes,
            required_stops,
            allow_missing=exclude_unmapped_standard_ids,
        )
        output_row_mask = apply_standard_ids(df, route_map, stop_map)
        excluded_rows = int((~output_row_mask).sum())
        if excluded_rows and not exclude_unmapped_standard_ids:
            raise ValueError(
                f"표준 ID가 없는 학습 행 {excluded_rows:,}개; "
                "--exclude-unmapped-standard-ids 필요"
            )
        id_mapping_meta["excluded_training_rows"] = excluded_rows
        stop_coords, coordinate_grid_conflicts = standardize_stop_coords(
            stop_coords, stop_map
        )
        id_namespace = "national-standard-id"
        print(
            "표준 ID 매핑 완료: "
            f"누락 노선 {id_mapping_meta['missing_route_count']:,}개 / "
            f"누락 정류장 {id_mapping_meta['missing_stop_count']:,}개 / "
            f"학습 출력 제외 {excluded_rows:,}행 / 충돌 0개"
        )

    weather_coords = None
    weather_meta = {"enabled": False}
    if with_weather:
        # 표준 ID로 변환한 뒤 좌표 키도 같은 ID 체계로 맞춘다. 출력에서 제외될 행의
        # 좌표는 조회하지 않아 불필요한 Open-Meteo 호출을 줄인다.
        weather_coords = stop_coords
        weather_frame = (
            df.loc[output_row_mask] if output_row_mask is not None else df
        )
        used_board_stops = set(weather_frame["board_stop_id"])
        missing_coordinate_count = len(used_board_stops - stop_coords.keys())
        print(
            f"날씨 좌표: {len(used_board_stops) - missing_coordinate_count:,}/"
            f"{len(used_board_stops):,}개 정류장"
        )
        weather_meta = {
            "enabled": True,
            "source": "Open-Meteo Historical Weather API",
            "coordinate_grid_degrees": 0.1,
            "used_board_stop_count": len(used_board_stops),
            "missing_coordinate_count": missing_coordinate_count,
            "standard_id_alias_grid_conflict_count": coordinate_grid_conflicts,
        }

    # 공용 처리기는 누락행을 포함한 상태로 회차와 FIFO를 계산하고 output_row_mask를
    # 마지막에 적용한다. 이 호출 순서는 입석 레이블의 의미를 보존하므로 변경하지 않는다.
    build_stats = build_loaded(
        df,
        scope["seq_map"],
        str(out_path),
        stop_coords=weather_coords,
        output_row_mask=output_row_mask,
    )
    manifest = {
        "pipeline_version": PIPELINE_VERSION,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_zip": str(zip_path),
        "source_zip_size_bytes": zip_path.stat().st_size,
        "source_zip_mtime_utc": datetime.fromtimestamp(
            zip_path.stat().st_mtime, timezone.utc
        ).isoformat(),
        "source_entries": entry_meta,
        "service_date": date,
        "district_code_prefix": district_code,
        "id_namespace": id_namespace,
        "standard_id_mapping": id_mapping_meta,
        "unmapped_standard_id_policy": (
            "include-in-simulation-exclude-from-training"
            if exclude_unmapped_standard_ids
            else "strict-fail"
        ),
        "weather": weather_meta,
        "sample_count_scope": "single-service-day; multi-day training requires recomputation",
        "target_bus_stop_count": len(scope["target_stops"]),
        "selected_route_count": len(scope["selected_routes"]),
        "route_stop_sequence_count": len(scope["seq_map"]),
        "duplicate_route_stop_rows_ignored": scope["duplicate_route_stops"],
        "loader": load_stats,
        "roster": build_stats,
    }
    manifest_path = out_path.with_suffix(out_path.suffix + ".manifest.json")
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"처리 기록: {manifest_path}")
    return manifest


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", help="DATA_YYYYMMDD.zip 경로")
    parser.add_argument("out_path", help="출력 roster_YYYYMMDD.parquet 경로")
    parser.add_argument(
        "--district-code",
        default="11290",
        help="법정동코드 접두사(기본값: 성북구 11290)",
    )
    parser.add_argument(
        "--standard-id-zip",
        help="같은 날짜의 27컬럼 DATA_YYYYMMDD.zip; 지정 시 표준 ID를 엄격히 적용",
    )
    parser.add_argument(
        "--with-weather",
        action="store_true",
        help="정류장 좌표 기준 Open-Meteo 과거 시간별 날씨를 연결",
    )
    parser.add_argument(
        "--exclude-unmapped-standard-ids",
        action="store_true",
        help="표준 ID 없는 행은 재차인원 계산에 포함하고 학습 출력에서만 제외",
    )
    return parser.parse_args(argv)


if __name__ == "__main__":
    args = parse_args(sys.argv[1:])
    build_metropolitan(
        args.zip_path,
        args.out_path,
        args.district_code,
        standard_id_zip=args.standard_id_zip,
        with_weather=args.with_weather,
        exclude_unmapped_standard_ids=args.exclude_unmapped_standard_ids,
    )
