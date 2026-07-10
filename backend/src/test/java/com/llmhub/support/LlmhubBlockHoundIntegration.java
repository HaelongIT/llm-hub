package com.llmhub.support;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * BlockHound 설정. {@code META-INF/services}로 등록되어 {@code BlockHound.install()}이 어디서
 * 불리든 적용된다 — 테스트 실행 순서에 의존하지 않는다.
 *
 * <p>BlockHound는 <b>모든 {@code park}를 블로킹으로 본다.</b> I/O가 아니라 프레임워크 내부의 일회성 락도
 * 잡힌다. 그런 지점만 골라 허용한다. 파일·소켓 I/O는 그대로 잡힌다.
 *
 * <p>허용은 좁게, 이유를 붙여서. 넓게 허용하면 진짜 블로킹을 놓친다.
 */
public final class LlmhubBlockHoundIntegration implements BlockHoundIntegration {

	@Override
	public void applyTo(BlockHound.Builder builder) {
		// Jackson은 타입별 (역)직렬화기를 처음 만들 때 캐시를 ReentrantLock으로 보호한다.
		// 동시 요청이 같은 타입을 처음 다루면 여기서 park이 발생한다. 워밍업 이후엔 락이 없다.
		// I/O가 아니므로 이벤트 루프를 굶기지 않는다.
		builder.allowBlockingCallsInside(
				"com.fasterxml.jackson.databind.deser.DeserializerCache", "_createAndCacheValueDeserializer");
		builder.allowBlockingCallsInside(
				"com.fasterxml.jackson.databind.deser.DeserializerCache", "hasValueDeserializerFor");
		builder.allowBlockingCallsInside(
				"com.fasterxml.jackson.databind.ser.SerializerCache", "addAndResolveNonTypedSerializer");
	}
}
