package com.deviniti.testflo.jenkins.plugin

import com.deviniti.testflo.jira.InvalidJiraCredentials
import com.deviniti.testflo.jira.InvalidJiraUrl
import com.deviniti.testflo.jira.JiraRestClientImpl
import com.deviniti.testflo.jira.UnexpectedError
import com.deviniti.testflo.testsender.*
import com.deviniti.testflo.testsender.MissingTestPlanKeyStrategy.FAIL_TASK
import com.deviniti.testflo.testsender.TargetIteration.CURRENT_ITERATION
import com.deviniti.testflo.testsender.TestCaseCreationStrategy.CREATE_AND_UPDATE
import hudson.EnvVars
import hudson.Extension
import hudson.FilePath
import hudson.Launcher
import hudson.model.AbstractProject
import hudson.model.Result.FAILURE
import hudson.model.Run
import hudson.model.TaskListener
import hudson.security.Permission
import hudson.tasks.BuildStepDescriptor
import hudson.tasks.Notifier
import hudson.tasks.Publisher
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import hudson.util.Secret
import jenkins.model.Jenkins
import jenkins.tasks.SimpleBuildStep
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

val jiraRestClient = JiraRestClientImpl()
var testResultSender: TestResultSender = TestResultSenderImpl(jiraRestClient)
/**
 * Fields cannot be private, as Jenkins doesn't see them in task configuration
 */
class TestResultSenderBuildStep @DataBoundConstructor constructor(
    val jiraURL: String,
    val jiraUserName: String,
    val jiraPassword: Secret,
    val testResultsDirectory: String,
    val missingTestPlanKeyStrategy: MissingTestPlanKeyStrategy,
    val testResultsType: TestResultsType,
    val retryOnActiveProgress: Boolean? = false,
    val loggingEnabled: Boolean? = false,
) : Notifier(), SimpleBuildStep {

    companion object {
        const val BLANK_JIRA_URL = "Jira URL is blank"
        const val BLANK_JIRA_USERNAME = "Jira username is blank"
        const val BLANK_JIRA_PASSWORD = "Jira password is blank"
        fun getEmptyTestResultsLabel(testResultsDirectory: String) = "Test result files not found in $testResultsDirectory, skipping sending test results"
        const val MISSING_TEST_PLAN_KEY = "Test plan key build parameter is missing"
    }

    @SuppressWarnings("deprecated")
    override fun perform(run: Run<*, *>, workspace: FilePath, envVars: EnvVars, launcher: Launcher, listener: TaskListener) {
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

        fun logMessage(message: String) {
            if(loggingEnabled != null && loggingEnabled) {
                val time = LocalDateTime.now().toLocalTime().toString()
                listener.logger.println("$time - $message")
            }
        }

        val (outFolder, testResultFiles) = getTestResultFiles(workspace)
        try {
            val testPlanKey = envVars[ConfigurationField.TEST_PLAN_KEY]
            val importResultsParameters = envVars[ConfigurationField.TEST_FLO_IMPORT_RESULTS_PARAMETERS]
            val testCaseCreationStrategy = envVars[ConfigurationField.TEST_CASE_CREATION_STRATEGY]
                ?.takeUnless { it.isBlank() }
                ?.let(TestCaseCreationStrategy::valueOf)
                ?: CREATE_AND_UPDATE
            val targetIteration = envVars[ConfigurationField.TARGET_ITERATION]
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
                    testResultFiles = testResultFiles,
                    testResultsType = testResultsType,
                    importResultsParameters = importResultsParameters,
                    retryOnActiveProgress = retryOnActiveProgress ?: false,
                    logger = {
                        logMessage(it)
                    }
                )
                logMessage("Sending test results to $jiraURL/browse/$testPlanKey")
                val error = testResultSender.send(configuration)
                if (error != null) {
                    listener.error(error.response.body().asString("text/plain"))
                    listener.error(error.message, error.cause)
                    run.setResult(FAILURE)
                } else {
                    logMessage("Import finished")
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

        @JellyMethod
        fun doFillTestResultsTypeItems() = ListBoxModel().apply {
            add("JUnit", TestResultsType.JUNIT.name)
            add("TestNG", TestResultsType.TESTNG.name)
            add("Cucumber (JSON only, read help on the right)", TestResultsType.CUCUMBER.name)
            add("NUnit", TestResultsType.NUNIT.name)
        }

        override fun isApplicable(aClass: Class<out AbstractProject<*, *>?>?) = true

        override fun getDisplayName() = "TestFLO Automation test results publisher"

        @JellyMethod
        @POST
        fun doTestConnection(@QueryParameter jiraURL: String, @QueryParameter jiraUserName: String, @QueryParameter jiraPassword: Secret): FormValidation {
            Jenkins.get().checkPermission(Permission.CONFIGURE)
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
