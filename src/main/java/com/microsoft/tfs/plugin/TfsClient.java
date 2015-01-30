package com.microsoft.tfs.plugin;

import com.microsoft.vss.client.build.BuildHttpClient;
import com.microsoft.vss.client.build.model.Build;
import com.microsoft.vss.client.build.model.BuildDefinition;
import com.microsoft.vss.client.build.model.QueueReference;
import com.microsoft.vss.client.build.model.enumeration.BuildResult;
import com.microsoft.vss.client.build.model.enumeration.BuildStatus;
import com.microsoft.vss.client.build.model.enumeration.QueueOptions;
import com.microsoft.vss.client.core.model.TeamProjectReference;
import com.microsoft.vss.client.distributedtask.DistributedTaskHttpClient;
import com.microsoft.vss.client.distributedtask.model.TaskLog;
import com.microsoft.vss.client.distributedtask.model.TaskLogReference;
import com.microsoft.vss.client.distributedtask.model.Timeline;
import com.microsoft.vss.client.distributedtask.model.TimelineRecord;
import com.microsoft.vss.client.distributedtask.model.enumeration.TimelineRecordState;
import com.microsoft.vss.client.project.ProjectHttpClient;
import hudson.model.AbstractBuild;
import hudson.util.Secret;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Time;
import java.util.*;
import java.util.logging.Logger;

import static hudson.model.Result.ABORTED;
import static hudson.model.Result.FAILURE;
import static hudson.model.Result.SUCCESS;

/**
 * Created by yacao on 12/30/2014.
 */
public class TfsClient {

    private static final Logger logger = Logger.getLogger(TfsClient.class.getName());

    private BuildHttpClient buildClient;
    private ProjectHttpClient projectClient;
    private DistributedTaskHttpClient distributedTaskHttpClient;

    public synchronized static TfsClient newClient(String url, String username, Secret password) throws URISyntaxException {
        return new TfsClient(url, username, password);
    }

    public OutputStream getLogStream(AbstractBuild build) throws FileNotFoundException {
        /* build is guaranteed to be non-null*/
        File rootDir = build.getRootDir();

        return new FileOutputStream(new File(rootDir, "vsolog.log"));
    }


    private Client getClient(String username, Secret password) {
        ClientConfig clientConfig = new ClientConfig();

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
                new UsernamePasswordCredentials(username, Secret.toString(password)));

        clientConfig.property(ApacheClientProperties.PREEMPTIVE_BASIC_AUTHENTICATION, true);
        clientConfig.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED);

        if( System.getProperty("EnableFiddler") != null) {
            clientConfig.property(ClientProperties.PROXY_URI, "http://127.0.0.1:8888");
            clientConfig.property(ApacheClientProperties.SSL_CONFIG, getSslConfigurator());
        }

        clientConfig.property(ApacheClientProperties.CREDENTIALS_PROVIDER, credentialsProvider);
        clientConfig.connectorProvider(new ApacheConnectorProvider());

        return ClientBuilder.newClient(clientConfig);
    }

    private static SslConfigurator getSslConfigurator() {
        String keystore = System.getProperty("FiddlerKeystore"); //E:\\FiddlerKeystore.jks
        String storePass = System.getProperty("FiddlerKeystorePass"); //"fiddler"

        final SslConfigurator sslConfig = SslConfigurator.newInstance().trustStoreFile(keystore) //$NON-NLS-1$
                .trustStorePassword(storePass) //$NON-NLS-1$
                .trustStoreType("JKS") //$NON-NLS-1$
                .trustManagerFactoryAlgorithm("PKIX") //$NON-NLS-1$
                .securityProtocol("SSL"); //$NON-NLS-1$

        return sslConfig;
    }

    private TfsClient(String url, String username, Secret password) throws URISyntaxException {
        URI uri = new URI(url);

        Client client = this.getClient(username, password);

        buildClient = new BuildHttpClient(client, uri);
        projectClient = new ProjectHttpClient(client, uri);
        distributedTaskHttpClient =  new DistributedTaskHttpClient(client, uri);
    }

    public BuildHttpClient getBuildClient() {
        return buildClient;
    }

    public ProjectHttpClient getProjectClient() {
        return projectClient;
    }

    public DistributedTaskHttpClient getDistributedTaskHttpClient() {
        return distributedTaskHttpClient;
    }

    private Build createBuild(TeamProjectReference project, BuildDefinition definition, QueueReference queue) {
        Build b = new Build();
        b.setQueue(queue);
        b.setDefinition(definition);
        b.setProject(project);

        b.setParameters("{}");
        b.setDemands(Collections.<String>emptyList());

        b.setQueueOptions(QueueOptions.DoNotRun);

        return b;
    }

    public Build queueBuild(String projectId, int buildDefinition) {
        List<QueueReference> queues = buildClient.getQueues("*");
        if (queues == null || queues.isEmpty()) {
            return null;
        }

        TeamProjectReference project = projectClient.getProject(projectId);
        if (project != null) {
            BuildDefinition definition = buildClient.getDefinition(project.getId(), buildDefinition);
            if (definition != null) {
                QueueReference anyQueue = queues.get(0);
                Build vsoBuild = this.createBuild(project, definition, anyQueue);

                // ignore warnings as we don't require any agent or demand be met
                Build queuedBuild = buildClient.queueBuild(vsoBuild, true);

                logger.info(String.format("Queued build with plan Id %s", queuedBuild.getOrchestrationPlan().getPlanId()));
                return queuedBuild;
            }
        }

        return null;
    }

    public Build startBuild(int vsoBuildId) {
        Build vsoBuild = new Build();
        vsoBuild.setId(vsoBuildId);
        vsoBuild.setStatus(BuildStatus.InProgress);
        vsoBuild.setStartTime(new Date());

        return this.getBuildClient().updateBuild(vsoBuild);
    }

    public Build updateBuild(int vsoBuildId, AbstractBuild<?, ?> jenkinsBuild) {
        Build vsoBuild = new Build();
        vsoBuild.setId(vsoBuildId);

        vsoBuild.setStatus(BuildStatus.Completed);
        BuildResult result;
        if (jenkinsBuild.getResult() == SUCCESS) {
            result = BuildResult.Succeeded;
        } else if (jenkinsBuild.getResult() == FAILURE) {
            result = BuildResult.Failed;
        } else if (jenkinsBuild.getResult() == ABORTED) {
            result = BuildResult.Canceled;
        } else {
            result = BuildResult.Stopped;
        }

        vsoBuild.setFinishTime(new Date());
        vsoBuild.setResult(result);

        /* also need to update all the timeline records */

        return this.getBuildClient().updateBuild(vsoBuild);
    }

    public Timeline updateTimelineWithJenkinsTasks(UUID planId) {
        List<Timeline> timelines = this.getDistributedTaskHttpClient().getTimelines(planId);
        for (Timeline timeline : timelines) {
            logger.info("timeline id: "+timeline.getId());
        }

        if (timelines.size() < 1) {
            throw new RuntimeException("timeline not created");
        }

        Timeline timeline = timelines.get(0);

        List<TimelineRecord> records = this.getRecords(planId, timeline.getId());
        if (records == null || records.isEmpty()) {
            records = new ArrayList<TimelineRecord>();
        }

        TimelineRecord jobRecord = null;
        for (TimelineRecord record : records) {
            if (record.getRecordType().equalsIgnoreCase("Job")) {
                jobRecord = record;
            }
        }

        if (jobRecord == null) {
            jobRecord = new TimelineRecord();
            jobRecord.setId(UUID.randomUUID());
            jobRecord.setRecordType("Job");
            jobRecord.setName("Build");

            records.add(jobRecord);
        }

        jobRecord.setState(TimelineRecordState.InProgress);
        jobRecord.setStartTime(new Date());
        jobRecord.setWorkerName("Jenkins");

        TimelineRecord jenkinsTaskRecord = new TimelineRecord();
        jenkinsTaskRecord.setId(UUID.randomUUID());
        jenkinsTaskRecord.setState(TimelineRecordState.InProgress);
        jenkinsTaskRecord.setRecordType("Task");
        jenkinsTaskRecord.setName("Jenkins Build");
        jenkinsTaskRecord.setStartTime(new Date());
        jenkinsTaskRecord.setParentId(jobRecord.getId());

        records.add(jenkinsTaskRecord);
        timeline.setRecords(records);

        return timeline;
    }

    public List<TimelineRecord> getRecords(UUID planId, UUID timelineId) {
        return this.getDistributedTaskHttpClient().getRecords(planId, timelineId);
    }

    public List<TimelineRecord> updateRecords(UUID planId, UUID timelineId, List<TimelineRecord> records) {
        return getDistributedTaskHttpClient().updateRecords(planId, timelineId, records);
    }

    public void postConsoleFeed(UUID planId, UUID timelineId, UUID recordId, List<String> lines) {
        logger.info(String.format("I am posting %d lines to remote console", lines.size()));
        this.getDistributedTaskHttpClient().appendTimelineRecordFeed(planId, timelineId, recordId, lines);
    }

    public TaskLog createLog(UUID planId, UUID timelineId) {
        List<TaskLog> logs = this.getDistributedTaskHttpClient().getLogs(planId);
        TaskLog jobLog = null;
        if (logs == null || logs.isEmpty()) {
            TaskLog log = new TaskLog();
            log.setId(0);
            log.setPath("logs\\" + UUID.randomUUID());
            logger.info("There is no log defined. Creating log for plan: " + planId);
            this.getDistributedTaskHttpClient().createLog(planId, log);
        }

        logs = this.getDistributedTaskHttpClient().getLogs(planId);
        if (logs != null && logs.size() > 0) {
            logger.info("Log is: "+logs.get(0).getId() + " path: "+logs.get(0).getPath());
            jobLog = logs.get(0);
        }

        List<TimelineRecord> records = this.getRecords(planId, timelineId);
        for (TimelineRecord record : records) {
            if (record.getRecordType().equalsIgnoreCase("Job")) {
                TaskLogReference logReference = new TaskLogReference();
                logReference.setId(jobLog.getId());
                logReference.setLocation(jobLog.getLocation());
                record.setLog(logReference);
            }
        }

        this.updateRecords(planId, timelineId, records);

        return jobLog;
    }

    public void appendLog(UUID planId, int logid, AbstractBuild b) {
        File rootDir = b.getRootDir();

        try {
            this.getDistributedTaskHttpClient().appendLog(planId, logid, new FileInputStream(new File(rootDir, "vsolog.log")));
        } catch (FileNotFoundException e) {
            logger.severe("Failed to append log");
            logger.severe(e.getMessage());
        }
    }

}
