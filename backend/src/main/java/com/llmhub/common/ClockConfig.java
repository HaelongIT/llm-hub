package com.llmhub.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시간은 주입한다. 테스트에서 고정할 수 없으면 시각에 의존하는 불변식을 검증할 수 없다. */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
