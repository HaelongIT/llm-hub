plugins {
	java
	id("org.springframework.boot") version "3.5.16"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.llmhub"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Spring AI 1.1.x — 2.0은 Spring Boot 4를 요구하므로 쓰지 않는다 (docs/08 §E).
extra["springAiVersion"] = "1.1.8"

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

// 의존성은 그것을 요구하는 TDD 사이클에서 추가한다.
// 아직 쓰지 않는 스타터를 미리 넣으면 자동설정이 켜져 스캐폴딩 테스트를 깨뜨린다
// (예: data-jpa는 DataSource 설정 없이 컨텍스트 기동을 실패시킨다).
//
// 검증된 버전 핀 — 각 모듈 착수 시 여기서 꺼내 쓴다 (docs/LEARNINGS.md 참조):
//   Spring AI BOM           1.1.8            (2.0은 Spring Boot 4를 요구하므로 쓰지 않는다)
//   elasticsearch-java      9.4.3            (클라이언트 major.minor를 서버와 일치시킨다)
//   hwplib                  1.1.10
//   Testcontainers BOM      1.21.4
//   testcontainers-keycloak 4.3.0
//   BlockHound              1.0.17.RELEASE   (+ -XX:+AllowRedefinitionToAddDeleteMethods)
dependencies {
	// CORE — WebFlux 논블로킹 스택.
	// spring-boot-starter-web을 함께 넣으면 Spring Boot가 WebFlux가 아니라
	// MVC를 자동설정한다. 절대 추가하지 말 것 (S13, PERF-1).
	implementation("org.springframework.boot:spring-boot-starter-webflux")

	// 운영 — 헬스 엔드포인트. 컨테이너 오케스트레이터가 기동 완료를 판정한다 (REL-5).
	// 애플리케이션 포트가 아니라 관리 포트에 실린다. 자세한 이유는 application.yml (SEC-1).
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// AUTH — Keycloak OIDC. 리소스 서버는 JWT를 검증만 하고 로그인 플로우를 수행하지 않는다 (S25).
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// 데이터 — PG는 사용자·세션·이력·감사(S9). 블로킹 JPA는 전용 리포지토리 계층에 격리한다(S13, E12).
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	// IDX — 토큰 계수(TokenCountEstimator). 자동설정을 끌고 오는 스타터가 아니라 순수 라이브러리다.
	// spring-ai-core는 없어졌고 1.1.x에서는 spring-ai-commons에 있다.
	implementation("org.springframework.ai:spring-ai-commons")

	// CHAT — ChatClient. 단일 고정 모델이며 라우팅은 LiteLLM 게이트웨이 책임이다 (S8-1, E7).
	implementation("org.springframework.ai:spring-ai-starter-model-openai")

	// IDX — 문서 추출. Tika 기본 포맷과 hwp를 각각 다른 어댑터가 맡는다 (S8-2, E8).
	implementation("org.springframework.ai:spring-ai-tika-document-reader")
	implementation("kr.dogfoot:hwplib:1.1.10")

	// IDX/SEARCH — ES 하이브리드 검색은 Spring AI의 VectorStore(kNN 전용)로 불가능하므로
	// ES Java 클라이언트를 직접 쓴다. 클라이언트 major.minor를 서버와 일치시킨다.
	implementation("co.elastic.clients:elasticsearch-java:9.4.3")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	// 모듈 경계와 의존 방향을 테스트로 강제한다 (MAINT-1, docs/01)
	testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
	testImplementation("org.testcontainers:elasticsearch")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("org.testcontainers:junit-jupiter")
	// 논블로킹 스레드에서의 블로킹 호출을 잡는다. 다만 JDK 21에서 Thread.sleep 계열을
	// 더 이상 잡지 못하는 구멍이 있어, 스레드 이름 단언과 함께 쓴다 (docs/LEARNINGS).
	testImplementation("io.projectreactor.tools:blockhound:1.0.17.RELEASE")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// BlockHound는 네이티브 메서드를 재정의한다. JDK 13+ 에서는 이 플래그가 없으면 동작하지 않는다.
	jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
	// BlockHound는 JVM 전역으로 클래스를 계측한다. 한 JVM에서 44개 테스트 클래스 + 여러 Spring 컨텍스트가
	// 누적되면 계측 에이전트의 네이티브 할당이 실패한다(java.lang.instrument: can't create name string).
	// 테스트 JVM을 주기적으로 재활용해 누적을 끊는다(fork는 순차 실행되므로 Testcontainers 피크 자원은
	// 그대로다). 순서 의존도 함께 줄인다 — 각 fork는 무장되지 않은 새 JVM에서 시작한다 (리뷰 #5).
	setForkEvery(15)
	maxHeapSize = "2g"
	// boundedElastic 상한을 코어 수와 무관하게 고정한다. 기본은 코어 × 10이라, 4코어 미만 CI에서는
	// LoadIsolationTest의 동시성(32)보다 작아져 부분 직렬화로 플래키했다 (리뷰 테스트 #6).
	systemProperty("reactor.schedulers.defaultBoundedElasticSize", "64")
}
