package com.artyomh.finance.mockdata;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.artyomh.finance.mockdata", importOptions = ImportOption.DoNotIncludeTests.class)
class MockdataArchitectureTest {

    private static final String SCENARIO = "..mockdata.scenario..";
    private static final String GENERATOR = "..mockdata.generator..";
    private static final String WRITER = "..mockdata.writer..";
    private static final String CLI = "..mockdata.cli..";

    @ArchTest
    static final ArchRule scenarioDependsOnNoOtherPackage = noClasses().that().resideInAPackage(SCENARIO)
            .should().dependOnClassesThat().resideInAnyPackage(GENERATOR, WRITER, CLI);

    @ArchTest
    static final ArchRule generatorDependsOnlyOnScenario = noClasses().that().resideInAPackage(GENERATOR)
            .should().dependOnClassesThat().resideInAnyPackage(WRITER, CLI);

    // writers see only the generated statement, never the scenario behind it
    @ArchTest
    static final ArchRule writerDependsOnlyOnGenerator = noClasses().that().resideInAPackage(WRITER)
            .should().dependOnClassesThat().resideInAnyPackage(SCENARIO, CLI);
}
