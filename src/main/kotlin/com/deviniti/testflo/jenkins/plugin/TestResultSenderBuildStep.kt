package com.deviniti.testflo.jenkins.plugin

import com.deviniti.testflo.jira.InvalidJiraCredentials
import com.deviniti.testflo.jira.InvalidJiraUrl
import com.deviniti.testflo.jira.JiraRestClientImpl
import com.deviniti.testflo.jira.UnexpectedError
import com.deviniti.testflo.testsender.*
import com.deviniti.testflo.testsender.MissingTestPlanKeyStrategy.*
import com.deviniti.testflo.testsender.TargetIteration.*
import com.deviniti.testflo.testsender.TestCaseCreationStrategy.*
import hudson.EnvVars
import hudson.Extension
import hudson.FilePath
import hudson.Launcher
import hudson.model.AbstractProject
import hudson.model.Result.FAILURE
import hudson.model.Run
import hudson.model.TaskListener
import hudson.tasks.BuildStepDescriptor
import hudson.tasks.Notifier
import hudson.tasks.Publisher
import hudson.util.FormValidation
import hudson.util.Secret
import jenkins.tasks.SimpleBuildStep
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.QueryParameter
import java.io.File
import java.nio.file.Files

val jiraRestClient = JiraRestClientImpl()
val testResultSender: TestResultSender = TestResultSenderImpl(jiraRestClient)

/**
 * Fields cannot be private, as Jenkins doesn't see them in task configuration
 */
class TestResultSenderBuildStep @DataBoundConstructor constructor(
        val jiraURL: String,
        val jiraUserName: String,
        val jiraPassword: Secret,
        val testResultsDirectory: String,
        val missingTestPlanKeyStrategy: MissingTestPlanKeyStrategy
) : Notifier(), SimpleBuildStep {

    companion object {
        const val BLANK_JIRA_URL = "Jira URL is blank"
        const val BLANK_JIRA_USERNAME = "Jira username is blank"
        const val BLANK_JIRA_PASSWORD = "Jira password is blank"
        fun getEmptyTestResultsLabel(testResultsDirectory: String) = "Test result files not found in $testResultsDirectory, skipping sending test results"
        const val MISSING_TEST_PLAN_KEY = "Test plan key build parameter is missing"
    }

    @SuppressWarnings("deprecated")
    override fun perform(run: Run<*, *>, workspace: FilePath, launcher: Launcher, listener: TaskListener) {
        var shouldSendResults = true

        fun checkRequiredConfig(errorMessage: String, isValid: Boolean) {
            if (!isValid) {
                run.setResult(FAILURE)
                shouldSendResults = false
                listener.error(errorMessage)
            }
        }

        fun checkRequiredTestPlanKey(isValid: Boolean) {
            if (!isValid) {
                if (missingTestPlanKeyStrategy == FAIL_TASK) {
                    run.setResult(FAILURE)
                    listener.error(MISSING_TEST_PLAN_KEY)
                }
                shouldSendResults = false
            }
        }

        fun checkTestResultFiles(testResultFiles: List<File>) {
            if (testResultFiles.isEmpty()) {
                shouldSendResults = false
                listener.logger.println(getEmptyTestResultsLabel(testResultsDirectory))
            }
        }

        val (outFolder, testResultFiles) = getTestResultFiles(workspace)
        try {
            val env = run.getEnvironment(listener)
            val testPlanKey = env[ConfigurationField.TEST_PLAN_KEY]
            val testCaseCreationStrategy = env[ConfigurationField.TEST_CASE_CREATION_STRATEGY]
                    ?.takeUnless { it.isBlank() }
                    ?.let(TestCaseCreationStrategy::valueOf)
                    ?: CREATE_AND_UPDATE
            val targetIteration = env[ConfigurationField.TARGET_ITERATION]
                    ?.takeUnless { it.isBlank() }
                    ?.let(TargetIteration::valueOf)
                    ?: CURRENT_ITERATION

            checkRequiredConfig(BLANK_JIRA_URL, jiraURL.isNotBlank())
            checkRequiredConfig(BLANK_JIRA_USERNAME, jiraUserName.isNotBlank())
            checkRequiredConfig(BLANK_JIRA_PASSWORD, jiraPassword.plainText.isNotBlank())
            checkTestResultFiles(testResultFiles)
            checkRequiredTestPlanKey(!testPlanKey.isNullOrBlank())

            if (shouldSendResults) {
                val configuration = Configuration(
                        jiraUrl = jiraURL,
                        testPlanKey = testPlanKey!!,
                        buildUrl = runCatching { run.getAbsoluteUrl() }.getOrDefault(""),
                        testCaseCreationStrategy = testCaseCreationStrategy,
                        targetIteration = targetIteration,
                        jiraUsername = jiraUserName,
                        jiraPassword = jiraPassword.plainText,
                        testResultFiles = testResultFiles
                )
                val error = testResultSender.send(configuration)
                if (error != null) {
                    listener.error(error.response.body().asString("text/plain"))
                    listener.error(error.message, error.cause)
                    run.setResult(FAILURE)
                }
            }
        } finally {
            outFolder.deleteRecursively()
        }
    }

    private fun getTestResultFiles(workspace: FilePath): Pair<File, List<File>> {
        val outFolderPath = Files.createTempDirectory("tempfolder")
        val outFolder = outFolderPath.toFile()

        workspace.copyRecursiveTo(testResultsDirectory, FilePath(outFolder))

        return outFolder to outFolder.walkTopDown()
                .filter { it.isFile }
                .toList()
    }

    @Extension
    class DescriptorImpl : BuildStepDescriptor<Publisher>() {
        @JellyMethod
        fun getDefaultTestResultsDirectory() = "**/target/surefire-reports/*.xml"

        override fun isApplicable(aClass: Class<out AbstractProject<*, *>?>?) = true

        override fun getDisplayName() = "TestFLO Automation test results sender"

        @JellyMethod
        fun doTestConnection(@QueryParameter jiraURL: String, @QueryParameter jiraUserName: String, @QueryParameter jiraPassword: Secret): FormValidation {
            val validationResult = jiraRestClient.validateJiraCredentials(
                    jiraUrl = jiraURL,
                    username = jiraUserName,
                    password = jiraPassword.plainText
            )

            return when (validationResult) {
                is InvalidJiraCredentials -> FormValidation.error("Invalid username or password")
                is InvalidJiraUrl -> FormValidation.error("Invalid address")
                is UnexpectedError -> FormValidation.error("""An unexpected error occurred while testing Jira credentials: ${validationResult.message}""")
                else -> FormValidation.ok("Connection successful")
            }
        }
    }
}

operator fun EnvVars.get(configurationField: ConfigurationField): String? {
    return get(configurationField.fieldName)
}