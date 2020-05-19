### About the plugin
This plugin integrates Jenkins with TestFLO for jira app, allowing to publish build test results and import them as Test Cases in Jira.

### Requirements
- Jenkins 2.138.4 or higher
- Jira instance (server / data center) with installed TestFLO app  

### Supported test results formats
- JUnit
- TestNG

### Usage
This plugin provides new build task, which should be used in Post-build actions in the configuration of jenkins job:
![](docs/images/post_build_action_select.png)

Following fields are present:
![](docs/images/task_configuration.png)

- Jira URL - URL to Jira instance, which receives test results
- User - Jira user login
- Password - Jira user password
- Test results directory - Directories from which task gets test results files
- Missing Test Plan key parameter behaviour - when task doesn't get Jira Test Plan issue key, it can either skip this task or fail it

To verify task configuration, you can use "Test connection" button: 
![](docs/images/connection_success.png)

To make job possible to trigger from TestFLO app, it is required to parametrize job with 3 parameters:
![](docs/images/job_parameters.png)
- testPlanKey - contains issue key of Test Plan from which job is being run
- targetIteration - tells whether to add Test Cases to current iteration in test plan, or to create new. You can provide default value using these options: 
    - CURRENT_ITERATION
    - NEW_ITERATION
- testCaseCreationStrategy - tells whether new Test Cases should be created with test results, or only limited to updating existing Test Cases. 
You can define default value, using these options: 
    - CREATE_AND_UPDATE
    - UPDATE_EXISTING