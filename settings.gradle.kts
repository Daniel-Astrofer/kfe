rootProject.name = "kerosene-kfe"

val contractsDirectory = providers.environmentVariable("KEROSENE_CONTRACTS_DIR")
    .orElse("../kerosene-contracts")
    .get()

includeBuild(contractsDirectory) {
    dependencySubstitution {
        substitute(module("io.kerosene.contracts:kerosene-contracts"))
            .using(project(":"))
    }
}

val sharedDirectory = providers.environmentVariable("KEROSENE_SHARED_DIR")
    .orElse("../kerosene-shared")
    .get()

includeBuild(sharedDirectory) {
    dependencySubstitution {
        substitute(module("kerosene:kerosene-shared"))
            .using(project(":"))
    }
}
