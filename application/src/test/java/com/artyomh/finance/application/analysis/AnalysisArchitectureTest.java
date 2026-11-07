package com.artyomh.finance.application.analysis;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.artyomh.finance.application", importOptions = ImportOption.DoNotIncludeTests.class)
class AnalysisArchitectureTest {

    // comparison composes spending; the reverse would make the two services change together
    @ArchTest
    static final ArchRule spendingAndSubscriptionDoNotDependOnComparison = noClasses()
            .that().resideInAnyPackage("..analysis.spending..", "..analysis.subscription..")
            .should().dependOnClassesThat().resideInAPackage("..analysis.comparison..");
}
