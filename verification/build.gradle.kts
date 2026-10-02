plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.15"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.data:spring-data-redis")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    implementation("kerosene:kerosene-shared:PRE-ALPHA")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
sourceSets {
    main {
        java.setSrcDirs(listOf("../src/main/java"))
        java.include("com/kerosene/kfe/maintenance/**", "com/kerosene/kfe/controller/KfeMaintenanceAdminController.java",
            "com/kerosene/kfe/controller/KfeAuthenticationSupport.java", "com/kerosene/kfe/exception/KfeExceptionHandler.java",
            "com/kerosene/kfe/runtime/KfeJwtVerifier.java", "com/kerosene/kfe/runtime/KfeJwtAuthenticationFilter.java")
        resources.setSrcDirs(listOf("../src/main/resources"))
        resources.include("db/migration/V57__kfe_maintenance_admission.sql", "db/migration/V58__kfe_maintenance_continuations.sql")
    }
    test {
        java.setSrcDirs(listOf("../src/test/java"))
        java.include("com/kerosene/kfe/maintenance/KfeMaintenanceServiceTest.java",
            "com/kerosene/kfe/maintenance/JdbcKfeMaintenanceStoreTest.java",
            "com/kerosene/kfe/maintenance/KfeMaintenancePostgresTest.java",
            "com/kerosene/kfe/maintenance/KfeMaintenanceContinuationTest.java",
            "com/kerosene/kfe/maintenance/KfeMaintenanceHttpBarrierTest.java",
            "com/kerosene/kfe/controller/KfeMaintenanceAdminControllerTest.java")
    }
}
tasks.test { useJUnitPlatform() }
