package com.llmhub.chat;

/** 대화 메시지 한 건. */
public record Message(Role role, String content) {

	public static Message user(String content) {
		return new Message(Role.USER, content);
	}

	public static Message assistant(String content) {
		return new Message(Role.ASSISTANT, content);
	}
}
