package com.llmhub.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmhub.audit.AuditLogRepository;
import com.llmhub.audit.AuditOutcome;
import com.llmhub.audit.AuditRecord;
import com.llmhub.audit.AuditScope;
import com.llmhub.common.Blocking;
import com.llmhub.search.SearchService;
import com.llmhub.search.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 질문을 받아 근거 기반 답변을 이벤트 SSE로 스트리밍한다.
 *
 * <p><b>검색은 Advisor 안이 아니라 여기서 명시적으로 호출한다.</b> S6가 "근거는 서버 검색 결과에서만 나온다"를
 * 요구하므로 그 사실이 코드 구조에 드러나야 한다. 덕분에 {@code sources}를 첫 {@code text} 토큰보다 먼저 확정
 * 발행할 수 있다.
 *
 * <p><b>스트림은 저장을 기다리지 않는다.</b> 이력·감사 저장은 격리 스케줄러로 넘기고 구독만 한다 (S13). 저장 실패는
 * 로깅할 뿐 사용자 응답을 되돌리지 않는다 (REL-2). 감사 저장 실패는 경고 로그로 눈에 띄게 한다.
 *
 * <p>권한을 판단하지 않는다. AUTH가 앞단 게이트에서 확정한 태그를 소비만 한다 (S4).
 */
public final class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);
	private static final ObjectMapper JSON = new ObjectMapper();

	/** 사용자에게 보이는 유일한 실패 문구. 원인은 추적 ID로 로그에서 찾는다 (SEC-3, REL-3). */
	private static final String USER_FACING_ERROR = "요청을 처리하지 못했습니다. 추적 ID: %s";

	private static final String SYSTEM_PROMPT =
			"""
			당신은 사내 문서 검색 결과를 근거로 답하는 도우미입니다.
			아래 [근거]에 있는 내용만으로 답하십시오. 근거에 없는 내용은 지어내지 말고 모른다고 답하십시오.

			[근거]
			%s
			""";

	private final SearchService searchService;
	private final ChatClient chatClient;
	private final ContextAssembler contextAssembler;
	private final ChatHistoryRepository historyRepository;
	private final AuditLogRepository auditLogRepository;
	private final AuditScope auditScope;
	private final java.time.Duration streamIdleTimeout;

	/**
	 * @param streamIdleTimeout 토큰 <b>사이</b>의 유휴 상한. 게이트웨이가 조용히 멈추면 에러가 emit되지 않아
	 *     {@code onErrorResume}이 돌지 않는다 — done도 error도 나가지 않아 사용자가 무한히 기다린다 (REL-1).
	 *     전체 응답 상한이 아니므로 토큰이 계속 오는 긴 답변은 잘리지 않는다.
	 */
	public ChatService(
			SearchService searchService,
			ChatClient chatClient,
			ContextAssembler contextAssembler,
			ChatHistoryRepository historyRepository,
			AuditLogRepository auditLogRepository,
			AuditScope auditScope,
			java.time.Duration streamIdleTimeout) {
		this.searchService = searchService;
		this.chatClient = chatClient;
		this.contextAssembler = contextAssembler;
		this.historyRepository = historyRepository;
		this.auditLogRepository = auditLogRepository;
		this.auditScope = auditScope;
		this.streamIdleTimeout = streamIdleTimeout;
	}

	/**
	 * @param accessTags AUTH가 확정한 태그. 재계산하지 않는다 (S4).
	 * @param history 세션 이력. 최근 N턴만 컨텍스트에 실린다 (E2).
	 */
	public Flux<ChatEvent> stream(
			UUID sessionId,
			String requesterId,
			String question,
			Set<String> accessTags,
			List<Message> history,
			String traceId) {

		StringBuilder answer = new StringBuilder();

		return Blocking.call(() -> searchService.search(question, accessTags))
				.flatMapMany(
						sources -> {
							// 질문·응답 원문은 로그에 남기지 않는다 (SEC-3). 전문은 감사 로그가 맡는다 (S5, E5).
							log.info("LLM 호출 시작 traceId={} sources={} questionChars={}", traceId, sources.size(), question.length());
							return Flux.concat(
											// 근거 먼저. LLM이 한 글자도 내기 전에 확정된다 (S6).
											Mono.<ChatEvent>just(new ChatEvent.Sources(sources)),
											answerStream(question, history, sources, answer),
											Mono.<ChatEvent>just(new ChatEvent.Done(traceId)))
									// 이력은 성공적으로 끝난 대화만 남긴다. 실패·취소한 대화는 이력에 남지 않는다.
									.doOnComplete(
											() -> {
												log.info("응답 완료 traceId={} answerChars={}", traceId, answer.length());
												persistHistory(sessionId, question, snapshot(answer), sources, traceId);
											})
									// 감사는 취소·오류를 포함해 항상 남긴다. 취소는 doOnComplete를 실행시키지 않으므로,
									// 근거가 이미 전달된 대화(S6)가 감사에서 사라지는 것을 막는다 (R-5). doFinally는
									// complete·cancel·error 모든 종료에서 정확히 한 번 돈다.
									.doFinally(
											signal ->
													recordAudit(
															requesterId,
															question,
															snapshot(answer),
															sources,
															traceId,
															outcomeOf(signal)));
						})
				.onErrorResume(
						error -> {
							// 깨끗한 실패. 자동 우회·페일오버 없이 error 이벤트로 스트림을 닫는다 (S8-3).
							//
							// 예외 문구를 그대로 실어 보내지 않는다. ES 인덱스명·게이트웨이 주소 같은 내부 사정이
							// 브라우저로 나간다 (SEC-3). 원인은 로그에 있고, 사용자는 추적 ID로 신고한다.
							log.error("채팅 스트림 실패 traceId={}", traceId, error);
							return Flux.just(new ChatEvent.Error(USER_FACING_ERROR.formatted(traceId)));
						});
	}

	private Flux<ChatEvent> answerStream(
			String question, List<Message> history, List<Source> sources, StringBuilder answer) {
		return chatClient
				.prompt(promptOf(question, history, sources))
				.stream()
				.content()
				// 토큰 사이 유휴 상한. 넘으면 TimeoutException이 나고 onErrorResume이 error 이벤트로 닫는다.
				.timeout(streamIdleTimeout)
				// 취소는 in-flight onNext와 겹칠 수 있다. append와 snapshot 읽기를 같은 잠금으로 묶어
				// 찢긴 읽기를 막는다 (R-5, doFinally가 취소 시 answer를 읽는다).
				.doOnNext(delta -> appendAnswer(answer, delta))
				.map(ChatEvent.Text::new);
	}

	private static void appendAnswer(StringBuilder answer, String delta) {
		synchronized (answer) {
			answer.append(delta);
		}
	}

	private static String snapshot(StringBuilder answer) {
		synchronized (answer) {
			return answer.toString();
		}
	}

	private static AuditOutcome outcomeOf(SignalType signal) {
		return switch (signal) {
			case ON_COMPLETE -> AuditOutcome.COMPLETE;
			case CANCEL -> AuditOutcome.CANCELLED;
			default -> AuditOutcome.ERROR;
		};
	}

	/** 이력 저장을 격리 스케줄러로 던지고 <b>구독만 한다</b>. 스트림은 여기서 기다리지 않는다 (S13). */
	private void persistHistory(
			UUID sessionId, String question, String answer, List<Source> sources, String traceId) {

		String sourcesJson = toJson(sources);

		Blocking.run(
						() ->
								// 질문+답변을 한 트랜잭션으로. 답변 저장이 실패하면 답변 없는 질문이 남지 않는다 (R-12).
								historyRepository.appendTurn(
										sessionId, Message.user(question), Message.assistant(answer), sourcesJson))
				.doOnError(e -> log.error("이력 저장 실패 traceId={} — 사용자 응답은 정상이다", traceId, e))
				.onErrorComplete()
				.subscribe();
	}

	/** 감사 기록을 격리 스케줄러로 던지고 구독만 한다. 취소·오류에도 남긴다 (R-5). */
	private void recordAudit(
			String requesterId,
			String question,
			String answer,
			List<Source> sources,
			String traceId,
			AuditOutcome outcome) {

		String sourcesJson = toJson(sources);

		Blocking.run(
						() ->
								auditLogRepository.record(
										auditRecordOf(traceId, requesterId, question, answer, sourcesJson, outcome)))
				// 감사 저장 실패는 눈에 띄어야 한다 (REL-2).
				.doOnError(e -> log.warn("감사 저장 실패 traceId={} requester={} — 감사 공백이 생겼다", traceId, requesterId, e))
				.onErrorComplete()
				.subscribe();
	}

	/** 기록 범위는 설정으로 조절한다. 범위 변경이 스키마 변경을 요구하지 않는다 (E5). */
	private AuditRecord auditRecordOf(
			String traceId,
			String requesterId,
			String question,
			String answer,
			String sourcesJson,
			AuditOutcome outcome) {
		return auditScope == AuditScope.FULL
				? new AuditRecord(traceId, requesterId, question, answer, sourcesJson, outcome)
				: new AuditRecord(traceId, requesterId, null, null, sourcesJson, outcome);
	}

	private Prompt promptOf(String question, List<Message> history, List<Source> sources) {
		List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(SYSTEM_PROMPT.formatted(evidenceOf(sources))));
		for (Message message : contextAssembler.assemble(history)) {
			messages.add(
					message.role() == Role.USER
							? new UserMessage(message.content())
							: new AssistantMessage(message.content()));
		}
		messages.add(new UserMessage(question));
		return new Prompt(messages);
	}

	private static String evidenceOf(List<Source> sources) {
		return sources.stream()
				.map(s -> "- (%s, %s) %s".formatted(s.documentName(), s.location(), s.text()))
				.reduce((a, b) -> a + "\n" + b)
				.orElse("(검색된 근거가 없습니다)");
	}

	private static String toJson(List<Source> sources) {
		try {
			return JSON.writeValueAsString(sources);
		} catch (JsonProcessingException e) {
			log.warn("근거 직렬화 실패", e);
			return null;
		}
	}
}
