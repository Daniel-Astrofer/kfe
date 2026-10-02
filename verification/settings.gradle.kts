rootProject.name = "kfe-maintenance-verification"

// Focused verification is deliberately not a replacement for the full KFE
// build and its complete financial-schema migration tests.
includeBuild("../../contracts")
includeBuild("../../shared") {
    dependencySubstitution {
        substitute(module("kerosene:kerosene-shared")).using(project(":"))
    }
}
