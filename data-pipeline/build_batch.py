# -*- coding: utf-8 -*-
"""여러 날짜의 TCD 파일을 일괄 처리해 일자별 명부 + 통합 명부를 생성.

- <TCD디렉터리> 아래에서 DWTCD_YYYYMMDD*.txt 를 재귀적으로 찾아 날짜순으로 처리
- ROUTESTTN(순번)·STTN(좌표)은 파일 하나를 주면 전 일자에 공용, 디렉터리를 주면
  일자별 파일(*_YYYYMMDD.dat)을 자동 매칭 (같은 날짜 없으면 이전 날짜로 폴백)
- 일자별 출력: <출력디렉터리>/roster_YYYYMMDD.parquet (이미 있으면 건너뜀)
- 마지막에 전체를 병합한 roster_all.parquet 생성

사용법:
  python build_batch.py <TCD디렉터리> <ROUTESTTN파일|디렉터리> <STTN파일|디렉터리> <출력디렉터리>
"""
import re
import sys
from pathlib import Path

import pyarrow.parquet as pq
import pyarrow as pa

from build_roster import build, load_stop_sequences
from weather import load_stop_coords


def find_tcd_files(tcd_dir):
    """DWTCD_YYYYMMDD*.txt → [(날짜, 경로)] 날짜순."""
    found = {}
    for p in sorted(Path(tcd_dir).rglob("DWTCD_*.txt")):
        m = re.search(r"DWTCD_(\d{8})", p.name)
        if m:
            found[m.group(1)] = p  # 같은 날짜 중복 시 경로 정렬상 마지막 것(결정적)
    return sorted(found.items())


def resolve_master(path_or_dir, date):
    """마스터 경로 결정. 디렉터리면 해당 일자 파일, 없으면 직전 일자로 폴백."""
    p = Path(path_or_dir)
    if p.is_file():
        return p
    dated = {}
    for f in p.rglob("*.dat"):
        m = re.search(r"_(\d{8})\.dat$", f.name)
        if m:
            dated[m.group(1)] = f
    if not dated:
        sys.exit(f"마스터 파일 없음: {path_or_dir} (*_YYYYMMDD.dat 패턴)")
    candidates = [d for d in dated if d <= date]
    key = max(candidates) if candidates else min(dated)
    return dated[key]


class MasterCache:
    """같은 마스터 파일을 두 번 파싱하지 않도록 직전 로드 결과를 보관."""

    def __init__(self, loader):
        self.loader = loader
        self.path = None
        self.value = None

    def get(self, path):
        if path != self.path:
            self.path, self.value = path, self.loader(str(path))
        return self.value


def main(tcd_dir, routesttn_arg, sttn_arg, out_dir):
    files = find_tcd_files(tcd_dir)
    if not files:
        sys.exit(f"TCD 파일 없음: {tcd_dir} (DWTCD_YYYYMMDD*.txt 패턴)")
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"대상: {len(files)}일 ({files[0][0]} ~ {files[-1][0]})")

    seq_cache = MasterCache(load_stop_sequences)
    coord_cache = MasterCache(load_stop_coords)

    outputs = []
    for date, tcd_path in files:
        out_path = out_dir / f"roster_{date}.parquet"
        outputs.append(out_path)
        if out_path.exists():
            print(f"\n[{date}] 이미 존재 → 건너뜀 ({out_path.name})")
            continue
        routesttn_path = resolve_master(routesttn_arg, date)
        sttn_path = resolve_master(sttn_arg, date)
        print(f"\n[{date}] {tcd_path.name} "
              f"(마스터: {routesttn_path.name}, {sttn_path.name})")
        build(str(tcd_path), str(routesttn_path), str(out_path),
              seq_map=seq_cache.get(routesttn_path),
              stop_coords=coord_cache.get(sttn_path))

    merged_path = out_dir / "roster_all.parquet"
    tables = [pq.read_table(p) for p in outputs]
    merged = pa.concat_tables(tables)
    # sample_count는 일자별 파일에선 "그날 하루" 기준이므로 전체 기간 기준으로 재계산
    mdf = merged.to_pandas()
    mdf["sample_count_route_segment_hour"] = mdf.groupby(
        ["route_id", "board_stop_id", "alight_stop_id", "hour"], dropna=False
    )["trip_round_id"].transform("size")
    merged = pa.Table.from_pandas(mdf, schema=merged.schema, preserve_index=False)
    pq.write_table(merged, merged_path, compression="zstd")
    print(f"\n통합본: {merged.num_rows:,}행 ({len(outputs)}일, sample_count 전기간 재계산) → {merged_path}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
