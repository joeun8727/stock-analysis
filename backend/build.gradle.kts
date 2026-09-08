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
