package com.kubetrain.api.bdd;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
public class RunCucumberTest {
	// Runner JUnit Platform pour Cucumber
	// Maven Surefire découvre cette classe et exécute les .feature via cucumber.properties
}