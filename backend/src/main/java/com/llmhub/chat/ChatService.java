package com.llmhub.chat;

import com.llmhub.common.Blocking;
import com.llmhub.search.SearchService;
import com.llmhub.search.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 질문을 받아 근거 기반 답변을 이벤트 SSE로 스트리밍한다.
 *
 * <p><b>검색은 Advisor 안이 아니라 여기서 명시적으로 호출한다.</b> S6가 "근거는 서버 검색 결과에서만 나온다"를
 * 요구하므로, 그 사실이 코드 구조에 드러나야 한다. 덕분에 {@code sources}를 첫 {@code text} 토큰보다 먼저 확정
 * 발행할 수 있다. 스트리밍에서 Advisor의 검색 결과가 첫 토큰보다 먼저 방출된다는 보장은 문서화되어 있지 않다.
 *
 * <p>권한을 판단하지 않는다. AUTH가 앞단 게이트에서 확정한 태그를 소비만 한다 (S4).
 *
 * <p>블로킹 검색은 격리 스케줄러로 넘긴다 (S13).
 */
public final class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

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

	public ChatService(SearchService searchService, ChatClient chatClient, ContextAssembler contextAssembler) {
		this.searchService = searchService;
		this.chatClient = chatClient;
		this.contextAssembler = contextAssembler;
	}

	/**
	 * @param accessTags AUTH가 확정한 태그. 재계산하지 않는다.
	 * @param history 세션 이력. 최근 N턴만 컨텍스트에 실린다 (E2).
	 */
	public Flux<ChatEvent> stream(
			String question, Set<String> accessTags, List<Message> history, String traceId) {

		return Blocking.call(() -> searchService.search(question, accessTags))
				.flatMapMany(sources -> sourcesThenAnswer(question, history, traceId, sources))
				.onErrorResume(error -> {
					// 깨끗한 실패. 자동 우회·페일오버 없이 error 이벤트로 스트림을 닫는다 (S8-3).
					log.error("채팅 스트림 실패 traceId={}", traceId, error);
					return Flux.just(new ChatEvent.Error(error.getMessage()));
				});
	}

	private Flux<ChatEvent> sourcesThenAnswer(
			String question, List<Message> history, String traceId, List<Source> sources) {
		return Flux.concat(
				// 근거 먼저. LLM이 한 글자도 내기 전에 확정된다.
				Mono.just(new ChatEvent.Sources(sources)),
				answerStream(question, history, sources),
				Mono.just(new ChatEvent.Done(traceId)));
	}

	private Flux<ChatEvent> answerStream(String question, List<Message> history, List<Source> sources) {
		return chatClient
				.prompt(promptOf(question, history, sources))
				.stream()
				.content()
				.map(ChatEvent.Text::new);
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
}
