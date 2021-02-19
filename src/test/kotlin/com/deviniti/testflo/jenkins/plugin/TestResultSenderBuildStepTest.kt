package com.deviniti.testflo.jenkins.plugin

import com.deviniti.testflo.jira.ImportTestResultsActive
import com.deviniti.testflo.jira.JiraCredentialsValidationResult
import com.deviniti.testflo.jira.ValidJiraCredentials
import com.deviniti.testflo.testsender.*
import com.github.kittinunf.fuel.core.FuelError
import hudson.model.Result
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.util.Secret
import org.junit.Rule
import org.junit.Test
import org.jvnet.hudson.test.JenkinsRule
import java.io.File

class TestResultSenderBuildStepTest {
    @Rule
    @JvmField
    var jenkinsRule = JenkinsRule()
    val jiraURL = "http://localhost:3000"
    val jiraUsername = "username"
    val jiraPassword = "password"
    val testResultsDirectory = "**/*.xml"
    val testResultsType = TestResultsType.JUNIT

    @Test
    fun `should store configuration`() {
        // given
        var project = jenkinsRule.createFreeStyleProject()
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), testResultsDirectory, MissingTestPlanKeyStrategy.FAIL_TASK, testResultsType)
        project.publishersList.add(buildStep)

        // when
        project = jenkinsRule.configRoundtrip(project)

        // then
        jenkinsRule.assertEqualDataBoundBeans(
                TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), testResultsDirectory, MissingTestPlanKeyStrategy.FAIL_TASK, testResultsType),
                project.publishersList.first()
        )
    }

    @Test
    fun `should log errors when required config is missing`() {
        // given
        val project = jenkinsRule.createFreeStyleProject()
        val buildStep = TestResultSenderBuildStep("", "", Secret.fromString(""), "test", MissingTestPlanKeyStrategy.FAIL_TASK, testResultsType)
        project.publishersList.add(buildStep)

        // when
        val build = project.scheduleBuild2(0).get()

        // then
        jenkinsRule.assertBuildStatus(Result.FAILURE, build)
        jenkinsRule.assertLogContains(TestResultSenderBuildStep.BLANK_JIRA_URL, build)
        jenkinsRule.assertLogContains(TestResultSenderBuildStep.BLANK_JIRA_USERNAME, build)
        jenkinsRule.assertLogContains(TestResultSenderBuildStep.BLANK_JIRA_PASSWORD, build)
        jenkinsRule.assertLogContains(TestResultSenderBuildStep.getEmptyTestResultsLabel("test"), build)
        jenkinsRule.assertLogContains(TestResultSenderBuildStep.MISSING_TEST_PLAN_KEY, build)
    }

    @Test
    fun `should not log errors when Test Plan key is missing and Test Plan key strategy is set to skip build`() {
        // given
        val project = jenkinsRule.createFreeStyleProject()
        val prop = EnvironmentVariablesNodeProperty()
        val env = prop.envVars
        env[ConfigurationField.TARGET_ITERATION.fieldName] = "CURRENT_ITERATION"
        env[ConfigurationField.TEST_CASE_CREATION_STRATEGY.fieldName] = "CREATE_AND_UPDATE"
        jenkinsRule.jenkins.globalNodeProperties.add(prop)
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), "*.xml", MissingTestPlanKeyStrategy.SKIP_TASK, testResultsType)
        project.publishersList.add(buildStep)
        val workspace = jenkinsRule.jenkins.getWorkspaceFor(project)
        workspace!!.createTextTempFile("test", ".xml", "test")

        // when
        val build = project.scheduleBuild2(0).get()

        // then
        jenkinsRule.assertBuildStatusSuccess(build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_URL, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_USERNAME, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_PASSWORD, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.getEmptyTestResultsLabel("*.xml"), build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.MISSING_TEST_PLAN_KEY, build)
    }

    @Test
    fun `should log error when required config is present except Test Plan key and Test Plan key strategy is set to fail task`() {
        // given
        val project = jenkinsRule.createFreeStyleProject()
        val prop = EnvironmentVariablesNodeProperty()
        val env = prop.envVars
        env[ConfigurationField.TARGET_ITERATION.fieldName] = "CURRENT_ITERATION"
        env[ConfigurationField.TEST_CASE_CREATION_STRATEGY.fieldName] = "CREATE_AND_UPDATE"
        jenkinsRule.jenkins.globalNodeProperties.add(prop)
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), "*.xml", MissingTestPlanKeyStrategy.FAIL_TASK, testResultsType)
        project.publishersList.add(buildStep)
        val workspace = jenkinsRule.jenkins.getWorkspaceFor(project)
        workspace!!.createTextTempFile("test", ".xml", "test")

        // when
        val build = project.scheduleBuild2(0).get()

        // then
        jenkinsRule.assertBuildStatus(Result.FAILURE, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_URL, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_USERNAME, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_PASSWORD, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.getEmptyTestResultsLabel("*.xml"), build)
    }

    @Test
    fun `should not log anything when required config is present`() {
        // given
        val project = jenkinsRule.createFreeStyleProject()
        val prop = EnvironmentVariablesNodeProperty()
        val env = prop.envVars
        env[ConfigurationField.JIRA_URL.fieldName] = "http://localhost:2000"
        env[ConfigurationField.TEST_PLAN_KEY.fieldName] = "tp-123"
        env[ConfigurationField.TARGET_ITERATION.fieldName] = "CURRENT_ITERATION"
        env[ConfigurationField.TEST_CASE_CREATION_STRATEGY.fieldName] = "CREATE_AND_UPDATE"
        jenkinsRule.jenkins.globalNodeProperties.add(prop)
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), "*.xml", MissingTestPlanKeyStrategy.FAIL_TASK, testResultsType)
        testResultSender = TestResultSenderImpl(mockClient)

        project.publishersList.add(buildStep)
        val workspace = jenkinsRule.jenkins.getWorkspaceFor(project)
        workspace!!.createTextTempFile("test", ".xml", "test")

        // when
        val build = project.scheduleBuild2(0).get()

        // then
        jenkinsRule.assertBuildStatusSuccess(build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_URL, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_USERNAME, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.BLANK_JIRA_PASSWORD, build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.getEmptyTestResultsLabel("*.xml"), build)
        jenkinsRule.assertLogNotContains(TestResultSenderBuildStep.MISSING_TEST_PLAN_KEY, build)
    }

    private val mockClient = object : com.deviniti.testflo.jira.JiraRestClient {
        override fun isImportTestResultsActive(configuration: Configuration): ImportTestResultsActive {
            return ImportTestResultsActive(false, null)
        }

        override fun sendTestResultFileAsync(configuration: Configuration, testResultZipFile: File): FuelError? {
            return null
        }

        override fun validateJiraCredentials(jiraUrl: String, username: String, password: String): JiraCredentialsValidationResult {
            return ValidJiraCredentials
        }
    }
}