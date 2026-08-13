import csv
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZipFile

import pyarrow.parquet as pq


PIPELINE_DIR = Path(__file__).resolve().parents[1]
if str(PIPELINE_DIR) not in sys.path:
    sys.path.insert(0, str(PIPELINE_DIR))

from build_metropolitan_roster import (  # noqa: E402
    apply_standard_ids,
    build_metropolitan,
    load_metropolitan_tcd,
    load_route_scope,
    load_standard_id_maps,
)
from build_metropolitan_batch import build_batch  # noqa: E402
from build_roster import build_loaded  # noqa: E402


def csv_text(header, rows):
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return output.getvalue()


def raw_tcd_row(route_standard, route_settlement, board_standard,
                board_settlement, alight_standard, alight_settlement):
    row = [""] * 27
    row[0] = "20240401"
    row[1] = "08"
    row[4] = "MM"
    row[12] = route_standard
    row[13] = route_settlement
    row[16] = board_standard
    row[17] = board_settlement
    row[19] = alight_standard
    row[20] = alight_settlement
    return row


class MetropolitanRosterTests(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.zip_path = self.root / "DATA_20240401.zip"
        with ZipFile(self.zip_path, "w", compression=ZIP_DEFLATED) as archive:
            archive.writestr(
                "TB_KTS_STTN_20240401.csv",
                csv_text(
                    [
                        "운행일자", "정산사코드", "지역코드", "교통수단구분", "정류장ID",
                        "정류장명칭", "법정동코드", "정류장ARS번호", "정류장GPSY좌표",
                        "정류장GPSX좌표",
                    ],
                    [
                        ["20240401", "08", "MM", "B", "100", "성북구정류장", "1129010100", "1", "37.1", "127.1"],
                        ["20240401", "08", "MM", "B", "101", "종로구정류장", "1111010100", "2", "37.2", "127.2"],
                        ["20240401", "08", "MM", "B", "200", "강남구정류장", "1168010100", "3", "37.3", "127.3"],
                    ],
                ),
            )
            archive.writestr(
                "TB_KTS_ROUTESTTN_20240401.csv",
                csv_text(
                    [
                        "운행일자", "정산사ID", "정산지역코드", "노선ID", "노선명칭",
                        "교통수단구분", "정류장순번", "정류장ID", "정류장명칭",
                        "정류장Y좌표", "정류장X좌표", "정류장ARS번호", "노선누적거리",
                        "정류장거리",
                    ],
                    [
                        ["20240401", "08", "MM", "R1", "성북경유", "B", "1", "101", "종로구정류장", "37.2", "127.2", "2", "0", "0"],
                        ["20240401", "08", "MM", "R1", "성북경유", "B", "2", "100", "성북구정류장", "37.1", "127.1", "1", "1000", "1000"],
                        ["20240401", "08", "MM", "R2", "비경유", "B", "1", "200", "강남구정류장", "37.3", "127.3", "3", "0", "0"],
                    ],
                ),
            )
            archive.writestr(
                "TB_KTS_DWTCD_METROPOLITAN_20240401.csv",
                csv_text(
                    [
                        "운행일자", "정산사ID", "가상카드번호", "정산지역코드", "카드구분코드",
                        "정산사차량ID", "교통수단코드", "정산사노선ID", "승차일시",
                        "정산사승차정류장ID", "하차일시", "정산사하차정류장ID",
                        "트랜잭션ID", "환승건수", "이용자유형코드(시스템)", "이용자수",
                        "이용거리", "탑승시간",
                    ],
                    [
                        # 성북구 밖에서 탄 승객도 성북구 경유 노선 전체 재차인원에 포함돼야 한다.
                        ["20240401", "08", "SECRET_CARD", "MM", "C", "V1", "105", "R1", "20240401080000", "101", "20240401082000", "100", "1", "0", "04", "2", "1000", "1200"],
                        ["20240401", "08", "OTHER_CARD", "MM", "C", "V2", "105", "R2", "20240401090000", "200", "20240401091000", "200", "2", "0", "01", "1", "500", "600"],
                    ],
                ),
            )
        self.standard_id_dir = self.root / "standard-ids"
        self.standard_id_dir.mkdir()
        self.standard_id_zip = self.standard_id_dir / "DATA_20240401.zip"
        with ZipFile(
            self.standard_id_zip, "w", compression=ZIP_DEFLATED
        ) as archive:
            raw = io.StringIO(newline="")
            writer = csv.writer(raw, lineterminator="\n")
            writer.writerow(raw_tcd_row(
                "STANDARD_ROUTE_1", "R1",
                "STANDARD_STOP_101", "101",
                "STANDARD_STOP_100", "100",
            ))
            archive.writestr("TB_KTS_DWTCD_20240401_01.csv", raw.getvalue())

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_selects_all_passengers_on_routes_serving_target_district(self):
        with ZipFile(self.zip_path, "r") as archive:
            scope = load_route_scope(archive, "20240401", "11290")
            frame, _, stats = load_metropolitan_tcd(
                archive, "20240401", scope["selected_routes"]
            )

        self.assertEqual({"08:MM:R1"}, scope["selected_routes"])
        self.assertEqual(2, len(frame))
        self.assertEqual({"08:MM:101"}, set(frame["board_stop_id"]))
        self.assertEqual(2, stats["selected_passenger_rows"])
        self.assertNotIn("SECRET_CARD", set(frame["card_id"]))

    def test_builds_roster_and_manifest_without_writing_source_archive(self):
        before = (self.zip_path.stat().st_size, self.zip_path.stat().st_mtime_ns)
        out_path = self.root / "nested" / "roster_20240401.parquet"

        manifest = build_metropolitan(self.zip_path, out_path, "11290")

        after = (self.zip_path.stat().st_size, self.zip_path.stat().st_mtime_ns)
        table = pq.read_table(out_path)
        self.assertEqual(before, after)
        self.assertEqual(2, table.num_rows)
        self.assertEqual(1, manifest["selected_route_count"])
        self.assertEqual(2, manifest["roster"]["rows"])
        self.assertTrue(out_path.with_suffix(".parquet.manifest.json").exists())

    def test_maps_all_model_ids_to_national_standard_ids(self):
        before = (
            self.standard_id_zip.stat().st_size,
            self.standard_id_zip.stat().st_mtime_ns,
        )
        out_path = self.root / "standard" / "roster_20240401.parquet"

        manifest = build_metropolitan(
            self.zip_path,
            out_path,
            "11290",
            standard_id_zip=self.standard_id_zip,
        )

        frame = pq.read_table(out_path).to_pandas()
        self.assertEqual({"STANDARD_ROUTE_1"}, set(frame["route_id"]))
        self.assertEqual({"STANDARD_STOP_101"}, set(frame["board_stop_id"]))
        self.assertEqual({"STANDARD_STOP_100"}, set(frame["alight_stop_id"]))
        self.assertEqual("national-standard-id", manifest["id_namespace"])
        self.assertEqual(1, manifest["standard_id_mapping"]["route_mapping_count"])
        self.assertEqual(2, manifest["standard_id_mapping"]["stop_mapping_count"])
        self.assertEqual(
            before,
            (self.standard_id_zip.stat().st_size,
             self.standard_id_zip.stat().st_mtime_ns),
        )

    def test_rejects_incomplete_standard_id_mapping(self):
        with self.assertRaisesRegex(ValueError, "표준 ID 매핑 누락"):
            load_standard_id_maps(
                self.standard_id_zip,
                "20240401",
                {"08:MM:R1", "08:MM:UNKNOWN_ROUTE"},
                {"08:MM:101"},
            )

    def test_weather_uses_coordinates_keyed_by_standard_stop_id(self):
        out_path = self.root / "weather" / "roster_20240401.parquet"

        def fake_attach_weather(frame, sttn_path=None, coords=None):
            self.assertIn("STANDARD_STOP_101", coords)
            result = frame.copy()
            result["weather"] = "맑음"
            return result

        with patch("weather.attach_weather", side_effect=fake_attach_weather):
            manifest = build_metropolitan(
                self.zip_path,
                out_path,
                standard_id_zip=self.standard_id_zip,
                with_weather=True,
            )

        frame = pq.read_table(out_path).to_pandas()
        self.assertEqual({"맑음"}, set(frame["weather"]))
        self.assertTrue(manifest["weather"]["enabled"])
        self.assertEqual(0, manifest["weather"]["missing_coordinate_count"])

    def test_unmapped_stop_participates_in_calculation_but_not_output(self):
        with ZipFile(self.zip_path, "r") as archive:
            scope = load_route_scope(archive, "20240401", "11290")
            frame, _, _ = load_metropolitan_tcd(
                archive, "20240401", scope["selected_routes"]
            )
        frame.loc[frame.index[1], "board_stop_settle_id"] = "08:MM:UNKNOWN"
        frame.loc[frame.index[1], "board_stop_id"] = "08:MM:UNKNOWN"
        keep = apply_standard_ids(
            frame,
            {"08:MM:R1": "STANDARD_ROUTE_1"},
            {
                "08:MM:101": "STANDARD_STOP_101",
                "08:MM:100": "STANDARD_STOP_100",
            },
        )
        out_path = self.root / "excluded" / "roster.parquet"

        stats = build_loaded(
            frame,
            scope["seq_map"],
            out_path,
            output_row_mask=keep,
        )

        output = pq.read_table(out_path).to_pandas()
        self.assertEqual([True, False], keep.tolist())
        self.assertEqual(1, len(output))
        self.assertEqual(1, stats["excluded_output_rows"])
        self.assertEqual({"STANDARD_STOP_101"}, set(output["board_stop_id"]))

    def test_batch_skips_only_a_result_with_matching_manifest(self):
        out_dir = self.root / "batch"
        first = build_batch(self.root, out_dir)
        second = build_batch(self.root, out_dir)

        self.assertEqual({"archives": 1, "built": 1, "skipped": 0}, first)
        self.assertEqual({"archives": 1, "built": 0, "skipped": 1}, second)

    def test_batch_validates_standard_id_source_before_skipping(self):
        out_dir = self.root / "standard-batch"
        first = build_batch(
            self.root,
            out_dir,
            standard_id_source_dir=self.standard_id_dir,
        )
        second = build_batch(
            self.root,
            out_dir,
            standard_id_source_dir=self.standard_id_dir,
        )

        self.assertEqual({"archives": 1, "built": 1, "skipped": 0}, first)
        self.assertEqual({"archives": 1, "built": 0, "skipped": 1}, second)
        manifest_path = (
            out_dir / "roster_20240401.parquet.manifest.json"
        )
        self.assertTrue(manifest_path.exists())


if __name__ == "__main__":
    unittest.main()
