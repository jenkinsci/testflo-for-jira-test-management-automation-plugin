package com.deviniti.testflo.jenkins.plugin

import com.deviniti.testflo.testsender.ConfigurationField
import com.deviniti.testflo.testsender.MissingTestPlanKeyStrategy
import hudson.model.Result
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.util.Secret
import org.junit.Rule
import org.junit.Test
import org.jvnet.hudson.test.JenkinsRule

class TestResultSenderBuildStepTest {
    @Rule
    @JvmField
    var jenkinsRule = JenkinsRule()
    val jiraURL = "url"
    val jiraUsername = "username"
    val jiraPassword = "password"
    val testResultsDirectory = "**/*.xml"

    @Test
    fun `should store configuration`() {
        // given
        var project = jenkinsRule.createFreeStyleProject()
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), testResultsDirectory, MissingTestPlanKeyStrategy.FAIL_TASK)
        project.publishersList.add(buildStep)

        // when
        project = jenkinsRule.configRoundtrip(project)

        // then
        jenkinsRule.assertEqualDataBoundBeans(
                TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), testResultsDirectory, MissingTestPlanKeyStrategy.FAIL_TASK),
                project.publishersList.first()
        )
    }

    @Test
    fun `should log errors when required config is missing`() {
        // given
        val project = jenkinsRule.createFreeStyleProject()
        val buildStep = TestResultSenderBuildStep("", "", Secret.fromString(""), "test", MissingTestPlanKeyStrategy.FAIL_TASK)
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
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), "*.xml", MissingTestPlanKeyStrategy.SKIP_TASK)
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
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), "*.xml", MissingTestPlanKeyStrategy.FAIL_TASK)
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
        env[ConfigurationField.TEST_PLAN_KEY.fieldName] = "tp-123"
        env[ConfigurationField.TARGET_ITERATION.fieldName] = "CURRENT_ITERATION"
        env[ConfigurationField.TEST_CASE_CREATION_STRATEGY.fieldName] = "CREATE_AND_UPDATE"
        jenkinsRule.jenkins.globalNodeProperties.add(prop)
        val buildStep = TestResultSenderBuildStep(jiraURL, jiraUsername, Secret.fromString(jiraPassword), "*.xml", MissingTestPlanKeyStrategy.FAIL_TASK)
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
}