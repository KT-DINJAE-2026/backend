# -*- coding: utf-8 -*-
"""일자별 명부의 표본 수를 전체 학습기간 기준으로 다시 계산한다.

전체 Parquet을 한 DataFrame으로 합치지 않는다. 첫 번째 순회에서 날짜별 파일을
배치로 읽어 SQLite에 그룹 수를 누적하고, 두 번째 순회에서 각 Parquet의
``sample_count_route_segment_hour``만 교체해 별도 출력 디렉터리에 저장한다.
입력 파일은 수정하지 않는다.

사용법:
  python finalize_metropolitan_dataset.py <일자별 입력 디렉터리> <최종 출력 디렉터리>
"""

import argparse
import json
import re
import sqlite3
import sys
import tempfile
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


KEY_COLUMNS = ("route_id", "board_stop_id", "alight_stop_id", "hour")
COUNT_COLUMN = "sample_count_route_segment_hour"
FINALIZER_VERSION = "training-period-sample-count-v1"
# 3억 행 규모에서도 메모리를 일정하게 유지하도록 Parquet을 이 행 수 단위로 순회한다.
DEFAULT_BATCH_SIZE = 100_000


def discover_rosters(input_dir):
    """일별 명명 규칙을 만족하는 Parquet만 날짜순으로 반환한다."""
    found = []
    for path in Path(input_dir).glob("roster_*.parquet"):
        match = re.fullmatch(r"roster_(\d{8})\.parquet", path.name)
        if match:
            found.append((match.group(1), path.resolve()))
    return sorted(found)


def validate_inputs(rosters, output_dir, overwrite):
    """최종화에 필요한 스키마·manifest·ID 체계와 출력 안전성을 검증한다.

    입력과 출력 디렉터리를 같게 두지 못하게 하여 원본 일별 결과를 보호한다. 최종 표본 수는
    국토부 표준 ID 기준으로 합쳐야 하므로 복합 정산 ID 결과도 거부한다.
    """
    if not rosters:
        raise ValueError("roster_YYYYMMDD.parquet 입력 파일이 없음")
    output_dir = Path(output_dir).resolve()
    input_dir = rosters[0][1].parent
    if output_dir == input_dir:
        raise ValueError("입력 파일 보호를 위해 출력 디렉터리는 입력과 달라야 함")

    required = set(KEY_COLUMNS) | {COUNT_COLUMN}
    for date, path in rosters:
        schema = pq.ParquetFile(path).schema_arrow
        missing = required - set(schema.names)
        if missing:
            raise ValueError(f"{path.name} 필수 컬럼 누락: {sorted(missing)}")
        manifest_path = path.with_suffix(path.suffix + ".manifest.json")
        if not manifest_path.exists():
            raise ValueError(f"입력 manifest 누락: {manifest_path}")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("id_namespace") != "national-standard-id":
            raise ValueError(f"표준 ID가 아닌 입력은 최종화할 수 없음: {path.name}")
        target = output_dir / path.name
        if target.exists() and not overwrite:
            raise FileExistsError(f"출력 파일이 이미 존재함: {target}")
    return output_dir


def create_database(connection):
    """전체기간 그룹 수와 현재 배치 조회 키를 저장할 SQLite 테이블을 만든다."""
    # DB는 TemporaryDirectory 안에서 매번 재생성되며 원본 Parquet으로 복구 가능하다.
    # 그래서 내구성보다 처리 속도를 우선해 저널과 동기 쓰기를 끈다.
    connection.execute("PRAGMA journal_mode=OFF")
    connection.execute("PRAGMA synchronous=OFF")
    connection.execute("""
        CREATE TABLE counts (
            route_id TEXT NOT NULL,
            board_stop_id TEXT NOT NULL,
            alight_stop_id TEXT NOT NULL,
            hour INTEGER NOT NULL,
            sample_count INTEGER NOT NULL,
            PRIMARY KEY (route_id, board_stop_id, alight_stop_id, hour)
        ) WITHOUT ROWID
    """)
    connection.execute("""
        CREATE TEMP TABLE needed_keys (
            route_id TEXT NOT NULL,
            board_stop_id TEXT NOT NULL,
            alight_stop_id TEXT NOT NULL,
            hour INTEGER NOT NULL,
            PRIMARY KEY (route_id, board_stop_id, alight_stop_id, hour)
        ) WITHOUT ROWID
    """)


def batch_keys(batch):
    """Arrow 배치에서 SQLite와 동일한 표본 집계 키를 Python 튜플로 만든다.

    Parquet의 NULL 하차 정류장은 SQLite NOT NULL 기본키에 넣을 수 없으므로 빈 문자열로
    정규화한다. 집계와 조회 양쪽이 같은 변환을 사용해야 한다.
    """
    columns = {
        name: batch.column(batch.schema.get_field_index(name)).to_pylist()
        for name in KEY_COLUMNS
    }
    return [
        (route, board, alight or "", int(hour))
        for route, board, alight, hour in zip(
            columns["route_id"],
            columns["board_stop_id"],
            columns["alight_stop_id"],
            columns["hour"],
        )
    ]


def aggregate_training_counts(connection, rosters, batch_size):
    """첫 번째 순회에서 전체기간의 노선·OD·시간대별 행 수를 누적한다."""
    upsert = """
        INSERT INTO counts VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(route_id, board_stop_id, alight_stop_id, hour)
        DO UPDATE SET sample_count = sample_count + excluded.sample_count
    """
    total_rows = 0
    for index, (date, path) in enumerate(rosters, start=1):
        # 같은 날짜 안에서 먼저 집계한 뒤 그룹별 한 행만 SQLite에 UPSERT한다. 승객 한 명마다
        # DB 쓰기를 수행하는 것보다 I/O와 트랜잭션 비용이 크게 줄어든다.
        daily_counts = {}
        parquet = pq.ParquetFile(path)
        for batch in parquet.iter_batches(
            batch_size=batch_size, columns=list(KEY_COLUMNS)
        ):
            for key in batch_keys(batch):
                daily_counts[key] = daily_counts.get(key, 0) + 1
            total_rows += batch.num_rows
        connection.executemany(
            upsert, ((*key, count) for key, count in daily_counts.items())
        )
        connection.commit()
        print(
            f"[집계 {index}/{len(rosters)} {date}] "
            f"{parquet.metadata.num_rows:,}행 / 일별 그룹 {len(daily_counts):,}개"
        )
    group_count = connection.execute("SELECT COUNT(*) FROM counts").fetchone()[0]
    return total_rows, group_count


def lookup_counts(connection, keys):
    """현재 Arrow 배치에 실제로 등장한 키의 전체기간 표본 수만 조회한다."""
    unique_keys = set(keys)
    # 거대한 counts 전체를 Python으로 읽지 않고 임시 키 테이블과 JOIN한다. 이 구조 덕분에
    # 데이터셋 크기와 무관하게 현재 배치에 필요한 결과만 메모리에 올라온다.
    connection.execute("DELETE FROM needed_keys")
    connection.executemany(
        "INSERT INTO needed_keys VALUES (?, ?, ?, ?)", unique_keys
    )
    rows = connection.execute("""
        SELECT c.route_id, c.board_stop_id, c.alight_stop_id, c.hour,
               c.sample_count
        FROM counts c
        INNER JOIN needed_keys k
          ON c.route_id = k.route_id
         AND c.board_stop_id = k.board_stop_id
         AND c.alight_stop_id = k.alight_stop_id
         AND c.hour = k.hour
    """).fetchall()
    result = {(route, board, alight, hour): count
              for route, board, alight, hour, count in rows}
    if len(result) != len(unique_keys):
        raise ValueError(
            f"전체기간 표본 수 조회 누락: 요청 {len(unique_keys):,}개, "
            f"발견 {len(result):,}개"
        )
    return result


def rewrite_roster(connection, input_path, output_path, batch_size):
    """두 번째 순회에서 표본 수 컬럼만 교체해 새 Parquet을 원자적으로 저장한다."""
    parquet = pq.ParquetFile(input_path)
    schema = parquet.schema_arrow
    count_index = schema.get_field_index(COUNT_COLUMN)
    # 완성 전 파일이 정상 Parquet처럼 보이지 않게 .tmp에 쓴 뒤 같은 볼륨에서 교체한다.
    # 예외가 발생하면 임시 파일만 삭제하고 입력과 기존 정상 출력은 건드리지 않는다.
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    writer = pq.ParquetWriter(temp_path, schema, compression="zstd")
    try:
        for batch in parquet.iter_batches(batch_size=batch_size):
            keys = batch_keys(batch)
            counts = lookup_counts(connection, keys)
            values = pa.array([counts[key] for key in keys], type=pa.int64())
            writer.write_batch(batch.set_column(count_index, COUNT_COLUMN, values))
    except Exception:
        writer.close()
        temp_path.unlink(missing_ok=True)
        raise
    else:
        writer.close()
        temp_path.replace(output_path)


def write_output_manifest(input_path, output_path, dates, total_rows,
                          group_count):
    """일별 처리 이력을 보존하면서 전체기간 최종화 정보를 덧붙인다."""
    input_manifest_path = input_path.with_suffix(
        input_path.suffix + ".manifest.json"
    )
    manifest = json.loads(input_manifest_path.read_text(encoding="utf-8"))
    manifest["sample_count_scope"] = (
        f"training-period:{dates[0]}..{dates[-1]} ({len(dates)} service days)"
    )
    manifest["sample_count_finalization"] = {
        "version": FINALIZER_VERSION,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "date_from": dates[0],
        "date_to": dates[-1],
        "service_day_count": len(dates),
        "dataset_row_count": total_rows,
        "route_segment_hour_group_count": group_count,
    }
    output_manifest_path = output_path.with_suffix(
        output_path.suffix + ".manifest.json"
    )
    output_manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def finalize_dataset(input_dir, output_dir, overwrite=False,
                     batch_size=DEFAULT_BATCH_SIZE):
    """일별 데이터셋의 표본 수를 전체 학습기간 기준으로 최종화한다.

    첫 순회는 SQLite 집계, 두 번째 순회는 Parquet 재작성이다. 다른 17개 컬럼은 그대로
    복사하며 ``sample_count_route_segment_hour``만 전체기간 값으로 교체한다.
    """
    rosters = discover_rosters(input_dir)
    output_dir = validate_inputs(rosters, output_dir, overwrite)
    output_dir.mkdir(parents=True, exist_ok=True)
    dates = [date for date, _ in rosters]
    print(f"최종화 대상: {len(rosters)}일 ({dates[0]} ~ {dates[-1]})")

    with tempfile.TemporaryDirectory(prefix="metropolitan-counts-") as temp_dir:
        database_path = Path(temp_dir) / "counts.sqlite"
        with closing(sqlite3.connect(database_path)) as connection:
            create_database(connection)
            total_rows, group_count = aggregate_training_counts(
                connection, rosters, batch_size
            )
            print(
                f"전체 집계: {total_rows:,}행 / "
                f"노선·OD·시간대 그룹 {group_count:,}개"
            )
            for index, (date, input_path) in enumerate(rosters, start=1):
                output_path = output_dir / input_path.name
                rewrite_roster(
                    connection, input_path, output_path, batch_size
                )
                write_output_manifest(
                    input_path, output_path, dates, total_rows, group_count
                )
                print(f"[출력 {index}/{len(rosters)} {date}] {output_path}")

    dataset_manifest = {
        "finalizer_version": FINALIZER_VERSION,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "input_directory": str(Path(input_dir).resolve()),
        "date_from": dates[0],
        "date_to": dates[-1],
        "service_day_count": len(dates),
        "row_count": total_rows,
        "route_segment_hour_group_count": group_count,
        "files": [path.name for _, path in rosters],
    }
    (output_dir / "dataset.manifest.json").write_text(
        json.dumps(dataset_manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return dataset_manifest


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_dir")
    parser.add_argument("output_dir")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    return parser.parse_args(argv)


if __name__ == "__main__":
    args = parse_args(sys.argv[1:])
    finalize_dataset(
        args.input_dir,
        args.output_dir,
        overwrite=args.overwrite,
        batch_size=args.batch_size,
    )
