package com.microsoft.tfs.plugin;

import com.microsoft.vss.client.build.model.Build;
import com.microsoft.vss.client.distributedtask.model.Timeline;
import com.microsoft.vss.client.distributedtask.model.TimelineRecord;
import hudson.model.AbstractBuild;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TfsBuildInstance {
    private AbstractBuild jenkinsBuild;
    private Build vsoBuild;
    private Timeline timeline;
    private UUID jobRecordId;
    private Map<String, UUID> taskRecordsMap;

    public TfsBuildInstance(final Build vsoBuild, final AbstractBuild jenkinsBuild) {
        this.vsoBuild = vsoBuild;
        this.jenkinsBuild = jenkinsBuild;
        this.taskRecordsMap = new HashMap<String, UUID>();
    }

    public int getBuildId() {
        return vsoBuild.getId();
    }

    public UUID getPlanId() {
        return vsoBuild.getOrchestrationPlan().getPlanId();
    }

    public UUID getTimelineId() {
        return timeline.getId();
    }

    public List<TimelineRecord> getRecords() {
        return timeline.getRecords();
    }

    public void setTimeline(Timeline timeline) {
        this.timeline = timeline;
    }

    public UUID getJobRecordId() {
        return jobRecordId;
    }

    public void setJobRecordId(UUID jobRecordId) {
        this.jobRecordId = jobRecordId;
    }

    public UUID getTaskId(String taskName) {
        return taskRecordsMap.get(taskName);
    }

    public UUID setTaskId(String taskName, UUID taskId) {
        return taskRecordsMap.put(taskName, taskId);
    }

    public AbstractBuild getJenkinsBuild() {
        return jenkinsBuild;
    }

    public void setJenkinsBuild(AbstractBuild jenkinsBuild) {
        this.jenkinsBuild = jenkinsBuild;
    }
}
