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
import com.qubell.services.*;
import com.qubell.services.exceptions.*;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.UUID;


/**
 * Launches a Qubell instance and saves instance id. Has to be executed before {@link RunCommandBuilder} or {@link DestroyInstanceBuilder}
 *
 * @author Alex Krupnov
 */
public class StartInstanceBuilder extends QubellBuilder {
    private static String MANIFEST_TEMP_NAME = "project_manifest.yaml";

    private final String manifestRelativePath;
    private String manifestRelativePathResolved;

    private final String environmentId;
    private String environmentIdResolved;

    private final String applicationId;
    private String applicationIdResolved;

    private final String extraParameters;
    private String extraParametersResolved;

    /**
     * Manifest file path, relative to project workspace
     *
     * @return value of path, exposed for Jelly
     */
    public String getManifestRelativePath() {
        return manifestRelativePath;
    }

    /**
     * Qubell environment id, optional
     *
     * @return environment id
     */
    public String getEnvironmentId() {
        return environmentId;
    }

    /**
     * Qubell application id to launch, required
     *
     * @return value of application id
     */
    public String getApplicationId() {
        return applicationId;
    }

    /**
     * Extra parameters as JSON object string representation
     *
     * @return value of json string
     */
    public String getExtraParameters() {
        return extraParameters;
    }

    /**
     * Data bound constructor, executed by Jenkins
     *
     * @param manifestRelativePath see {@link #getManifestRelativePath()}
     * @param timeout              see {@link #getTimeout()}
     * @param environmentId        see {@link #getEnvironmentId()}
     * @param applicationId        see {@link #getApplicationId()}
     * @param extraParameters      see {@link #getExtraParameters()}
     * @param outputFilePath       path to output file
     * @param failureReaction      a target build status which should be set when instnace returns failure status
     */
    @DataBoundConstructor
    public StartInstanceBuilder(String manifestRelativePath, String timeout, String environmentId, String applicationId, String extraParameters, String outputFilePath, String failureReaction) {
        super(timeout, InstanceStatusCode.RUNNING, outputFilePath, failureReaction);
        this.manifestRelativePath = manifestRelativePath;
        this.environmentId = environmentId;
        this.applicationId = applicationId;
        this.extraParameters = extraParameters;
    }

    /**
     * Copies manifest from build workflow (either on master or slave), into temporary folder on master
     *
     * @param build    current build
     * @param buildLog current build log
     * @return {@link FilePath} object for new temporary maifest file
     * @throws IOException          when file could not be copied/accessed
     * @throws InterruptedException when operation is interrupted
     */
    protected FilePath copyManifest(AbstractBuild build, PrintStream buildLog) throws IOException, InterruptedException {
        logMessage(buildLog, "copying manifest from current build workspace. Relative path is %s", manifestRelativePathResolved);

        FilePath destinationManifest = getTemporaryManifestPath(build, buildLog);
        FilePath sourceManifest = build.getWorkspace().child(manifestRelativePathResolved);

        if (!sourceManifest.exists()) {
            logMessage(buildLog, "Unable to find manifest file with relative path %s, target file %s does not exist\n", manifestRelativePathResolved, sourceManifest.toURI());
            throw new FileNotFoundException("Manifest not found");
        }

        //logMessage(buildLog,  "Copying manifest running on  %s at %s to %s", currentMachine, sourceManifest.toURI(), destinationManifest.toURI());

        sourceManifest.copyTo(destinationManifest);
        return destinationManifest;
    }

    private FilePath getTemporaryManifestPath(AbstractBuild build, PrintStream buildLog) {
        FilePath masterWorkspaceRoot = getMasterWorkspaceRoot(build, buildLog);

        //Using base file name + guid to ensure there are no conflicts in manifest
        return masterWorkspaceRoot.child(UUID.randomUUID().toString() + "." + MANIFEST_TEMP_NAME);
    }

    /**
     * Performs a build with following steps
     * <ol>
     * <li>Copies manifest into temp folder</li>
     * <li>Updates manifest for qubell application</li>
     * <li>Launches application instance</li>
     * <li>Waits when instance turned into Running state</li>
     * <li>Saves instance id for {@link RunCommandBuilder} or {@link DestroyInstanceBuilder}</li>
     * </ol>
     *
     * @param build    current build
     * @param launcher build launcher
     * @param listener listener
     * @return true of builder did not fail, otherwise false
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        resolveParameterPlaceholders(build, listener);

        FilePath manifestFile;

        Manifest manifest;

        PrintStream buildLog = listener.getLogger();

        if (!validateConfiguration()) {
            logMessage(buildLog, "Unable to proceed without configuration. Please check global settings page.");

            build.setResult(Result.FAILURE);
            return false;
        }

        Application application = new Application(applicationIdResolved);
        Integer updatedVersion = 0;

        if (!StringUtils.isBlank(manifestRelativePathResolved)) {
            try {
                manifestFile = copyManifest(build, buildLog);
                manifest = new Manifest(manifestFile.readToString());

                logMessage(buildLog, "Deleting temporary manifestFile");
                manifestFile.delete();
            } catch (FileNotFoundException fnfe) {
                logMessage(buildLog, "Unable to proceed without manifest");
                build.setResult(Result.FAILURE);
                return false;
            } catch (IOException ioe) {
                logMessage(buildLog, "Unable to read manifest file");
                build.setResult(Result.FAILURE);
                return false;
            }

            logMessage(buildLog, "Updating app manifest");

            try {
                updatedVersion = getServiceFacade().updateManifest(application, manifest);
                logMessage(buildLog, "Manifest updated. New version is %s", updatedVersion.toString());
            } catch (QubellServiceException e) {
                logMessage(buildLog, "Error when updating manifest: %s", e.getMessage());
                build.setResult(Result.FAILURE);
                return false;
            }
        }

        Instance instance;
        try {
            instance = getServiceFacade().launchInstance(new InstanceSpecification(application, updatedVersion),
                    new LaunchSettings(new Environment(environmentIdResolved), JsonParser.parseMap(extraParametersResolved)));

            logMessage(buildLog, "Launched instance %s", instance.getId());
            saveBuildVariable(build, INSTANCE_ID_KEY, instance.getId(), buildLog);

        } catch (QubellServiceException e) {
            logMessage(buildLog, "Error when launching instance: %s", e.getMessage());
            build.setResult(Result.FAILURE);
            return false;
        }

        return waitForExpectedStatus(build, buildLog, instance);
    }

    @Override
    protected void resolveParameterPlaceholders(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
        super.resolveParameterPlaceholders(build, listener);

        this.manifestRelativePathResolved = resolveVariableMacros(build, listener, this.manifestRelativePath);
        this.environmentIdResolved = resolveVariableMacros(build, listener, this.environmentId);
        this.applicationIdResolved = resolveVariableMacros(build, listener, this.applicationId);
        this.extraParametersResolved = resolveVariableMacros(build, listener, this.extraParameters);
    }

    /**
     * Descriptor for {@link StartInstanceBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     * See <tt>src/main/resources/com/qubell/jenkinsci/plugins/qubell/builders/StartInstanceBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class StartInstanceDescriptor extends BaseDescriptor {
        /**
         * Performs on-the-fly validation of the form field application id
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckApplicationId(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please specify an application id");
            return FormValidation.ok();
        }

        /**
         * Gets application list json for typeahead functionality
         *
         * @return json object for apps list
         * @throws InvalidCredentialsException when credentials invalid
         * @throws NotAuthorizedException      when user not authorized to list applications
         * @throws ResourceNotFoundException   when organization not found
         */
        public String getApplicationsTypeAheadJson() throws InvalidCredentialsException, NotAuthorizedException, ResourceNotFoundException {
            return JsonParser.serialize(new QubellFacadeImpl(Configuration.get()).getAllApplications());
        }

        /**
         * Gets environments list json for typeahead functionality
         *
         * @return json object for envs list
         * @throws InvalidCredentialsException when credentials invalid
         * @throws NotAuthorizedException      when user not authorized to list environments
         * @throws ResourceNotFoundException   when organization not found
         */
        public String getEnvironmentsTypeAheadJson() throws InvalidCredentialsException, NotAuthorizedException, ResourceNotFoundException {
            return JsonParser.serialize(new QubellFacadeImpl(Configuration.get()).getAllEnvironments());
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Qubell: Launch Application Instance";
        }
    }

}

