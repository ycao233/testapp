package com.microsoft.tfs.plugin;

import com.microsoft.vss.client.build.model.Build;
import com.microsoft.vss.client.distributedtask.model.Timeline;
import com.microsoft.vss.client.distributedtask.model.TimelineRecord;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.DescribableList;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Created by yacao on 12/17/2014.
 */
public class TfsBuildWrapper extends BuildWrapper {

    private static final Logger logger = Logger.getLogger(TfsBuildWrapper.class.getName());

    private TfsConfiguration config = null;
    private TfsClient tfsClient = null;
    private TfsBuildInstance tfsBuildinstance = null;

    @DataBoundConstructor
    public TfsBuildWrapper() {
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) {
        if (this.config != null) {
            /* TODO localization*/
            listener.getLogger().println("Sending outputs to VSO.");
            try {
                TfsClient client = getClient(this.config);
            } catch (URISyntaxException e) {
                listener.getLogger().println("Failed in creating VSO log appender: ");
                listener.getLogger().println(e.getMessage());
            }
        } else {
            /* TODO localization*/
            listener.getLogger().println("Job is misconfigured.  Must also add a post-build step to send build statistics to VSO.");
        }

        return new Environment() {};
    }

    @Override
    public OutputStream decorateLogger(AbstractBuild build, OutputStream os) throws FileNotFoundException {
        DescribableList describableList = build.getProject().getPublishersList();
        if (describableList != null) {
            Describable notifier = describableList.get(TfsBuildNotifier.class);
            if (notifier != null && notifier instanceof TfsBuildNotifier) {
                this.config = ((TfsBuildNotifier) notifier).getConfig();
                logger.info("Configured TfsBuildNotifier");
                logger.info("configuration: " + this.config);

                TfsClient client = null;
                try {
                    client = getClient(this.config);
                    Build vsoBuild = client.queueBuild(this.config.getProject(), Integer.valueOf(this.config.getBuildDefinition()));
                    tfsBuildinstance = createTfsBuildInstance(client, vsoBuild, build);

                    client.startBuild(this.tfsBuildinstance.getBuildId());
                    client.updateRecords(tfsBuildinstance.getPlanId(), tfsBuildinstance.getTimelineId(), tfsBuildinstance.getRecords());

                    // Generate log file to be uploaded later
                    logger.info("root dir: " + build.getRootDir());
                    OutputStream tfsLoggingStream = new TfsFileLogOutputStream(os, client.getLogStream(build));

                    // Post logs to TFS server's build console
                    TfsRemoteConsoleLogAppender appender = new TfsRemoteConsoleLogAppender(tfsLoggingStream, client, tfsBuildinstance);
                    appender.start();

                    return appender;
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    logger.info("Failed to create TFS client, do not decorate the output logger");
                    logger.fine("exception: "+e);
                }
            }
        }

        logger.info("TfsBuildNotifier is not configured, do not decorate the output logger");
        return os;
    }

    @Override
    public void makeBuildVariables(AbstractBuild build, Map<String,String> env) {
        /* TODO: review variable name */
        logger.info("environments:");
        logger.info("build id: "+this.tfsBuildinstance.getBuildId());
        logger.info("plan id: " + this.tfsBuildinstance.getPlanId());
        env.put("VssBuildId" + build.getId(), String.valueOf(this.tfsBuildinstance.getBuildId()));
        env.put("VssPlanId" + build.getId(), this.tfsBuildinstance.getPlanId().toString());
        env.put("VssTimelineId"+build.getId(), tfsBuildinstance.getTimelineId().toString());
    }

    private TfsBuildInstance createTfsBuildInstance(TfsClient client, Build vsoBuild, AbstractBuild jenkinsBuild) {
        TfsBuildInstance buildInstance = new TfsBuildInstance(vsoBuild, jenkinsBuild);

        UUID planId = vsoBuild.getOrchestrationPlan().getPlanId();

        Timeline timeline = client.updateTimelineWithJenkinsTasks(planId);
        buildInstance.setTimeline(timeline);

        List<TimelineRecord> records = timeline.getRecords();
        for (TimelineRecord record : records) {
            logger.info(record.getName()+ " "+record.getRecordType()+ " "+record.getId());
            if (record.getRecordType().equals("Job")) {
                buildInstance.setJobRecordId(record.getId());
            }  else {
                buildInstance.setTaskId(record.getName(), record.getId());
            }
        }

        return buildInstance;
    }

    private synchronized TfsClient getClient(TfsConfiguration config) throws URISyntaxException {
        if (this.tfsClient == null) {
            return TfsClient.newClient(this.config.getServerUrl(), this.config.getUsername(), this.config.getPassword());
        }

        return tfsClient;
    }

    /* used in unit test to inject TfsClient dependency */
    void setClient(TfsClient client) {
        this.tfsClient = client;
    }

    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(TfsBuildWrapper.class);
            load();
        }

        public String getDisplayName() {
            return "TFS Build remote log appender";
        }

        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }
    }
}
