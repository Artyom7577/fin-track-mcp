package com.artyomh.finance.adapter.parser;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.artyomh.finance.adapter.parser", importOptions = ImportOption.DoNotIncludeTests.class)
class ParserArchitectureTest {

    private static final String ROOT = "com.artyomh.finance.adapter.parser";
    private static final String FORMAT = "..adapter.parser.format..";
    private static final String EVOCA = "..adapter.parser.evoca..";

    // file formats are shared by every bank, so they never know one
    @ArchTest
    static final ArchRule formatDoesNotKnowBanks = noClasses().that().resideInAPackage(FORMAT)
            .should().dependOnClassesThat().resideInAPackage(EVOCA);

    @ArchTest
    static final ArchRule fileLibrariesStayInFormat = noClasses().that().resideOutsideOfPackage(FORMAT)
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.poi..", "org.apache.commons.csv..");

    @ArchTest
    static final ArchRule onlyTheEntryPointSeesEveryPackage = noClasses().that().resideInAnyPackage(FORMAT, EVOCA)
            .should().dependOnClassesThat().resideInAPackage(ROOT);

    @ArchTest
    static final ArchRule noOtherAdapterServerOrSpring = noClasses().that().resideInAPackage("..adapter.parser..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.persistence..", "..adapter.fx..",
                    "..finance.mcp..", "org.springframework..");
}
