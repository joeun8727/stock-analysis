// 아래 flywayRepair/flywayValidate/flywayInfo 태스크가 Flyway Java API를 직접 부르므로,
// 빌드 스크립트 자신의 클래스패스에 Flyway와 MySQL 드라이버가 있어야 합니다.
// (buildscript 블록은 반드시 파일 맨 앞에 있어야 스크립트 컴파일에 반영됩니다.)
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.flywaydb:flyway-core:11.7.2")
        classpath("org.flywaydb:flyway-mysql:11.7.2")
        classpath("com.mysql:mysql-connector-j:9.2.0")
    }
}

plugins {
    java
    // Kotlin과 Java를 한 모듈에서 같이 컴파일합니다. 변환을 한 번에 끝내지 않고
    // 패키지 단위로 옮기면서 매 단계 테스트를 통과시키기 위해서입니다.
    kotlin("jvm") version "2.2.20"
    // @Component/@Service/@Configuration 클래스를 자동으로 open 처리 — Spring이 CGLIB
    // 프록시를 만들려면 final 클래스이면 안 되는데, Kotlin은 기본이 final입니다.
    kotlin("plugin.spring") version "2.2.20"
    // @Entity에 no-arg 생성자 + open 처리 — Hibernate가 리플렉션으로 인스턴스를 만들고
    // 지연로딩 프록시를 씌우기 때문에 둘 다 필요합니다.
    kotlin("plugin.jpa") version "2.2.20"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.stockanalysis"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Kotlin은 아직 JVM 타깃 25를 지원하지 않아 24로 폴백합니다. Java도 24로 맞춰두지 않으면
// 한 모듈에 두 타깃이 섞여 Gradle이 "Inconsistent JVM-target compatibility"로 빌드를 막습니다.
// JDK 25로 컴파일하되 산출물만 24로 냅니다 — 이 코드는 25 전용 기능을 쓰지 않습니다.
tasks.withType<JavaCompile> {
    options.release = 24
}

kotlin {
    compilerOptions {
        // 플랫폼 타입(Java에서 넘어온 값)의 널 가능성을 느슨하게 두지 않습니다.
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

// ── Flyway 운영 태스크 ────────────────────────────────────────────────────────
// Flyway는 마이그레이션 **파일 내용**으로 체크섬을 계산합니다. SQL을 한 글자도 바꾸지 않고
// 주석만 고쳐도 체크섬이 달라지고, 이미 그 마이그레이션을 적용한 DB에서는 기동이
// "Migration checksum mismatch"로 막힙니다. `./gradlew flywayRepair`가 저장된 체크섬을
// 현재 파일에 맞춰 갱신합니다 — 스키마는 건드리지 않습니다.
//
// Gradle 플러그인(org.flywaydb.flyway)이 아니라 Java API를 직접 부릅니다: 플러그인이 아직
// Gradle 9에서 없어진 JavaPluginConvention을 참조해 태스크 실행이 실패합니다.
//
// 접속 정보는 application.yml과 같은 환경변수·같은 기본값을 읽습니다. 두 벌로 두면 한쪽만
// 고쳐놓고 엉뚱한 DB를 repair하게 됩니다.
fun flywayOf(): org.flywaydb.core.Flyway {
    val host = System.getenv("DB_HOST") ?: "localhost"
    val port = System.getenv("DB_PORT") ?: "3306"
    val name = System.getenv("DB_NAME") ?: "stock_analysis"
    return org.flywaydb.core.Flyway.configure()
        .dataSource(
            "jdbc:mysql://$host:$port/$name?connectionTimeZone=UTC&preserveInstants=false&characterEncoding=UTF-8",
            System.getenv("DB_USER") ?: "stock",
            System.getenv("DB_PASSWORD") ?: "stock")
        .locations("filesystem:src/main/resources/db/migration")
        .load()
}

tasks.register("flywayRepair") {
    group = "flyway"
    description = "저장된 체크섬을 현재 마이그레이션 파일에 맞춥니다 (스키마 변경 없음)."
    doLast { flywayOf().repair() }
}

tasks.register("flywayValidate") {
    group = "flyway"
    description = "적용된 마이그레이션이 현재 파일과 일치하는지 확인합니다."
    doLast { flywayOf().validate() }
}

tasks.register("flywayInfo") {
    group = "flyway"
    description = "마이그레이션 적용 상태를 출력합니다."
    doLast {
        flywayOf().info().all().forEach { m: org.flywaydb.core.api.MigrationInfo ->
            println("  %-6s %-30s %s".format(m.getVersion(), m.getDescription(), m.getState()))
        }
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // data class를 생성자 바인딩으로 역직렬화하려면 필요합니다. 없으면 기본 생성자가 없다며
    // 실패하고, 기본값이 있는 프로퍼티도 무시됩니다.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // 대용량 .xlsx를 저메모리로 읽는 SAX 방식 리더.
    implementation("com.github.pjfanning:excel-streaming-reader:5.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
