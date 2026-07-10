package com.llmhub.chat.config;

import com.llmhub.audit.AuditLogRepository;
import com.llmhub.chat.ChatHistoryRepository;
import com.llmhub.chat.ChatService;
import com.llmhub.chat.ContextAssembler;
import com.llmhub.chat.RecentTurnsContextAssembler;
import com.llmhub.search.SearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 채팅 배선.
 *
 * <p>{@link ChatClient}는 RAG Advisor 체인에 강결합되지 않는다. RAG 없는 순수 호출도 가능하다 (E13).
 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

	@Bean
	ContextAssembler contextAssembler(ChatProperties properties) {
		return new RecentTurnsContextAssembler(properties.contextTurns(), properties.maxContextTokens());
	}

	@Bean
	ChatClient chatClient(ChatModel chatModel) {
		return ChatClient.builder(chatModel).build();
	}

	@Bean
	ChatService chatService(
			SearchService searchService,
			ChatClient chatClient,
			ContextAssembler contextAssembler,
			ChatHistoryRepository historyRepository,
			AuditLogRepository auditLogRepository,
			ChatProperties properties) {
		return new ChatService(
				searchService,
				chatClient,
				contextAssembler,
				historyRepository,
				auditLogRepository,
				properties.auditScope());
	}
}
