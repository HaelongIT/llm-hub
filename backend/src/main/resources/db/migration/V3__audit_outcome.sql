-- audit_log.outcome: 대화의 종료 사유 (R-5, REQ-AUDIT).
--
-- 감사 기록은 완료뿐 아니라 취소·오류에도 남는다. 클라이언트가 근거(restricted 문서 포함 가능)를
-- 받은 뒤 스트림을 취소하면 근거는 이미 전달됐으므로, "채팅 1회 → 감사 1건"을 지키려면 그 접근도
-- 기록되어야 한다. outcome이 완료/취소/오류를 구분해, 감사자가 answer가 잘린 것인지 알 수 있게 한다.
--
-- 기존 행은 모두 완료된 대화이므로 default COMPLETE. 이후 insert는 값을 명시한다.
alter table audit_log
    add column outcome varchar(16) not null default 'COMPLETE'
        check (outcome in ('COMPLETE', 'CANCELLED', 'ERROR'));
