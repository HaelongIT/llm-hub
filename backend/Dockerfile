# 코어(WebFlux) 이미지.
#
# 레이어드 jar로 나눠 굽는다. 의존성은 거의 안 바뀌고 애플리케이션 클래스만 자주 바뀌므로,
# 코드를 고쳐도 아래 레이어를 다시 받지 않는다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 래퍼와 빌드 스크립트를 먼저 복사해 의존성 해석을 캐시한다.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY src ./src
# 테스트는 여기서 돌리지 않는다. Testcontainers가 도커를 요구하기 때문이다.
# 테스트는 커밋 훅과 CI가 책임진다.
RUN ./gradlew --no-daemon bootJar -x test

RUN mkdir -p /workspace/layers \
	&& cd /workspace/layers \
	&& java -Djarmode=tools -jar /workspace/build/libs/*.jar extract --layers --launcher --destination .

FROM eclipse-temurin:21-jre AS runtime

# 루트로 돌리지 않는다. uid를 고정한다 — 리눅스 호스트 바인드마운트에서 소유권이 어긋나지 않게
# 한다(프론트엔드 1001과 같은 관례). 고정하지 않으면 이미지마다 uid가 달라질 수 있다 (L-10).
RUN groupadd --system --gid 1001 llmhub && useradd --system --uid 1001 --gid llmhub --home /app llmhub
WORKDIR /app

COPY --from=build --chown=llmhub:llmhub /workspace/layers/dependencies/ ./
COPY --from=build --chown=llmhub:llmhub /workspace/layers/spring-boot-loader/ ./
COPY --from=build --chown=llmhub:llmhub /workspace/layers/snapshot-dependencies/ ./
COPY --from=build --chown=llmhub:llmhub /workspace/layers/application/ ./

# 업로드 원본 보관 경로 (S16). 볼륨으로 마운트한다.
RUN mkdir -p /app/data/documents && chown -R llmhub:llmhub /app/data

USER llmhub
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
