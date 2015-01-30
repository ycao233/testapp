package com.microsoft.tfs.plugin;

import com.microsoft.vss.client.distributedtask.model.TaskLog;
import hudson.console.ConsoleNote;
import hudson.console.LineTransformationOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Created by yacao on 12/31/2014.
 */
public class TfsRemoteConsoleLogAppender extends LineTransformationOutputStream {

    private static final Logger logger = Logger.getLogger(TfsRemoteConsoleLogAppender.class.getName());

    public final OutputStream delegate;

    private final TfsClient client;
    private final TfsBuildInstance buildInstance;
    private final ScheduledExecutorService executorService;
    private final BlockingQueue<String> logs;

    public TfsRemoteConsoleLogAppender(OutputStream delegate, TfsClient client, TfsBuildInstance buildInstance) {
        this.delegate = delegate;
        this.logs = new LinkedBlockingQueue<String>();
        this.executorService = Executors.newScheduledThreadPool(1);
        this.client = client;
        this.buildInstance = buildInstance;

        this.logger.info("Initialized Tfs Remote Console log appender");
    }

    @Override
    protected void eol(byte[] b, int len) throws IOException {
        delegate.write(b, 0, len);

        String line = ConsoleNote.removeNotes(new String(b, 0, len)).trim();
        logs.offer(line);
    }

    public void flush() throws IOException {
        delegate.flush();
    }

    public void close() throws IOException {
        delegate.close();
        this.executorService.shutdown();

        try {
            if (this.executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.info("Thread pool has terminated.");

                if (!logs.isEmpty()) {
                    logger.info(String.format("Append %d remaining logs.", logs.size()));

                    List<String> lines = new ArrayList<String>(logs.size());
                    logs.drainTo(lines);
                    client.postConsoleFeed(buildInstance.getPlanId(), buildInstance.getTimelineId(), buildInstance.getJobRecordId(), lines);
                }

            } else {
                logger.warning("Log appender took more than 30 seconds to complete, log maybe incomplete on remote console.");
            }

        } catch (InterruptedException e) {
            logger.warning("Console log appender interrupted, log maybe incomplete on remote console.");
        }

        TaskLog log = client.createLog(buildInstance.getPlanId(), buildInstance.getTimelineId());
        client.appendLog(buildInstance.getPlanId(), log.getId(), buildInstance.getJenkinsBuild());
    }

    public void start() {
        final Runnable logAppender = new Runnable() {

            @Override
            public void run() {
                List<String> lines = new ArrayList<String>(100);

                String line;
                while ((line = logs.poll()) != null) {
                    lines.add(line);

                    if (lines.size() >= 100) {
                        //TFS Client must post this logs
                        client.postConsoleFeed(buildInstance.getPlanId(), buildInstance.getTimelineId(), buildInstance.getJobRecordId(), lines);
                        lines.clear();
                    }
                }

                if (!lines.isEmpty()) {
                    client.postConsoleFeed(buildInstance.getPlanId(), buildInstance.getTimelineId(), buildInstance.getJobRecordId(), lines);
                }
            }
        };

        logger.info("TFS remote console log appender started");
        executorService.scheduleWithFixedDelay(logAppender, 1, 1, TimeUnit.SECONDS);
    }
}
