/*
 * Copyright 2013 Qubell, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qubell.jenkinsci.plugins.qubell.builders;

import com.qubell.jenkinsci.plugins.qubell.Configuration;
import com.qubell.jenkinsci.plugins.qubell.JsonParser;
import com.qubell.jenkinsci.plugins.qubell.VariablesAction;
import com.qubell.services.*;
import com.qubell.services.exceptions.InvalidCredentialsException;
import com.qubell.services.exceptions.NotAuthorizedException;
import com.qubell.services.exceptions.QubellServiceException;
import com.qubell.services.exceptions.ResourceNotFoundException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.*;
import hudson.slaves.SlaveComputer;
import hudson.tasks.Builder;
import hudson.util.VariableResolver;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.StopWatch;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;


/**
 * Base type for Qubell specific Jenkins builders see {@link Builder}
 *
 * @author Alex Krupnov
 */
public abstract class QubellBuilder extends Builder {

    public static final String ASYNC_INSTANCE_ID_KEY = "instanceId";
    public static final String ASYNC_EXPECTED_STATUS_KEY = "expectedStatus";
    public static final String ASYNC_OUTPUT_PATH_KEY = "outputFilePath";
    /**
     * Defines which status has to be set when failure occurs
     */
    private final Result failureReaction;

    /**
     * Prefix for log messages
     */
    protected static String LOG_MESSAGE_PREFIX = "[QUBELL] ";
    /**
     * Key value for storing instance id
     */
    protected static String INSTANCE_ID_KEY = "QUBELL_INSTANCE_ID";

    /**
     * {@link InstanceStatusCode}, expected by builder for successful finish
     */
    protected InstanceStatusCode expectedStatus;

    /**
     * Status wait timeout in seconds
     */
    protected final int timeout;

    /**
     * Output file name for consolidated report
     */
    protected String outputFilePath;

    private String outputFilePathResolved;

    /**
     * Inits builder common properties
     *
     * @param timeout         string (injected from UI value of timeout)
     * @param expectedStatus  the {@link com.qubell.services.InstanceStatusCode}, expected by builder for successful finish
     * @param outputFilePath  path to builder output file
     * @param failureReaction a target build status which should be set when instnace returns failure status
     */
    public QubellBuilder(String timeout, InstanceStatusCode expectedStatus, String outputFilePath, String failureReaction) {
        this.expectedStatus = expectedStatus;
        this.timeout = Integer.parseInt(timeout);
        this.outputFilePath = outputFilePath;
        this.failureReaction = Result.fromString(failureReaction != null ? failureReaction : Result.FAILURE.toString());
    }

    /**
     * Plugin configuration holder
     *
     * @return dynamically fetched instance of Configuration
     */
    protected Configuration getConfiguration() {
        return Configuration.get();
    }

    /**
     * Qubell services facade for builders disposal
     *
     * @return new instance of facade
     */
    protected QubellFacade getServiceFacade() {
        return new QubellFacadeImpl(getConfiguration());
    }

    /**
     * Used for validating configuration object, making sure it is suitable for builder operations
     *
     * @return true of configuration valid, otherwise false
     */
    protected boolean validateConfiguration() {
        Configuration configuration = getConfiguration();
        return !StringUtils.isBlank(configuration.getUrl()) && !StringUtils.isBlank(configuration.getLogin()) && !StringUtils.isBlank(configuration.getPassword());
    }

    /**
     * Reads a build variable, stored into build parameter
     *
     * @param build    current build
     * @param key      variable key
     * @param buildLog current build log
     * @return string value of variable or null
     */
    protected String readBuildVariable(AbstractBuild build, String key, PrintStream buildLog) {
        VariablesAction variablesAction = getVariableAction(build);
        String value = variablesAction.getVariable(key);
        if (value == null) {
            logMessage(buildLog, "Expected a value for key: %s, no entry found", key);
        }

        return value;
    }

    /**
     * Saves a custom build variable into build to make it accessible for further build steps
     *
     * @param build    current build
     * @param key      key under which variable will be stored
     * @param value    value of variable
     * @param buildLog current build log
     */
    protected void saveBuildVariable(AbstractBuild build, String key, String value, PrintStream buildLog) {
        logMessage(buildLog, "Saving build variable %s", key);

        VariablesAction variablesAction = getVariableAction(build);
        String currentValue = variablesAction.getVariable(key);
        if (currentValue != null) {
            logMessage(buildLog, "Replacing current value: %s", value);
        }

        variablesAction.addVariable(key, value);
    }

    private VariablesAction getVariableAction(AbstractBuild build) {
        VariablesAction variablesAction = build.getAction(VariablesAction.class);
        if (variablesAction == null) {
            variablesAction = new VariablesAction();
            build.addAction(variablesAction);
        }
        return variablesAction;
    }

    /**
     * Waits for instance status to be equal to {@link #expectedStatus} with given {@link #timeout}
     *
     * @param buildLog build log
     * @param instance instance to be queries
     * @return true if instance gained expected status, false if timeout exceed before
     * @throws InvalidCredentialsException when credentials in configuration are invalid
     * @throws InterruptedException        when wait was interrupted
     */
    private boolean waitForInstanceStatus(PrintStream buildLog, Instance instance) throws InvalidCredentialsException, InterruptedException, ResourceNotFoundException, NotAuthorizedException {
        logMessage(buildLog, "Waiting for instance status %s with timeout of %d seconds", expectedStatus, timeout);

        StopWatch sw = new StopWatch();
        sw.start();

        int attempt = 0;

        while (true) {
            attempt++;
            logMessage(buildLog, "Attempt #%d", attempt);
            if (sw.getTime() >= timeout * 1000) {
                logMessage(buildLog, "Instance did not return expected status (%s) within given timeout of %s seconds", expectedStatus, timeout);

                return false;
            }

            InstanceStatusCode instanceStatus = getInstanceStatus(buildLog, instance).getStatus();
            if (instanceStatus.equals(expectedStatus)) {
                return true;
            }
            else if(instanceStatus.equals(InstanceStatusCode.FAILED)){
                //In case Failed status is not actually what we expect, considering it an issue
                logMessage(buildLog, "Instance returned Failed status, aborting further status wait...");
                return false;
            }

            Thread.sleep(getConfiguration().getStatusPollingInterval() * 1000);
        }

    }

    /**
     * Retrieves status of instance and outputs parameters to the log
     *
     * @param buildLog build log
     * @param instance application instance to be queried for the status
     * @return instance status
     * @throws InvalidCredentialsException when configuration contains invalid credentials
     */
    protected InstanceStatus getInstanceStatus(PrintStream buildLog, Instance instance) throws InvalidCredentialsException, ResourceNotFoundException, NotAuthorizedException {
        InstanceStatus status = getServiceFacade().getStatus(instance);

        logMessage(buildLog, "Instance status %s", status.getStatus());

        Workflow currentWorkflow = status.getCurrentWorkflow();
        if (currentWorkflow != null) {
            logMessage(buildLog, "Current workflow %s is in status %s", currentWorkflow.getName(), currentWorkflow.getStatus());
            if (currentWorkflow.getSteps() != null && currentWorkflow.getSteps().size() > 0) {
                logMessage(buildLog, "Workflow steps");
                for (WorkflowStep step : currentWorkflow.getSteps()) {
                    logMessage(buildLog, "Step: %s, Status %s, complete: %d percent", step.getName(), step.getStatus(), step.getPercentComplete());
                }
            }

            Map<String, Object> returnValues = status.getReturnValues();
            if (returnValues != null && returnValues.size() > 0) {
                logMessage(buildLog, "Instance contains %d return values", returnValues.size());
                logMessage(buildLog, "Return values dump: \n %s", JsonParser.serialize(returnValues));
            }
        }
        if (!StringUtils.isBlank(status.getErrorMessage())) {
            logMessage(buildLog, "ERROR instance status returned error %s", status.getErrorMessage());
        }

        return status;
    }

    /**
     * Outputs a log message into buildLog
     *
     * @param buildLog current build log
     * @param message  log message, optionally w/ formatting placeholders
     * @param args     arguments for string format of the message
     */
    protected void logMessage(PrintStream buildLog, String message, Object... args) {
        buildLog.printf(LOG_MESSAGE_PREFIX.concat(message).concat("\n"), args);
    }

    /**
     * Timeout value, exposed for Jelly UI
     *
     * @return value of timeout
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Relative path to output of command
     *
     * @return value of path
     */
    public String getOutputFilePath() {
        return outputFilePath;
    }

    /**
     * Target build status which should be set when instance returns failure status
     *
     * @return desired build status
     */
    public String getFailureReaction() {
        return failureReaction.toString();
    }

    public String isSelectedFailureReason(String candidate) {
        return candidate.equals(getFailureReaction()) ? "selected" : "";
    }

    /**
     * Waits for instance to be in expected status and decides whether build was successful
     * see {@link #expectedStatus}
     * build is marked is failed when status is not reached
     *
     * @param build    current
     * @param buildLog build log
     * @param instance instance of
     * @return true if expected status reached, otherwise false
     */
    protected boolean waitForExpectedStatus(AbstractBuild build, PrintStream buildLog, Instance instance) {
        try {
            if (!waitForInstanceStatus(buildLog, instance)) {

                build.setResult(failureReaction);

                return failureReaction != Result.FAILURE;
            } else {
                //Since return values not always getting populated instantly, adding an explicit wait here
                Thread.sleep(2000);
                saveReturnValues(build, buildLog, instance);
            }
        } catch (QubellServiceException e) {
            logMessage(buildLog, "Error when getting instance status: %s", e.getMessage());
            build.setResult(Result.FAILURE);
            return false;
        } catch (InterruptedException e) {
            logMessage(buildLog, "Build interrupted");
            build.setResult(Result.FAILURE);
            return false;
        } catch (IOException e) {
            build.setResult(Result.FAILURE);
            return false;
        }

        return true;
    }

    /**
     * Saves instance return values into container accessible by further builds, see {@link #saveBuildVariable(hudson.model.AbstractBuild, String, String, java.io.PrintStream)}
     * see {@link VariablesAction}
     *
     * @param build    current build
     * @param buildLog build log
     * @param instance a qubell instance to be queried for status
     * @throws InvalidCredentialsException when configuration contains invalid credentials
     */
    protected void saveReturnValues(AbstractBuild build, PrintStream buildLog, Instance instance) throws InvalidCredentialsException, IOException, ResourceNotFoundException, NotAuthorizedException {
        if (StringUtils.isEmpty(outputFilePathResolved)) {
            logMessage(buildLog, "Output file is not specified, ignoring variables save");
            return;
        }

        logMessage(buildLog, "Saving output data to file %s", outputFilePathResolved);

        InstanceStatus status = getServiceFacade().getStatus(instance);
        Map<String, Object> returnValues = status.getReturnValues();

        Map<String, Object> resultMap = new HashMap<String, Object>();
        resultMap.put("instanceId", status.getInstance().getId());
        resultMap.put("applicationId", status.getApplication().getId());
        resultMap.put("status", status.getStatus());

        if (returnValues != null && returnValues.size() > 0) {
            logMessage(buildLog, "Saving %d return values", returnValues.size());
            resultMap.put("returnValues", returnValues);
        }

        String outputContents = JsonParser.serialize(resultMap);

        saveFileToWorkspace(build, buildLog, outputContents, outputFilePathResolved);
    }

    /**
     * Saves a text file to master and slave (if available) workspaces
     *
     * @param build    current build
     * @param buildLog current build log
     * @param contents contents of file to save
     * @param filePath relative file path
     * @throws IOException when file can't be saved or folders created
     */
    protected void saveFileToWorkspace(AbstractBuild build, PrintStream buildLog, String contents, String filePath) throws IOException {
        Computer currentMachine = Computer.currentComputer();

        FilePath workspaceOutput = build.getWorkspace().child(filePath);
        try {

            workspaceOutput.getParent().mkdirs();
            workspaceOutput.write(contents, null);
            if (currentMachine instanceof SlaveComputer) {
                FilePath masterWorkspaceOutput = getMasterWorkspaceRoot(build, buildLog).child(filePath);

                masterWorkspaceOutput.getParent().mkdirs();
                masterWorkspaceOutput.write(contents, null);
            }

        } catch (IOException e) {
            logMessage(buildLog, "Unable to save file to workspace %s", e);
            throw e;
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    /**
     * Retrieves a path to workspace root on master node
     *
     * @param build    current build
     * @param buildLog build log
     * @return path to master root
     */
    protected FilePath getMasterWorkspaceRoot(AbstractBuild build, PrintStream buildLog) {
        AbstractProject project = build.getProject();
        FilePath targetDirectory;

        if (project instanceof FreeStyleProject) {
            FreeStyleProject freeStyleProject = (FreeStyleProject) project;

            if (StringUtils.isNotEmpty(freeStyleProject.getCustomWorkspace())) {
                targetDirectory = new FilePath(new File(freeStyleProject.getCustomWorkspace()));
            } else {
                targetDirectory = new FilePath(new File(freeStyleProject.getRootDir(), "workspace"));
            }
        } else {
            targetDirectory = new FilePath(new File(project.getRootDir(), "workspace"));
        }

        try {
            //Attempt to create path when does not exist
            targetDirectory.mkdirs();
        } catch (Exception e) {
            logMessage(buildLog, "unable to create target directory");
        }
        return targetDirectory;
    }

    /**
     * Replaces build/environment variables placeholders within {@code source} with their respectful values
     *
     * @param build    current build
     * @param listener build listener
     * @param source   current build listeners
     * @return string with replaced placeholders or with placeholders themselves when no variable matched for a placeholder
     * @throws IOException
     * @throws InterruptedException
     */
    protected String resolveVariableMacros(AbstractBuild build, BuildListener listener, String source) throws IOException, InterruptedException {
        if (StringUtils.isBlank(source)) {
            return source;
        }

        VariableResolver<String> vr = build.getBuildVariableResolver();
        EnvVars env = build.getEnvironment(listener);

        return env.expand(Util.replaceMacro(source, vr));
    }

    protected void resolveParameterPlaceholders(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
        this.outputFilePathResolved = resolveVariableMacros(build, listener, this.outputFilePath);
    }
}
