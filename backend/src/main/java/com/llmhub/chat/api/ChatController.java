package com.llmhub.chat.api;

import com.llmhub.auth.AccessTags;
import com.llmhub.chat.ChatEvent;
import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.ChatService;
import com.llmhub.chat.Message;
import com.llmhub.common.Blocking;
import com.llmhub.common.TraceId;
import com.llmhub.common.user.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 채팅 스트리밍 엔드포인트.
 *
 * <p><b>권한을 확인하지 않는다.</b> 인증과 접근 태그 확정은 필터체인에서 이미 끝났다 (S4). 컨트롤러가 권한을 확인하는
 * 구조라면 새 엔드포인트가 그것을 빼먹을 수 있다.
 *
 * <p>이벤트 타입 SSE로 {@code sources}/{@code text}/{@code error}/{@code done}을 내보낸다 (S6).
 * 클라이언트 BFF가 이것을 AI SDK 파트로 번역한다.
 *
 * <p>이력 조회는 블로킹 JPA다. 격리 스케줄러로 넘긴다 (S13).
 */
@RestController
public class ChatController {

	private final ChatService chatService;
	private final ChatHistoryRepository historyRepository;
	private final AppUserRepository userRepository;

	public ChatController(
			ChatService chatService, ChatHistoryRepository historyRepository, AppUserRepository userRepository) {
		this.chatService = chatService;
		this.historyRepository = historyRepository;
		this.userRepository = userRepository;
	}

	@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Object>> stream(
			@RequestBody ChatStreamRequest request, @AuthenticationPrincipal Jwt jwt) {

		String requesterId = jwt.getSubject();

		// 추적 ID는 필터가 이미 발급했다. 여기서 새로 만들면 응답 헤더의 값과 감사 기록의 값이 갈린다 (REL-3).
		return Mono.zip(TraceId.current(), AccessTags.current())
				.flatMapMany(
						context ->
								// 세션 확보와 이력 조회는 블로킹이다.
								Blocking.call(() -> sessionOf(request, requesterId))
										.flatMapMany(
												session ->
														chatService.stream(
																session.sessionId(),
																requesterId,
																request.question(),
																context.getT2(),
																session.history(),
																context.getT1())))
				.map(ChatController::toServerSentEvent);
	}

	private Session sessionOf(ChatStreamRequest request, String keycloakSubject) {
		UUID userId = userRepository.ensureExists(keycloakSubject);
		if (request.sessionId() == null) {
			return new Session(historyRepository.createSession(userId, titleOf(request.question())), List.of());
		}
		return new Session(request.sessionId(), historyRepository.history(request.sessionId()));
	}

	private static String titleOf(String question) {
		return question.length() <= 60 ? question : question.substring(0, 60);
	}

	private static ServerSentEvent<Object> toServerSentEvent(ChatEvent event) {
		return switch (event) {
			case ChatEvent.Sources sources -> sse("sources", sources.sources());
			case ChatEvent.Text text -> sse("text", text.delta());
			case ChatEvent.Error error -> sse("error", error.message());
			case ChatEvent.Done done -> sse("done", done.traceId());
		};
	}

	private static ServerSentEvent<Object> sse(String name, Object data) {
		return ServerSentEvent.builder().event(name).data(data).build();
	}

	private record Session(UUID sessionId, List<Message> history) {}
}
