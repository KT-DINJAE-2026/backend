# 내장 공휴일 목록

`kasi-holidays.csv`는 한국천문연구원 특일 정보 API(`getRestDeInfo`)에서 받은 2025~2027년
법정공휴일(`isHoliday=Y`) 목록이다. 형식은 `yyyyMMdd,공휴일명` (UTF-8, 헤더 없음).

- 원본: KASI 특일 정보 API, 2026-08-24 조회 (2025년 20건, 2026년 22건, 2027년 24건)
- 용도: KASI API(apis.data.go.kr)가 일부 클라우드 대역을 간헐 차단해 Cloud Run에서 연결
  타임아웃이 날 때의 폴백 (`KasiHolidayClient`, `app.holiday.fallback-resource`)
- 폴백 사용 시 짧은 TTL(`app.holiday.failure-cache-ttl`, 기본 1시간)로 캐시하고 이후 API를
  다시 시도하므로, API가 복구되면 자동으로 실시간 조회로 복귀한다
- 임시공휴일이 새로 지정되면 이 파일에 반영되지 않으므로, 매년 갱신하거나 시연·운영 기간에
  임시공휴일 발표가 있으면 수동으로 추가한다
