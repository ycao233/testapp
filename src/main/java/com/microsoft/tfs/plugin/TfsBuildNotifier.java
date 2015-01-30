package com.microsoft.tfs.plugin;

import com.microsoft.vss.client.build.model.Build;
import com.microsoft.vss.client.build.model.BuildDefinition;
import com.microsoft.vss.client.build.model.enumeration.BuildResult;
import com.microsoft.vss.client.build.model.enumeration.BuildStatus;
import com.microsoft.vss.client.core.model.TeamProjectReference;
import com.microsoft.vss.client.distributedtask.model.TaskLog;
import com.microsoft.vss.client.distributedtask.model.TaskResult;
import com.microsoft.vss.client.distributedtask.model.TimelineRecord;
import com.microsoft.vss.client.distributedtask.model.enumeration.TimelineRecordState;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

import static hudson.model.Result.*;

/**
 * Created by yacao on 12/18/2014.
 */
public class TfsBuildNotifier extends Notifier {

    private static final Logger logger = Logger.getLogger(TfsBuildNotifier.class.getName());

    public final String serverUrl;
    public final String username;
    public final Secret password;
    public final String collection;
    public final String project;
    public final String buildDefinition;

    /* TODO: replace with API client */
    private PrintWriter pw;

    private TfsClient client;

    private TfsConfiguration config;

    @DataBoundConstructor
    public TfsBuildNotifier(String serverUrl, String username, Secret password, String collection, String project, String buildDefinition) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.collection = collection;
        this.project = project;
        this.buildDefinition = buildDefinition;

        this.config = new TfsConfiguration(this.serverUrl, this.username, this.password,
                this.collection, this.project, this.buildDefinition);

    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        /* We need to run after build is marked complete to get the accurate build duration time
           to send to VSO.  We can not fail the build at this point.
        */
        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {

        // Get VSO build environments
        // TODO: Create build if we don't have plan id
        Map<String, String> env = build.getBuildVariables();
        int vsoBuildId = Integer.valueOf(env.get("VssBuildId" + build.getId()));
        UUID planId = UUID.fromString(env.get("VssPlanId" + build.getId()));
        UUID timelineId = UUID.fromString(env.get("VssTimelineId" + build.getId()));
        logger.info("notifier plan Id: "+ planId);
        logger.info("notifier build Id: "+ vsoBuildId);

        try {
            client = TfsClient.newClient(this.serverUrl, this.username, this.password);
            client.updateBuild(vsoBuildId, build);
            List<TimelineRecord> records = client.getRecords(planId, timelineId);
            if (records != null) {
                for (TimelineRecord record : records) {
                    record.setState(TimelineRecordState.Completed);
                    if (build.getResult() == Result.SUCCESS) {
                        record.setResult(TaskResult.Succeeded);
                    } else if (build.getResult() == Result.FAILURE) {
                        record.setResult(TaskResult.Failed);
                    } else if (build.getResult() == Result.ABORTED) {
                        record.setResult(TaskResult.Canceled);
                    } else {
                        record.setResult(TaskResult.Abandoned);
                    }

                    record.setFinishTime(new Date());
                }

                client.updateRecords(planId, timelineId, records);
            }



        } catch (URISyntaxException e) {
            e.printStackTrace();
            logger.severe(e.getMessage());

            return false;
        }

        return true;
    }

    public synchronized TfsConfiguration getConfig() {
        if (this.config == null) {
           this.config = new TfsConfiguration(serverUrl, username, password, collection, project, buildDefinition);
        }

        return this.config;
    }

    @Extension
    public static class Descriptor extends BuildStepDescriptor<Publisher> {

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        private transient TfsClient client;

        public String getDisplayName() {
            /* TODO: localization */
            return "TFS Notifier";
        }

        public FormValidation doCheckServerUrl(@QueryParameter String serverUrl) {
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                /* TODO: localization */
                return FormValidation.error("Please enter the URL for Team Foundation Service host");
            }

            try {
                new URL(serverUrl);
                return FormValidation.ok();

            } catch (MalformedURLException e) {
                return FormValidation.error(e.getLocalizedMessage());
            }
        }

        public FormValidation doCheckPassword(@QueryParameter String password) {
            return FormValidation.validateRequired(password);
        }

        public FormValidation doTestConnection(@QueryParameter String serverUrl, @QueryParameter String username,
                                               @QueryParameter Secret password, @QueryParameter String project, @QueryParameter String buildDefinition) {

            try {
                if (!validInputs(serverUrl, username, password, project, buildDefinition)) {
                    return FormValidation.error("Input fields are invalid");
                }
                client = TfsClient.newClient(serverUrl, username, password);

                //  project and buildDefinition are IDs
                TeamProjectReference projectReference = client.getProjectClient().getProject(project);
                if (projectReference != null) {
                    BuildDefinition definition = client.getBuildClient().getDefinition(projectReference.getId(), Integer.valueOf(buildDefinition));

                    if (definition != null) {
                        return FormValidation.ok("Build definition exists");
                    }
                }

            } catch (URISyntaxException e) {
                e.printStackTrace();
                return FormValidation.error(e.getMessage());
            }

            return FormValidation.error("Could not find the specified buildDefinition from " + serverUrl);
        }

        public ListBoxModel doFillProjectItems(@QueryParameter String serverUrl, @QueryParameter String username,
                @QueryParameter Secret password) throws URISyntaxException {

            ListBoxModel items = new ListBoxModel();

            if (validInputs(serverUrl, username, password)) {
                client = TfsClient.newClient(serverUrl, username, password);

                List<TeamProjectReference> references = client.getProjectClient().getProjects();

                for (TeamProjectReference ref : references) {
                    items.add(ref.getName(), String.valueOf(ref.getId()));
                }
            }

            return items;
        }

        public ListBoxModel doFillBuildDefinitionItems(@QueryParameter String serverUrl, @QueryParameter String username,
                @QueryParameter Secret password, @QueryParameter String project) throws URISyntaxException {

            ListBoxModel items = new ListBoxModel();

            if (validInputs(serverUrl, username, password, project)) {
                client = TfsClient.newClient(serverUrl, username, password);
                List<BuildDefinition> definitions = client.getBuildClient().getDefinitions(UUID.fromString(project));

                for (BuildDefinition definition : definitions) {
                    items.add(definition.getName(), String.valueOf(definition.getId()));
                }
            }

            return items;
        }

        private boolean isNullOrEmpty(String s) {
            return s == null || s.length() == 0;
        }

        private boolean validInputs(Object... inputs) {
            for (Object input : inputs) {
                if (input == null) {
                    return false;
                }
                if (input instanceof String && isNullOrEmpty((String)input)) {
                    return false;
                }
            }

            return true;
        }
    }
}
