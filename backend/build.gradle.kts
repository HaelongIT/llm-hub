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

	// IDX — 토큰 계수(TokenCountEstimator). 자동설정을 끌고 오는 스타터가 아니라 순수 라이브러리다.
	// spring-ai-core는 없어졌고 1.1.x에서는 spring-ai-commons에 있다.
	implementation("org.springframework.ai:spring-ai-commons")

	// IDX — 문서 추출(Tika). hwp는 별도 어댑터(hwplib)가 맡는다 (S8-2, E8).
	implementation("org.springframework.ai:spring-ai-tika-document-reader")

	// IDX/SEARCH — ES 하이브리드 검색은 Spring AI의 VectorStore(kNN 전용)로 불가능하므로
	// ES Java 클라이언트를 직접 쓴다. 클라이언트 major.minor를 서버와 일치시킨다.
	implementation("co.elastic.clients:elasticsearch-java:9.4.3")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	// 모듈 경계와 의존 방향을 테스트로 강제한다 (MAINT-1, docs/01)
	testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
	testImplementation("org.testcontainers:elasticsearch")
	testImplementation("org.testcontainers:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
