package com.llmhub.chat.api;

import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.Message;
import com.llmhub.chat.SessionSummary;
import com.llmhub.common.Blocking;
import com.llmhub.common.user.AppUserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 세션 관리 (S2). 세션은 사용자 소유다.
 *
 * <p>소유권 확인은 데이터 수준의 불변식이다. 남의 세션은 <b>존재하지 않는 것처럼</b> 취급한다 — 403은 그 세션이 있다는
 * 사실을 알려준다.
 *
 * <p>모든 JPA 접근은 블로킹이다. 격리 스케줄러로 넘긴다 (S13).
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

	private final ChatHistoryRepository historyRepository;
	private final AppUserRepository userRepository;

	public SessionController(ChatHistoryRepository historyRepository, AppUserRepository userRepository) {
		this.historyRepository = historyRepository;
		this.userRepository = userRepository;
	}

	@PostMapping
	public Mono<Map<String, String>> create(
			@RequestBody(required = false) CreateSessionRequest request, @AuthenticationPrincipal Jwt jwt) {
		String title = request == null || request.title() == null || request.title().isBlank() ? "새 대화" : request.title();
		return Blocking.call(
				() -> {
					UUID userId = userRepository.ensureExists(jwt.getSubject());
					return Map.of("id", historyRepository.createSession(userId, title).toString());
				});
	}

	@GetMapping
	public Mono<List<SessionSummary>> list(@AuthenticationPrincipal Jwt jwt) {
		return Blocking.call(() -> historyRepository.sessionsOf(userRepository.ensureExists(jwt.getSubject())));
	}

	@GetMapping("/{sessionId}/messages")
	public Mono<ResponseEntity<List<Message>>> messages(
			@PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt) {
		return Blocking.call(
				() -> {
					UUID userId = userRepository.ensureExists(jwt.getSubject());
					if (!historyRepository.isOwnedBy(sessionId, userId)) {
						return ResponseEntity.notFound().build();
					}
					return ResponseEntity.ok(historyRepository.history(sessionId));
				});
	}

	@DeleteMapping("/{sessionId}")
	public Mono<ResponseEntity<Void>> delete(@PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt) {
		return Blocking.call(
				() -> {
					UUID userId = userRepository.ensureExists(jwt.getSubject());
					if (!historyRepository.isOwnedBy(sessionId, userId)) {
						return ResponseEntity.notFound().build();
					}
					historyRepository.deleteSession(sessionId);
					// 메시지는 cascade로 사라진다. 감사 로그는 남는다 (S5).
					return ResponseEntity.status(HttpStatus.NO_CONTENT).<Void>build();
				});
	}

	record CreateSessionRequest(String title) {}
}
