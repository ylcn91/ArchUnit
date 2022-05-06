package com.tngtech.archunit.junit.internal.testexamples.abstractbase;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

@AnalyzeClasses(packages = "some.dummy.package")
public class ArchTestWithLibraryWithAbstractBaseClass {
    public static final String FIELD_RULE_LIBRARY_NAME = "fieldRules";
    public static final String METHOD_RULE_LIBRARY_NAME = "methodRules";
    @ArchTest
    ArchTests fieldRules = ArchTests.in(ArchTestWithAbstractBaseClassWithFieldRule.class);
    @ArchTest
    ArchTests methodRules = ArchTests.in(ArchTestWithAbstractBaseClassWithMethodRule.class);
}
