package com.llmhub.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.Message;
import com.llmhub.common.user.AppUserRepository;
import com.llmhub.support.PostgresInitializer;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * REQ-AUDIT 시나리오: audit_log 스키마에 FK 제약이 없다. 세션 삭제 후에도 감사 레코드가 유지된다.
 *
 * <p>S5: 감사 로그는 대화 이력과 <b>완전히 별도의 수명주기</b>를 갖는다. FK가 없는 것이 핵심이다 — FK가 있으면 사용자
 * 삭제가 감사 기록을 함께 지우거나 삭제 자체를 막는다.
 *
 * <p>구조를 information_schema로 직접 검증한다. "지금은 안 지워진다"가 아니라 "지워질 수 없다"를 확인한다.
 */
@SpringBootTest
@ContextConfiguration(initializers = PostgresInitializer.class)
class AuditIndependenceTest {

	@Autowired private DataSource dataSource;
	@Autowired private AppUserRepository users;
	@Autowired private ChatHistoryRepository history;
	@Autowired private AuditLogRepository auditLog;

	@Test
	@DisplayName("audit_log에는 어떤 외래키 제약도 없다")
	void audit_log에는_FK가_없다() {
		List<String> 외래키 = 외래키_제약("audit_log");

		assertThat(외래키)
				.as("FK가 있으면 사용자 삭제가 감사 기록을 함께 지우거나 삭제를 막는다 (S5)")
				.isEmpty();
	}

	@Test
	@DisplayName("chat_message는 세션 FK를 갖고 cascade 삭제된다")
	void chat_message는_세션에_묶인다() {
		assertThat(외래키_제약("chat_message")).as("이력은 세션에 종속된다").isNotEmpty();
	}

	@Test
	@DisplayName("세션을 삭제하면 메시지는 사라지고 감사 레코드는 남는다")
	void 세션_삭제가_감사에_영향을_주지_않는다() {
		UUID userId = users.ensureExists("keycloak-subject-" + UUID.randomUUID());
		UUID sessionId = history.createSession(userId, "연차 문의");
		history.append(sessionId, Message.user("연차휴가는?"), null);
		history.append(sessionId, Message.assistant("15일입니다."), "[{\"documentId\":\"doc-1\"}]");

		String traceId = "trace-" + UUID.randomUUID();
		auditLog.record(
				new AuditRecord(traceId, userId.toString(), "연차휴가는?", "15일입니다.", "[{\"documentId\":\"doc-1\"}]", com.llmhub.audit.AuditOutcome.COMPLETE));

		assertThat(history.history(sessionId)).hasSize(2);

		history.deleteSession(sessionId);

		assertThat(history.history(sessionId)).as("메시지는 cascade로 사라진다").isEmpty();
		assertThat(감사_레코드_수(traceId))
				.as("사용자가 대화를 지워도 감사 기록은 남는다 (S5)")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("사용자를 삭제해도 감사 레코드는 남는다")
	void 사용자_삭제가_감사에_영향을_주지_않는다() {
		UUID userId = users.ensureExists("keycloak-subject-" + UUID.randomUUID());
		String traceId = "trace-" + UUID.randomUUID();
		auditLog.record(new AuditRecord(traceId, userId.toString(), "질문", "답변", null, com.llmhub.audit.AuditOutcome.COMPLETE));

		new JdbcTemplate(dataSource).update("delete from app_user where id = ?", userId);

		assertThat(감사_레코드_수(traceId))
				.as("requester_id는 FK가 아니라 값 복사다 (S5)")
				.isEqualTo(1);
	}

	private List<String> 외래키_제약(String table) {
		return new JdbcTemplate(dataSource)
				.queryForList(
						"""
						select constraint_name from information_schema.table_constraints
						where table_name = ? and constraint_type = 'FOREIGN KEY'
						""",
						String.class,
						table);
	}

	private int 감사_레코드_수(String traceId) {
		Integer count =
				new JdbcTemplate(dataSource)
						.queryForObject("select count(*) from audit_log where trace_id = ?", Integer.class, traceId);
		return count == null ? 0 : count;
	}
}
