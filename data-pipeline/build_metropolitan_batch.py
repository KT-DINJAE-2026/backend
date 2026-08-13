# -*- coding: utf-8 -*-
"""KDATA METROPOLITAN 일별 ZIP을 날짜 파티션 Parquet으로 일괄 처리한다.

대용량 전체 기간을 하나의 pandas DataFrame으로 합치지 않는다. 각 날짜의 원본 ZIP을
읽기 전용으로 처리하고 ``roster_YYYYMMDD.parquet``와 manifest를 남긴다. 일자별 파일의
``sample_count_route_segment_hour``는 그 하루 기준이므로 학습 전 전체 기간 기준으로
별도 재집계해야 한다.

사용법:
  python build_metropolitan_batch.py D:\\20250328_KDATA C:\\bus-standing-work\\roster-2024 \\
      --standard-id-source-dir D:\\20250313_KDATA
  python build_metropolitan_batch.py ... --date-from 20240401 --date-to 20240407
"""

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from build_metropolitan_roster import PIPELINE_VERSION, build_metropolitan


def discover_archives(source_dir, date_from=None, date_to=None):
    """파일명에서 서비스 일자를 읽어 요청 범위의 ZIP을 날짜순으로 반환한다.

    문자열 비교가 안전하도록 일자는 고정 길이 ``YYYYMMDD``만 허용한다. 다른 이름의 ZIP은
    사용자가 둔 보조 파일일 수 있으므로 오류로 만들지 않고 대상에서 제외한다.
    """
    found = []
    for path in Path(source_dir).glob("DATA_*.zip"):
        match = re.fullmatch(r"DATA_(\d{8})\.zip", path.name, re.IGNORECASE)
        if not match:
            continue
        date = match.group(1)
        if date_from and date < date_from:
            continue
        if date_to and date > date_to:
            continue
        found.append((date, path.resolve()))
    return sorted(found)


def source_signature(source_path):
    """manifest 재사용 판단에 필요한 원본 ZIP의 경로·크기·수정시각을 만든다."""
    if source_path is None:
        return None
    return {
        "source_zip": str(source_path),
        "source_zip_size_bytes": source_path.stat().st_size,
        "source_zip_mtime_utc": datetime.fromtimestamp(
            source_path.stat().st_mtime, timezone.utc
        ).isoformat(),
    }


def manifest_matches(manifest_path, source_path, district_code,
                     standard_id_path=None, with_weather=False,
                     exclude_unmapped_standard_ids=False):
    """기존 결과가 현재 입력·옵션으로 만든 것인지 보수적으로 판단한다.

    하나라도 다르거나 manifest를 읽지 못하면 False를 반환해 재생성한다. Parquet 존재만으로
    건너뛰지 않는 이유는 다른 원본, ID 정책 또는 날씨 옵션으로 만든 결과가 같은 파일명을
    가질 수 있기 때문이다.
    """
    if not manifest_path.exists():
        return False
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    mapping = manifest.get("standard_id_mapping")
    mapping_signature = None if mapping is None else {
        key: mapping.get(key) for key in (
            "source_zip", "source_zip_size_bytes", "source_zip_mtime_utc"
        )
    }
    expected_policy = (
        "include-in-simulation-exclude-from-training"
        if exclude_unmapped_standard_ids
        else "strict-fail"
    )
    actual_policy = manifest.get("unmapped_standard_id_policy")
    policy_matches = actual_policy == expected_policy
    if (
        exclude_unmapped_standard_ids
        and actual_policy in (None, "strict-fail")
        and mapping is not None
        and mapping.get("missing_route_count", 0) == 0
        and mapping.get("missing_stop_count", 0) == 0
        and mapping.get("excluded_training_rows", 0) == 0
    ):
        # 이전 strict 결과도 실제 누락이 0건이면 완전히 같은 학습 행을 가진다.
        policy_matches = True
    return (
        manifest.get("pipeline_version") == PIPELINE_VERSION
        and manifest.get("source_zip") == str(source_path)
        and manifest.get("source_zip_size_bytes") == source_path.stat().st_size
        and manifest.get("source_zip_mtime_utc") == datetime.fromtimestamp(
            source_path.stat().st_mtime, timezone.utc
        ).isoformat()
        and manifest.get("district_code_prefix") == district_code
        and mapping_signature == source_signature(standard_id_path)
        and manifest.get("weather", {}).get("enabled") == with_weather
        and policy_matches
    )


def build_batch(source_dir, out_dir, district_code="11290", date_from=None,
                date_to=None, overwrite=False, standard_id_source_dir=None,
                with_weather=False, exclude_unmapped_standard_ids=False):
    """날짜별 ZIP을 독립적으로 처리하고 재실행 가능한 일별 결과를 만든다.

    하루 단위 실패·재개가 가능하도록 전체 연도를 메모리에 합치지 않는다. 정상 manifest가
    있는 날짜만 건너뛰며, 누락되거나 옵션이 다른 날짜는 다시 생성한다.
    """
    archives = discover_archives(source_dir, date_from, date_to)
    if not archives:
        raise ValueError("처리할 DATA_YYYYMMDD.zip 파일이 없음")
    out_dir = Path(out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"대상: {len(archives)}일 ({archives[0][0]} ~ {archives[-1][0]})")

    built = 0
    skipped = 0
    for index, (date, source_path) in enumerate(archives, start=1):
        standard_id_path = None
        if standard_id_source_dir is not None:
            # 표준 ID는 노선 개편에 따라 날짜별로 달라질 수 있어 반드시 같은 날짜의
            # 27컬럼 ZIP을 짝지어 사용한다. 다른 날짜 자료로 대체하지 않는다.
            standard_id_path = (
                Path(standard_id_source_dir).resolve() / f"DATA_{date}.zip"
            )
            if not standard_id_path.is_file():
                raise ValueError(f"표준 ID 매핑 ZIP 누락: {standard_id_path}")
        out_path = out_dir / f"roster_{date}.parquet"
        manifest_path = out_path.with_suffix(out_path.suffix + ".manifest.json")
        if not overwrite and out_path.exists() and manifest_matches(
                manifest_path, source_path, district_code, standard_id_path,
                with_weather, exclude_unmapped_standard_ids):
            skipped += 1
            print(f"[{index}/{len(archives)} {date}] 검증된 기존 결과 → 건너뜀")
            continue
        print(f"\n[{index}/{len(archives)} {date}] {source_path.name}")
        build_metropolitan(
            source_path,
            out_path,
            district_code,
            standard_id_zip=standard_id_path,
            with_weather=with_weather,
            exclude_unmapped_standard_ids=exclude_unmapped_standard_ids,
        )
        built += 1

    print(f"\n완료: 생성 {built}일, 건너뜀 {skipped}일, 출력 {out_dir}")
    return {"archives": len(archives), "built": built, "skipped": skipped}


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_dir", help="DATA_YYYYMMDD.zip가 있는 디렉터리")
    parser.add_argument("out_dir", help="일자별 Parquet 출력 디렉터리")
    parser.add_argument("--district-code", default="11290")
    parser.add_argument("--date-from", help="시작일 YYYYMMDD")
    parser.add_argument("--date-to", help="종료일 YYYYMMDD")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument(
        "--standard-id-source-dir",
        help="날짜별 27컬럼 DATA_YYYYMMDD.zip이 있는 디렉터리",
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
    build_batch(
        args.source_dir,
        args.out_dir,
        district_code=args.district_code,
        date_from=args.date_from,
        date_to=args.date_to,
        overwrite=args.overwrite,
        standard_id_source_dir=args.standard_id_source_dir,
        with_weather=args.with_weather,
        exclude_unmapped_standard_ids=args.exclude_unmapped_standard_ids,
    )
