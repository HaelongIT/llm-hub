package com.llmhub.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * docs/01의 모듈 의존 방향을 테스트로 강제한다 (MAINT-1).
 *
 * <p>Gradle 서브프로젝트로 쪼개는 대신 이 테스트로 경계를 지킨다. 위반은 빌드 실패로 드러난다.
 *
 * <p>허용된 의존:
 *
 * <ul>
 *   <li>모든 모듈 → common
 *   <li>CHAT → SEARCH, AUDIT (오케스트레이션)
 * </ul>
 *
 * <p>그 외 모듈 간 의존은 없다. IDX는 독립(관리자 경로)이고, SEARCH는 AUTH가 확정한 태그를
 * 소비만 하므로 AUTH를 참조하지 않는다(S4). AUDIT는 CHAT의 비동기 훅이므로 역참조가 없다.
 */
@AnalyzeClasses(packages = "com.llmhub", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

	@ArchTest
	static final ArchRule 모듈_간_순환_의존이_없다 =
			SlicesRuleDefinition.slices().matching("com.llmhub.(*)..").should().beFreeOfCycles();

	@ArchTest
	static final ArchRule IDX는_다른_모듈에_의존하지_않는다 =
			noClasses()
					.that()
					.resideInAPackage("..idx..")
					.should()
					.dependOnClassesThat()
					.resideInAnyPackage("..auth..", "..search..", "..chat..", "..audit..")
					.because("IDX는 관리자 경로에 독립적으로 선다 (docs/01)");

	@ArchTest
	static final ArchRule AUTH는_다른_모듈에_의존하지_않는다 =
			noClasses()
					.that()
					.resideInAPackage("..auth..")
					.should()
					.dependOnClassesThat()
					.resideInAnyPackage("..idx..", "..search..", "..chat..", "..audit..")
					.because("AUTH는 앞단 게이트이며 하위 계층을 알지 못한다 (S4)");

	@ArchTest
	static final ArchRule SEARCH는_다른_모듈에_의존하지_않는다 =
			noClasses()
					.that()
					.resideInAPackage("..search..")
					.should()
					.dependOnClassesThat()
					.resideInAnyPackage("..idx..", "..auth..", "..chat..", "..audit..")
					.because("SEARCH는 AUTH가 확정한 태그를 소비만 하며 권한을 재판단하지 않는다 (S4)");

	@ArchTest
	static final ArchRule AUDIT는_다른_모듈에_의존하지_않는다 =
			noClasses()
					.that()
					.resideInAPackage("..audit..")
					.should()
					.dependOnClassesThat()
					.resideInAnyPackage("..idx..", "..auth..", "..search..", "..chat..")
					.because("AUDIT는 CHAT의 비동기 훅이며 독립 수명주기를 가진다 (S5)");

	@ArchTest
	static final ArchRule CHAT은_IDX에_의존하지_않는다 =
			noClasses()
					.that()
					.resideInAPackage("..chat..")
					.should()
					.dependOnClassesThat()
					.resideInAnyPackage("..idx..")
					.because("색인은 관리자 경로이며 채팅 흐름과 무관하다 (docs/01)");

	@ArchTest
	static final ArchRule common은_어떤_모듈에도_의존하지_않는다 =
			noClasses()
					.that()
					.resideInAPackage("..common..")
					.should()
					.dependOnClassesThat()
					.resideInAnyPackage("..idx..", "..auth..", "..search..", "..chat..", "..audit..")
					.because("common은 공통 부품이며 모듈을 알지 못한다 (MAINT-1)");
}
