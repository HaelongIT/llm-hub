package com.llmhub.chat.api;

import com.llmhub.auth.AccessTags;
import com.llmhub.chat.ChatEvent;
import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.ChatService;
import com.llmhub.chat.Message;
import com.llmhub.chat.config.ChatProperties;
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
	private final int maxQuestionChars;

	public ChatController(
			ChatService chatService,
			ChatHistoryRepository historyRepository,
			AppUserRepository userRepository,
			ChatProperties properties) {
		this.chatService = chatService;
		this.historyRepository = historyRepository;
		this.userRepository = userRepository;
		this.maxQuestionChars = properties.maxQuestionChars();
	}

	@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Object>> stream(
			@RequestBody ChatStreamRequest request, @AuthenticationPrincipal Jwt jwt) {

		// 질문이 비어 있으면 두 경로(신규/기존 세션)를 400으로 통일해 거부한다. 없으면 새 세션 경로의
		// titleOf(null)에서 NPE가 나 SSE 없이 raw 500이 된다 (리뷰 F5, S8-3).
		if (request.question() == null || request.question().isBlank()) {
			throw new QuestionRequiredException();
		}
		// 이력은 조립기가 예산으로 자르지만 지금 질문은 자를 수 없다. 거부한다 (PERF-5, SEC-4).
		if (request.question().length() > maxQuestionChars) {
			throw new QuestionTooLongException(request.question().length(), maxQuestionChars);
		}
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

	/**
	 * 세션은 사용자 소유다 (S2). 남의 세션 ID를 넘기면 그 사람의 대화 이력이 프롬프트에 실려 답변으로 새어 나가고, 그 세션에
	 * 메시지가 덧붙는다. 유출되는 것은 문서 조각이 아니라 <b>대화 이력</b>이므로 접근 태그 필터는 아무 소용이 없다.
	 *
	 * <p>세션 ID가 추측하기 어렵다는 것은 통제가 아니다 (SEC-2: 권한 검사 누락 가능 경로가 없어야 한다).
	 */
	private Session sessionOf(ChatStreamRequest request, String keycloakSubject) {
		UUID userId = userRepository.ensureExists(keycloakSubject);
		if (request.sessionId() == null) {
			return new Session(historyRepository.createSession(userId, titleOf(request.question())), List.of());
		}
		if (!historyRepository.isOwnedBy(request.sessionId(), userId)) {
			throw new SessionNotFoundException(request.sessionId());
		}
		return new Session(request.sessionId(), historyRepository.history(request.sessionId()));
	}

	static String titleOf(String question) {
		if (question.length() <= 60) {
			return question;
		}
		// 60번째 코드유닛이 서로게이트 쌍의 low half면 한 칸 앞에서 자른다. 쌍을 쪼개면 고립 서로게이트가
		// 제목에 남아 사이드바에 U+FFFD로 뜬다. 청킹처럼 글자 경계를 지킨다 (리뷰 F6, S2).
		int end = Character.isHighSurrogate(question.charAt(59)) ? 59 : 60;
		return question.substring(0, end);
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
